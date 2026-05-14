package net.minestom.web.internal.proxy;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.server.common.CookieStorePacket;
import net.minestom.server.network.packet.server.common.TransferPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.web.BackendRouter;
import net.minestom.web.Direction;
import net.minestom.web.LifecycleEvent;
import net.minestom.web.ProxyConfig;
import net.minestom.web.internal.codec.PacketDecoder;
import net.minestom.web.internal.persist.HistoryFile;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionEvent;
import net.minestom.web.internal.session.SessionMessage;
import net.minestom.web.internal.session.SessionRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public final class TcpAcceptor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TcpAcceptor.class);

    private final ProxyConfig config;
    private final BackendRouter router;
    private final SessionRegistry registry;
    private final JourneyTracker journeys;
    private final ThrottleManager throttles;
    private final @Nullable PersistentHistory persistence;
    private final ProxyMetrics.Live metrics = ProxyMetrics.Live.create();
    private final Map<UUID, ConnectionWorker> workersBySession = new ConcurrentHashMap<>();
    private final Executor connectionSetup = virtualExecutor("Minestom-Web-Setup-");
    private final Executor workers = virtualExecutor("Minestom-Web-Conn-");

    private ServerSocketChannel server;
    private volatile boolean running;

    public TcpAcceptor(ProxyConfig config, BackendRouter router, SessionRegistry registry,
                       JourneyTracker journeys, @Nullable PersistentHistory persistence) {
        this.config = config;
        this.router = router;
        this.registry = registry;
        this.journeys = journeys;
        this.throttles = new ThrottleManager();
        this.persistence = persistence;
    }

    public ThrottleManager throttles() { return throttles; }
    public ProxyMetrics.Live metrics() { return metrics; }

    public boolean inject(UUID playerUuid, Direction direction, Packet packet) {
        final Session session = registry.sessionFor(playerUuid);
        final ConnectionWorker worker = session == null ? null : workersBySession.get(session.id);
        if (worker != null && worker.inject(direction, packet)) return true;
        metrics.injectRejected().increment();
        return false;
    }

    /// Move `playerUuid` to `target` by minting a transfer cookie, injecting a
    /// [CookieStorePacket] and a [TransferPacket] toward the client. The client will disconnect
    /// and reconnect with `Intent.TRANSFER`; the journey tracker recognises the cookie and the
    /// new TCP session lands on `target`.
    ///
    /// Returns `true` on a successful inject, `false` if the player isn't currently online or
    /// the inject was rejected (worker queue full / closed).
    public boolean movePlayer(UUID playerUuid, InetSocketAddress target) {
        if (playerUuid == null || target == null) return false;
        final Session session = registry.sessionFor(playerUuid);
        if (session == null || session.journeyId() == null) return false;
        final JourneyTracker.Assignment current = journeys.current(playerUuid);
        final JourneyTracker.Pending pending = journeys.mintTransfer(playerUuid, session.journeyId(),
                current == null ? null : current.address(), target);
        final InetSocketAddress reachable = config.reachableAddress();
        final boolean a = inject(playerUuid, Direction.CLIENTBOUND,
                new CookieStorePacket(JourneyTracker.COOKIE_KEY,
                        JourneyTracker.cookieBytes(pending.cookieId())));
        final boolean b = inject(playerUuid, Direction.CLIENTBOUND,
                new TransferPacket(reachable.getHostString(), reachable.getPort()));
        return a && b;
    }

    public void start() throws IOException {
        server = ServerSocketChannel.open();
        server.bind(config.bind());
        running = true;
        Thread.ofPlatform().name("Minestom-Web-Proxy-Accept").daemon(true).start(this::acceptLoop);
        LOGGER.info("Proxy listening on {} → default backend {}", config.bind(), config.defaultBackend());
        // TransferPacket must carry a host clients can actually dial — bare 0.0.0.0/:: don't
        // round-trip through a client. Embedders should set publicAddress(...) explicitly.
        if (config.publicAddress() == null && isWildcard(config.bind().getAddress())) {
            LOGGER.warn("Proxy bind is wildcard {} and no publicAddress is configured — "
                    + "movePlayer's TransferPacket will tell clients to reconnect to that "
                    + "wildcard. Set ProxyServer.Builder#publicAddress for production.",
                    config.bind());
        }
    }

    private static boolean isWildcard(java.net.InetAddress addr) {
        return addr != null && addr.isAnyLocalAddress();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final SocketChannel client = server.accept();
                metrics.connectionsAccepted().increment();
                connectionSetup.execute(() -> spawnConnection(client));
            } catch (IOException e) {
                if (running) LOGGER.warn("accept failed", e);
                break;
            }
        }
    }

    private void spawnConnection(SocketChannel client) {
        SocketAddress remote = null;
        try { remote = client.getRemoteAddress(); } catch (IOException _) {}
        // Defer firing onSessionOpen until backend/journey are stamped so subscribers (e.g.
        // ScopeSessionBridge → persistence.recordConnect) see the full routing context.
        final Session session = registry.createSession(UUID.randomUUID(),
                remote == null ? "?" : remote.toString());

        final LoginPipeline.Result login;
        try {
            login = LoginPipeline.run(client, config, router, journeys, session.registries);
        } catch (LoginPipeline.UpstreamRejected rejected) {
            LOGGER.info("upstream rejected login for {}: {}", remote, rejected.reason());
            metrics.loginFailures().increment();
            closeQuiet(client);
            session.close();
            return;
        } catch (IOException io) {
            LOGGER.warn("login failed for {}", remote, io);
            metrics.loginFailures().increment();
            closeQuiet(client);
            session.close();
            return;
        }

        try {
            stampAndRun(client, session, login);
        } catch (Throwable t) {
            LOGGER.warn("connection setup failed for {}: {}", remote, t.toString());
            closeQuiet(client);
            closeQuiet(login.upstream());
            session.close();
        }
    }

    /// Runs after a successful [LoginPipeline] — stamps routing data, fires open listeners,
    /// queues the synthetic login + (optional) SERVER_SWITCH, and submits the worker. Any
    /// throw here is caught by the caller, which closes both sockets.
    private void stampAndRun(SocketChannel client, Session session, LoginPipeline.Result login) throws IOException {
        session.setBackendAddress(login.backend().address());

        final boolean isStatus = login.handshake().intent() == ClientHandshakePacket.Intent.STATUS;
        if (!isStatus) {
            session.setJourneyId(login.consumedCookie() != null
                    ? login.consumedCookie().journeyId() : UUID.randomUUID());
        }

        // Listeners (ScopeSessionBridge.onSessionOpen → persistence.recordConnect) read the
        // stamped backendAddress + journeyId, so this must run AFTER the setters above and
        // BEFORE the SERVER_SWITCH mutate enqueue (so the lifecycle listener is registered).
        registry.notifyOpened(session);

        // Transfer reconnect: adopt the cookie's player UUID + journey + publish SERVER_SWITCH.
        // Bundle into a single Mutate so all three observations happen on the owner thread,
        // after which session.playerUuid() resolves correctly for the lifecycle listener.
        if (!isStatus && login.consumedCookie() != null) {
            final JourneyTracker.Pending cookie = login.consumedCookie();
            final InetSocketAddress toAddress = login.backend().address();
            session.send(new SessionMessage.Mutate(p -> {
                p.uuid = cookie.playerUuid();
                session.refreshPlayerUuid();
                registry.markLive(session);
                session.publish(new SessionEvent.Lifecycle(session.lifecycle.record(
                        LifecycleEvent.Kind.SERVER_SWITCH, -1,
                        serverSwitchJson(cookie.fromAddress(), toAddress))));
            }, new java.util.concurrent.CompletableFuture<>()));
        }

        final long initialIoSeq = seedSyntheticLogin(session, login);

        final ConnectionWorker worker = new ConnectionWorker(registry, session, client,
                login.upstream(), config, throttles, persistence, metrics,
                login.initialClientBytes(), login.initialUpstreamBytes(), initialIoSeq);

        if (login.clientCipher() != null) worker.installClientCipher(login.clientCipher());
        if (login.upstreamCipher() != null) worker.installUpstreamCipher(login.upstreamCipher());

        workersBySession.put(session.id, worker);
        session.onClosed(() -> workersBySession.remove(session.id));
        workers.execute(worker);
    }

    private static com.google.gson.JsonObject serverSwitchJson(@Nullable InetSocketAddress from, InetSocketAddress to) {
        final com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        if (from != null) o.addProperty("from", from.getHostString() + ":" + from.getPort());
        o.addProperty("to", to.getHostString() + ":" + to.getPort());
        return o;
    }

    /// Inject everything [LoginPipeline] consumed from the wire as synthetic packets so the
    /// session state machine, packet ring and persistence all match the on-wire reality the
    /// worker is about to resume from.
    ///
    /// Always seeds the client handshake (every login flow consumes it). For online-mode
    /// connections additionally seeds the LoginStart / EncryptionRequest / EncryptionResponse
    /// [/ SetCompression] / LoginSuccess chain; for STATUS pings and offline-mode LOGIN /
    /// TRANSFER the handshake alone is enough.
    private long seedSyntheticLogin(Session session, LoginPipeline.Result result) {
        session.clientCompressionThreshold = result.compressionThreshold();
        session.upstreamCompressionThreshold = result.compressionThreshold();

        long ioSeq = recordSynthetic(session, 0,
                new Synthetic(Direction.SERVERBOUND, ConnectionState.HANDSHAKE, result.handshake(), -1));

        final var leg = result.clientLeg();
        if (leg == null) {
            // STATUS or offline-mode LOGIN/TRANSFER — applySynthetic advanced the state via the
            // handshake's intent; nothing else was consumed by the pipeline.
            if (persistence != null) {
                persistence.recordConnectInit(session.id,
                        session.clientToServerState, session.serverToClientState, -1);
            }
            return ioSeq;
        }

        if (persistence != null) {
            persistence.recordConnectInit(session.id, ConnectionState.HANDSHAKE, ConnectionState.HANDSHAKE, -1);
        }
        final int compression = result.compressionThreshold();
        ioSeq = recordSynthetic(session, ioSeq,
                new Synthetic(Direction.SERVERBOUND, ConnectionState.LOGIN, leg.loginStart(), -1));
        ioSeq = recordSynthetic(session, ioSeq,
                new Synthetic(Direction.CLIENTBOUND, ConnectionState.LOGIN, leg.encryptionRequest(), -1));
        ioSeq = recordSynthetic(session, ioSeq,
                new Synthetic(Direction.SERVERBOUND, ConnectionState.LOGIN, leg.encryptionResponse(), -1));
        if (compression > 0) {
            ioSeq = recordSynthetic(session, ioSeq,
                    new Synthetic(Direction.CLIENTBOUND, ConnectionState.LOGIN, new SetCompressionPacket(compression), -1));
        }
        return recordSynthetic(session, ioSeq,
                new Synthetic(Direction.CLIENTBOUND, ConnectionState.LOGIN,
                        new LoginSuccessPacket(leg.playerProfile()), compression > 0 ? compression : -1));
    }

    private long recordSynthetic(Session session, long ioSeq, Synthetic s) {
        final long nextSeq = ioSeq + 1;
        applySynthetic(session, s, nextSeq);
        if (persistence != null) {
            persistence.recordIo(session.id, nextSeq, HistoryFile.nowMs(), s.direction(),
                    PacketDecoder.encodeToBytes(session.registries, s.state(), s.packet(), s.threshold()));
        }
        return nextSeq;
    }

    private void applySynthetic(Session session, Synthetic s, long ioEventSeq) {
        switch (s.packet()) {
            case ClientHandshakePacket handshake -> {
                final var target = switch (handshake.intent()) {
                    case STATUS -> ConnectionState.STATUS;
                    case LOGIN, TRANSFER -> ConnectionState.LOGIN;
                };
                session.clientToServerState = session.serverToClientState = target;
            }
            case LoginSuccessPacket _ -> session.serverToClientState = ConnectionState.CONFIGURATION;
            default -> { }
        }
        // Queued; the worker's run() drains synthetics on its first iteration before wire I/O.
        session.send(new SessionMessage.Mutate(
                _ -> registry.applier().apply(session, s.direction(), s.state(), s.packet(), 0, ioEventSeq),
                new java.util.concurrent.CompletableFuture<>()));
    }

    private record Synthetic(Direction direction, ConnectionState state, Packet packet, int threshold) {}

    private static Executor virtualExecutor(String prefix) {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(prefix, 0).factory());
    }

    static void closeQuiet(SocketChannel c) {
        try { c.close(); } catch (IOException _) {}
    }

    @Override
    public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException _) {}
        registry.closeAll();
    }
}
