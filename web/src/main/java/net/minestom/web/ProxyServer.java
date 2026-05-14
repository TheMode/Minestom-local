package net.minestom.web;

import com.google.gson.JsonObject;
import net.minestom.web.internal.AddressResolver;
import net.minestom.web.internal.expression.ExpressionEngine;
import net.minestom.web.internal.http.DashboardServer;
import net.minestom.web.internal.http.MetricsSampler;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.persist.RunMetadata;
import net.minestom.web.internal.proxy.JourneyTracker;
import net.minestom.web.internal.proxy.TcpAcceptor;
import net.minestom.web.internal.expression.QueryEngine;
import net.minestom.web.internal.session.ActionRunner;
import net.minestom.web.internal.scope.DashboardScope;
import net.minestom.web.internal.session.PlayerView;
import net.minestom.web.internal.session.SessionRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/// Public entry point for the Minestom Web Interface.
///
/// **Live mode** (default): owns a TCP proxy, a [BackendRouter], an optional persistence
/// writer, and a single "live" [DashboardScope] that the dashboard exposes. Embedders can drive
/// the [ControlBridge] returned by [#control()] to push console / metrics / global NBT, and
/// move players between backends via [TcpAcceptor#movePlayer].
///
/// **Replay mode** ([Builder#replayMode]): skips the TCP proxy entirely. No default scope is
/// created — each browser tab uploads a SQLite history via `POST /api/replay`, the dashboard
/// spins up an isolated scope for it (private registry, private WS subscribers), and replays
/// the file. Multiple uploads run concurrently without crossing data.
public final class ProxyServer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyServer.class);
    private static final String LIVE_SCOPE_ID = "live";

    static {
        // Item-icon compositing pulls in java.awt; force headless before any AWT class loads.
        System.setProperty("java.awt.headless", "true");
        // Allow per-player registries.
        System.setProperty("minestom.registry.unsafe-ops", "true");
    }

    private final ProxyConfig config;
    private final DashboardServer dashboard;
    private final @Nullable DashboardScope liveScope;
    private final @Nullable TcpAcceptor proxy;
    private final @Nullable PersistentHistory persistence;
    private final ControlBridge liveControl;

    private ProxyServer(ProxyConfig config, BackendRouter router) {
        this.config = config;
        this.dashboard = new DashboardServer(config);
        this.liveControl = new ControlBridge();
        if (config.replayMode()) {
            this.liveScope = null;
            this.proxy = null;
            this.persistence = null;
        } else {
            this.persistence = openPersistence(config);
            final ExpressionEngine expressions = new ExpressionEngine(liveControl);
            final QueryEngine queries = new QueryEngine(expressions);
            final SessionRegistry registry = new SessionRegistry(config.decodedPacketCacheSize(), queries);
            final JourneyTracker journeys = new JourneyTracker();
            registry.attachJourneyTracker(journeys);
            this.proxy = new TcpAcceptor(config, router, registry, journeys, persistence);
            registry.attachActionRunner(new ActionRunner(proxy, expressions));
            final MetricsSampler metrics = new MetricsSampler(120);
            this.liveScope = DashboardScope.live(LIVE_SCOPE_ID, registry, liveControl, queries,
                    expressions, metrics, persistence, proxy);
        }
    }

    private static @Nullable PersistentHistory openPersistence(ProxyConfig config) {
        if (config.persistencePath() == null) return null;
        final Path target = uniquePerRunPath(config.persistencePath());
        try {
            return new PersistentHistory(target, runMetadata(config));
        } catch (Exception e) {
            LOGGER.warn("persistence disabled — failed to open {}: {}", target, e.toString());
            return null;
        }
    }

    private static RunMetadata runMetadata(ProxyConfig config) {
        return new RunMetadata(
                formatAddr(config.bind()),
                formatAddr(config.defaultBackend()),
                config.mojang() != null ? RunMetadata.AuthMode.ONLINE : RunMetadata.AuthMode.OFFLINE,
                config.dataChannel(),
                RunMetadata.currentHostInfo());
    }

    private static @Nullable String formatAddr(@Nullable InetSocketAddress addr) {
        return addr == null ? null : addr.getHostString() + ":" + addr.getPort();
    }

    /// Derive `<dir>/<stem>-yyyyMMdd-HHmmss<ext>` from the configured path so each run gets its
    /// own file. Two runs starting in the same second get `-2`, `-3`, ... — defensive padding
    /// since seconds-resolution timestamps collide once in a blue moon.
    private static Path uniquePerRunPath(Path base) {
        final String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        final String name = base.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String stem = dot < 0 ? name : name.substring(0, dot);
        final String ext = dot < 0 ? "" : name.substring(dot);
        final Path dir = base.getParent() == null ? Path.of(".") : base.getParent();
        Path candidate = dir.resolve(stem + "-" + stamp + ext);
        int suffix = 2;
        while (java.nio.file.Files.exists(candidate)) {
            candidate = dir.resolve(stem + "-" + stamp + "-" + suffix + ext);
            suffix++;
        }
        return candidate;
    }

    public static Builder builder() { return new Builder(); }

    public ProxyConfig config() { return config; }

    public void start() {
        if (liveScope != null) dashboard.setLiveScope(liveScope);
        if (proxy != null) {
            try { proxy.start(); }
            catch (IOException e) { throw new RuntimeException("proxy bind failed", e); }
        }
        dashboard.start();
    }

    @Override
    public void close() {
        try { dashboard.close(); } catch (Exception _) {}
        if (proxy != null) try { proxy.close(); } catch (Exception _) {}
        // The dashboard closes the live scope (which owns persistence + routines). The shared
        // ControlBridge is owned here so embedders can hold a reference past server lifetime.
        if (liveScope == null) try { liveControl.close(); } catch (Exception _) {}
    }

    public Collection<JsonObject> players() {
        return liveScope == null ? List.of()
                : liveScope.registry.players().stream().map(PlayerView::playerJson).toList();
    }

    public Optional<JsonObject> player(UUID uuid) {
        if (liveScope == null) return Optional.empty();
        final var player = liveScope.registry.player(uuid);
        return player == null ? Optional.empty() : Optional.of(player.playerJson());
    }

    /// Live snapshot of the live-scope routines. Returns an empty collection in replay mode.
    public Collection<Routine> routines() {
        return liveScope == null ? List.of() : liveScope.registry.routines();
    }

    public Routine removeRoutine(UUID id) {
        return liveScope == null ? null : liveScope.registry.removeRoutine(id);
    }

    /// Move `playerUuid` to a different backend. Mints a transfer cookie and injects a
    /// `CookieStorePacket` + `TransferPacket` toward the client. The client disconnects and
    /// reconnects with `Intent.TRANSFER`; the proxy recognises the cookie and dials the
    /// requested address this time around.
    ///
    /// `addressSpec` accepts the same shapes as the vanilla connect dialog —
    /// `"play.example.com"` (SRV → 25565 fallback), `"play.example.com:25577"`,
    /// `"[ipv6]:25577"`. Resolved via [AddressResolver#parseMinecraft]; runs the (potentially
    /// blocking) SRV lookup on the caller's thread.
    ///
    /// Returns `false` if the proxy isn't running, the player isn't currently online, the
    /// inject was rejected, or `addressSpec` is malformed/unresolvable.
    public boolean movePlayer(UUID playerUuid, String addressSpec) {
        if (proxy == null) return false;
        final InetSocketAddress target;
        try { target = AddressResolver.parseMinecraft(addressSpec); }
        catch (IllegalArgumentException _) { return false; }
        return proxy.movePlayer(playerUuid, target);
    }

    public boolean movePlayer(UUID playerUuid, InetSocketAddress target) {
        return proxy != null && proxy.movePlayer(playerUuid, target);
    }

    /// The live scope's control bridge — push console / metrics / global NBT in via
    /// [ControlBridge#receive], and register a sink with [ControlBridge#setOnOutbound] to
    /// receive Commands / Broadcasts / Kicks / ServerData from the dashboard.
    ///
    /// In replay mode this returns an inert bridge with no sinks attached; calls discard
    /// silently so embedders don't need to null-check.
    public ControlBridge control() { return liveControl; }

    public static final class Builder {
        private InetSocketAddress bind = new InetSocketAddress("0.0.0.0", 25565);
        private @Nullable InetSocketAddress defaultBackend;
        private @Nullable InetSocketAddress publicAddress;
        private InetSocketAddress dashboard = new InetSocketAddress("127.0.0.1", 8080);
        private String token;
        private int decodedPacketCacheSize = 5000;
        private String dataChannel = ProxyConfig.DEFAULT_DATA_CHANNEL;
        private @Nullable Path persistencePath = Path.of("sessions.db");
        private @Nullable MojangAuth mojang;
        private @Nullable BackendRouter router;
        private boolean replayMode;

        public Builder bindProxy(InetSocketAddress address)     { this.bind = address; return this; }
        public Builder bindDashboard(InetSocketAddress address) { this.dashboard = Objects.requireNonNull(address); return this; }

        /// Address fresh `LOGIN` connections are routed to. Required in live mode. Players can
        /// still be moved to any other address at any time via [ProxyServer#movePlayer]; this
        /// is just the landing target.
        public Builder defaultBackend(InetSocketAddress address) {
            this.defaultBackend = Objects.requireNonNull(address);
            return this;
        }

        /// Externally-reachable proxy address. Used as the `host:port` in `TransferPacket` when
        /// moving a player — clients re-dial it on transfer. Defaults to [#bindProxy]; set this
        /// explicitly when the bind is a wildcard (`0.0.0.0` / `::`), otherwise clients can't
        /// reconnect.
        public Builder publicAddress(InetSocketAddress address) {
            this.publicAddress = address;
            return this;
        }

        public Builder token(String token)         { this.token = token; return this; }
        public Builder decodedPacketCacheSize(int n) { this.decodedPacketCacheSize = n; return this; }
        public Builder dataChannel(String channel) { this.dataChannel = channel; return this; }

        public Builder persistence(@Nullable Path path) { this.persistencePath = path; return this; }
        public Builder mojang(@Nullable MojangAuth mojang) { this.mojang = mojang; return this; }

        public Builder router(@Nullable BackendRouter router) { this.router = router; return this; }

        /// Switch the server into replay mode — no TCP proxy, no backends, no persistence
        /// writer. The dashboard accepts SQLite uploads via `POST /api/replay` and scopes each
        /// upload to the requesting browser tab.
        public Builder replayMode(boolean enabled) { this.replayMode = enabled; return this; }

        public ProxyServer build() {
            final InetSocketAddress effectiveBind = replayMode ? null : bind;
            final InetSocketAddress effectiveDefault = replayMode ? null : defaultBackend;
            final InetSocketAddress effectivePublic = replayMode ? null : publicAddress;
            final Path effectivePersistence = replayMode ? null : persistencePath;
            final MojangAuth effectiveMojang = replayMode ? null : mojang;
            final BackendRouter effectiveRouter = router != null ? router : BackendRouter.defaultRouter();
            return new ProxyServer(new ProxyConfig(
                    effectiveBind, effectiveDefault, effectivePublic, dashboard,
                    token, decodedPacketCacheSize, dataChannel,
                    effectivePersistence, effectiveMojang, replayMode), effectiveRouter);
        }
    }
}
