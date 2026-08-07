package net.minestom.web.internal.http;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.Javalin;
import io.javalin.compression.CompressionStrategy;
import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.staticfiles.Location;
import io.javalin.plugin.bundled.CorsPluginConfig;
import net.minestom.web.*;
import net.minestom.web.internal.expression.ExpressionEngine;
import net.minestom.web.internal.http.routes.*;
import net.minestom.web.internal.expression.QueryEngine;
import net.minestom.web.internal.renderer.ItemIconRenderer;
import net.minestom.web.internal.replay.ReplaySource;
import net.minestom.web.internal.session.ActionRunner;
import net.minestom.web.internal.scope.DashboardScope;
import net.minestom.web.internal.scope.ScopeSessionBridge;
import net.minestom.web.internal.session.MailboxException;
import net.minestom.web.internal.session.PlayerView;
import net.minestom.web.internal.session.SessionRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

/// Javalin HTTP + WebSocket dashboard. Every request and every WS connection is bound to a
/// [DashboardScope] — live mode has a single default scope owning the proxy + persistence; replay
/// mode creates a fresh scope per uploaded SQLite file, isolated to the requesting browser
/// tab. Scope id travels on the `X-Replay-Id` header (REST) or `?replay=` query (WS).
public final class DashboardServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardServer.class);
    private static final long REPLAY_IDLE_TTL_MS = 30 * 60 * 1000L;
    private static final long DISCONNECTED_PLAYER_TTL_MS = 30 * 60 * 1000L;
    private static final long MAX_REPLAY_BYTES = 512L * 1024 * 1024;
    private static final long RATE_BUCKET_IDLE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final ProxyConfig config;
    private final ConcurrentHashMap<String, DashboardScope> scopes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<io.javalin.websocket.WsContext, DashboardScope> wsScope = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<ScheduledFuture<?>>> scopeTickers = new ConcurrentHashMap<>();
    private final RateLimiter postLimiter = new RateLimiter(30, 30);
    private final ItemIconRenderer itemIcons = new ItemIconRenderer();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2,
            r -> Thread.ofVirtual().name("web-scheduler").unstarted(r));
    private final ScopeRouter routeCtx;

    private Javalin app;

    public DashboardServer(ProxyConfig config) {
        this.config = config;
        this.routeCtx = new ScopeRouter(scopes);
        this.routeCtx.setReplayLifecycle(this::createDashboardScope, this::removeScope);
    }

    // ---- scope management ----------------------------------------------------------------

    public void setLiveScope(DashboardScope scope) {
        registerScope(scope);
        routeCtx.setDefaultScopeId(scope.id);
    }

    public void addDashboardScope(DashboardScope scope) {
        registerScope(scope);
    }

    private void registerScope(DashboardScope scope) {
        scopes.put(scope.id, scope);
        // Registers session listeners on `scope` that drive persistence + WS fan-out; the
        // instance itself is not retained.
        new ScopeSessionBridge(scope);
        scope.wireControlSinks();
        final List<ScheduledFuture<?>> tickers = new ArrayList<>();
        tickers.add(scheduler.scheduleAtFixedRate(scope::sampleMetrics, 1, 1, TimeUnit.SECONDS));
        tickers.add(scheduler.scheduleAtFixedRate(scope::flushPacketAggregate, 250, 250, TimeUnit.MILLISECONDS));
        tickers.add(scheduler.scheduleAtFixedRate(scope::publishPlayersSummary, 500, 500, TimeUnit.MILLISECONDS));
        scopeTickers.put(scope.id, tickers);
        LOGGER.info("Scope {} registered ({})", scope.id, scope.isReplay() ? "replay" : "live");
    }

    public void removeScope(String id) {
        final DashboardScope scope = scopes.remove(id);
        if (scope == null) return;
        if (id.equals(routeCtx.defaultScopeId())) routeCtx.setDefaultScopeId(null);
        stopScopeTickers(id);
        try { scope.close(); } catch (Exception _) {}
        LOGGER.info("Scope {} removed", id);
    }

    private void stopScopeTickers(String id) {
        final List<ScheduledFuture<?>> tickers = scopeTickers.remove(id);
        if (tickers == null) return;
        for (ScheduledFuture<?> ticker : tickers) ticker.cancel(false);
    }

    // ---- server lifecycle ----------------------------------------------------------------

    public void start() {
        final byte[] indexHtml = readResource("/web/index.html");
        app = Javalin.create(cfg -> {
            cfg.staticFiles.add(staticFiles -> {
                staticFiles.directory = "/web";
                staticFiles.location = Location.CLASSPATH;
                staticFiles.hostedPath = "/";
                // Pre-compress + cache the static bundle (app.js ~500KB, style.css ~140KB) so
                // they aren't re-gzipped per request.
                staticFiles.precompressMaxSize = 8 * 1024 * 1024;
            });
            cfg.spaRoot.addHandler("/", ctx -> ctx.contentType("text/html").result(indexHtml));
            cfg.bundledPlugins.enableCors(cors -> cors.addRule(CorsPluginConfig.CorsRule::anyHost));
            cfg.concurrency.useVirtualThreads = true;
            cfg.startup.showJavalinBanner = false;
            cfg.http.maxRequestSize = MAX_REPLAY_BYTES;
            // gzip the JS/CSS bundle + all JSON responses (brotli4j native dep isn't on the
            // classpath, so gzip-only). Cuts first-load JS+CSS transfer ~640KB → ~160KB.
            cfg.http.compressionStrategy = CompressionStrategy.GZIP;

            cfg.routes.before("/api/*", this::checkAuth);
            cfg.routes.before("/api/*", this::checkRateLimit);
            cfg.routes.before("/api/*", routeCtx::resolveScopeMiddleware);

            cfg.routes.exception(MailboxException.class, (e, ctx) ->
                    ctx.status(e.httpStatus()).result(e.httpMessage()));
            // Client-input failures are 400, not Javalin's default 500: a malformed JSON body,
            // a bad path/enum/address value, and MQL compile errors all surface as these. Genuine
            // server bugs (NPE, IllegalStateException) still fall through to 500.
            cfg.routes.exception(com.google.gson.JsonParseException.class, (e, ctx) ->
                    ctx.status(400).result("malformed JSON body"));
            cfg.routes.exception(NumberFormatException.class, (e, ctx) ->
                    ctx.status(400).result("invalid number"));
            cfg.routes.exception(IllegalArgumentException.class, (e, ctx) ->
                    ctx.status(400).result(e.getMessage() == null ? "bad request" : e.getMessage()));

            registerRoutes(cfg.routes);
            registerWebSockets(cfg.routes);
        });

        app.start(config.dashboard().getHostString(), config.dashboard().getPort());
        LOGGER.info("Dashboard listening on http://{} ({} mode)",
                config.dashboard(), config.replayMode() ? "replay" : "live");
        Thread.ofVirtual().name("web-icons-warmup").start(itemIcons::warm);
        scheduler.scheduleAtFixedRate(this::evictIdleScopes, 60, 60, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::evictDisconnectedPlayers, 60, 60, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(() -> postLimiter.sweepIdle(RATE_BUCKET_IDLE_NANOS), 5, 5, TimeUnit.MINUTES);
    }

    private void evictDisconnectedPlayers() {
        final long cutoff = System.currentTimeMillis() - DISCONNECTED_PLAYER_TTL_MS;
        for (DashboardScope scope : scopes.values()) {
            if (scope.isReplay()) continue;
            for (PlayerView player : scope.registry.players()) {
                if (!(player instanceof PlayerView.Retained retained)) continue;
                if (retained.disconnectedAt() > cutoff) continue;
                scope.registry.evict(retained);
            }
        }
    }

    private void evictIdleScopes() {
        final long now = System.currentTimeMillis();
        for (DashboardScope scope : scopes.values()) {
            if (!scope.isReplay()) continue;
            if (scope.hasSubscribers()) continue;
            if (now - scope.lastActiveAt() < REPLAY_IDLE_TTL_MS) continue;
            LOGGER.info("Evicting idle replay scope {} (inactive for >{}ms)", scope.id, REPLAY_IDLE_TTL_MS);
            removeScope(scope.id);
        }
    }

    private boolean authorized(@Nullable String provided) {
        final String token = config.token();
        if (token == null || token.isEmpty()) return true;
        return token.equals(provided);
    }

    private void checkRateLimit(Context ctx) {
        String m = ctx.method().name();
        if (!"POST".equalsIgnoreCase(m) && !"DELETE".equalsIgnoreCase(m)) return;
        // Key on client IP, never the auth token: the token gates auth, the IP gates rate. Keying
        // on the (constant) token would lump every tab/user/machine into one shared bucket.
        if (!postLimiter.tryAcquire(ctx.ip())) {
            ctx.status(429).result("rate limit");
            ctx.skipRemainingHandlers();
        }
    }

    private void checkAuth(Context ctx) {
        String provided = ctx.header("X-Auth-Token");
        if (provided == null) provided = ctx.queryParam("token");
        if (!authorized(provided)) {
            ctx.status(401).result("unauthorised");
            ctx.skipRemainingHandlers();
        }
    }

    // ---- routes -------------------------------------------------------------------------

    private void registerRoutes(RoutesConfig app) {
        ModeRoutes.register(app, config, routeCtx);
        PlayerRoutes.register(app);
        PacketRoutes.register(app);
        RoutineRoutes.register(app);
        ConsoleRoutes.register(app);
        ThrottleRoutes.register(app);
        QueryRoutes.register(app);
        InjectRoutes.register(app);
        MiscRoutes.register(app, itemIcons);
    }

    // ---- replay scope construction -----------------------------------------------------

    private DashboardScope createDashboardScope(Context ctx) throws Exception {
        final String id = UUID.randomUUID().toString();
        final String label = ctx.header("X-Replay-Label");
        final boolean respectTimestamps = replayRespectTimestamps(ctx);
        final Path tempDir = Files.createTempDirectory("replay-" + id + "-");
        final Path dbPath = tempDir.resolve("history.sqlite");
        try (InputStream in = ctx.bodyInputStream()) {
            Files.copy(in, dbPath, StandardCopyOption.REPLACE_EXISTING);
        }

        final ControlBridge control = new ControlBridge();
        final ExpressionEngine expressions = new ExpressionEngine(control);
        final QueryEngine queries = new QueryEngine(expressions);
        final SessionRegistry registry = new SessionRegistry(config.decodedPacketCacheSize(), queries);
        registry.attachActionRunner(new ActionRunner(null, expressions));
        final MetricsSampler metrics = new MetricsSampler(120);

        final ReplaySource source;
        try {
            source = new ReplaySource(dbPath, registry, respectTimestamps);
        } catch (Throwable t) {
            try { control.close(); } catch (Exception _) {}
            try { Files.deleteIfExists(dbPath); Files.deleteIfExists(tempDir); } catch (Exception _) {}
            throw t;
        }

        final String resolvedLabel = label == null || label.isBlank() ? "replay-" + id.substring(0, 8) : label.trim();
        final DashboardScope scope = DashboardScope.replay(id, resolvedLabel, registry, control,
                queries, expressions, metrics, dbPath);
        scope.replaySource = source;
        addDashboardScope(scope);

        scope.replayThread = Thread.ofVirtual().name("web-replay-" + id).start(() -> {
            scope.replayStatus = DashboardScope.ReplayStatus.RUNNING;
            scope.publishStatus();
            try {
                source.runBlocking();
                scope.replayStatus = DashboardScope.ReplayStatus.DONE;
            } catch (Throwable t) {
                scope.replayStatus = DashboardScope.ReplayStatus.ERROR;
                scope.replayError = t.toString();
                LOGGER.warn("replay scope {} failed: {}", id, t.toString());
            } finally {
                scope.replayEndedAt = System.currentTimeMillis();
                stopScopeTickers(scope.id);
                scope.publishStatus();
            }
        });
        return scope;
    }

    private static boolean replayRespectTimestamps(Context ctx) {
        String value = ctx.queryParam("respectTimestamps");
        if (value == null) value = ctx.header("X-Replay-Respect-Timestamps");
        if (value == null) return true;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "0", "false", "no", "off" -> false;
            default -> true;
        };
    }

    // ---- WebSockets ---------------------------------------------------------------------

    private void registerWebSockets(RoutesConfig app) {
        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                if (!authorized(ctx.queryParam("token"))) { ctx.closeSession(); return; }
                String replayId = ctx.queryParam("replay");
                final DashboardScope scope;
                if (replayId == null || replayId.isEmpty()) {
                    String defId = routeCtx.defaultScopeId();
                    scope = defId == null ? null : scopes.get(defId);
                } else {
                    scope = scopes.get(replayId);
                    if (scope != null) scope.touch();
                }
                if (scope == null) { ctx.closeSession(); return; }
                wsScope.put(ctx, scope);
                scope.addSubscriber(ctx);
            });
            ws.onMessage(ctx -> {
                final DashboardScope scope = wsScope.get(ctx);
                if (scope == null) return;
                final DashboardScope.Subscriber sub = scope.subscriber(ctx);
                if (sub == null) return;
                scope.touch();
                try {
                    JsonObject msg = JsonParser.parseString(ctx.message()).getAsJsonObject();
                    if (msg.has("subscribe")) {
                        msg.get("subscribe").getAsJsonArray().forEach(e -> {
                            final String topic = e.getAsString();
                            scope.subscribe(sub, topic);
                            if (Topics.SCOPE.equals(topic) && scope.isReplay()) scope.publishStatus();
                        });
                    }
                    if (msg.has("unsubscribe")) {
                        msg.get("unsubscribe").getAsJsonArray()
                                .forEach(e -> scope.unsubscribe(sub, e.getAsString()));
                    }
                } catch (Exception _) {}
            });
            ws.onClose(ctx -> {
                final DashboardScope scope = wsScope.remove(ctx);
                if (scope == null) return;
                scope.removeSubscriber(ctx);
            });
        });
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        for (DashboardScope scope : scopes.values())
            try { scope.close(); } catch (Exception _) {}
        scopes.clear();
        wsScope.clear();
        if (app != null) app.stop();
    }

    private static byte[] readResource(String path) {
        try (InputStream in = DashboardServer.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("classpath resource " + path + " not found");
            return in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("failed to read " + path, e);
        }
    }
}
