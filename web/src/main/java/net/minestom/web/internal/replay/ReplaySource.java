package net.minestom.web.internal.replay;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.Packet;
import net.minestom.web.Direction;
import net.minestom.web.internal.codec.PacketDecoder;
import net.minestom.web.internal.persist.HistoryFile;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/// Drives a [SessionRegistry] from a SQLite export. The file's `format.protocol_version` must
/// match the running build exactly — frames are only decodable by their original codec.
public final class ReplaySource implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplaySource.class);
    private static final long MAX_SLEEP_SLICE_NS = 100_000_000L;

    private final Connection db;
    private final SessionRegistry registry;
    private final boolean respectTimestamps;
    private final AtomicBoolean running = new AtomicBoolean();

    public ReplaySource(Path path, SessionRegistry registry, boolean respectTimestamps) throws SQLException {
        this.registry = registry;
        this.respectTimestamps = respectTimestamps;
        this.db = HistoryFile.openReadOnly(path);
    }

    /// Replay every session in the file. Blocks until the last io_event is consumed.
    public void runBlocking() throws SQLException, IOException {
        if (!running.compareAndSet(false, true)) throw new IllegalStateException("replay already running");
        try {
            final Map<UUID, ConnectionRow> connections = loadConnections();
            final Map<UUID, PerConnection> active = new HashMap<>();
            try (PreparedStatement ps = db.prepareStatement(
                    "SELECT connection_id, seq, ts_ms, direction, payload FROM io_events ORDER BY ts_ms ASC, seq ASC");
                 ResultSet rs = ps.executeQuery()) {
                long firstEventMs = Long.MIN_VALUE;
                long replayStartedNs = 0L;
                while (rs.next()) {
                    final UUID cid = HistoryFile.uuidFromBytes(rs.getBytes(1));
                    final long ioEventSeq = rs.getLong(2);
                    final long ts = rs.getLong(3);
                    final Direction dir = HistoryFile.directionFromId(rs.getInt(4));
                    final byte[] payload = rs.getBytes(5);
                    if (respectTimestamps) {
                        if (firstEventMs == Long.MIN_VALUE) {
                            firstEventMs = ts;
                            replayStartedNs = System.nanoTime();
                        } else {
                            paceReplay(firstEventMs, replayStartedNs, ts);
                        }
                    }
                    final ConnectionRow row = connections.get(cid);
                    final PerConnection pc = active.computeIfAbsent(cid, id -> {
                        final Session session = registry.openSession(cid, row == null ? null : row.address);
                        // No proxy worker in replay — spawn a default loop so the mailbox drains.
                        session.startDefaultLoop();
                        return new PerConnection(session, row);
                    });
                    pc.feed(dir, payload, ioEventSeq);
                }
            }
            for (PerConnection pc : active.values()) pc.session.close();
        } finally {
            running.set(false);
        }
    }

    /// Pace replay so an event recorded at `eventMs` (epoch ms) fires at
    /// `firstEventMs + (eventMs - firstEventMs)` wall-clock time. A clock that jumped backward
    /// during capture (rare; produces a row with `eventMs < firstEventMs`) fires immediately
    /// rather than sleeping forever.
    private static void paceReplay(long firstEventMs, long replayStartedNs, long eventMs) throws IOException {
        final long targetElapsedNs = Math.max(0L, (eventMs - firstEventMs) * 1_000_000L);
        while (true) {
            final long remaining = targetElapsedNs - (System.nanoTime() - replayStartedNs);
            if (remaining <= 0L) return;
            LockSupport.parkNanos(Math.min(remaining, MAX_SLEEP_SLICE_NS));
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("replay interrupted");
            }
        }
    }

    private Map<UUID, ConnectionRow> loadConnections() throws SQLException {
        final Map<UUID, ConnectionRow> out = new LinkedHashMap<>();
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT id, address, init_state_sb, init_state_cb, init_compression FROM connections ORDER BY connect_ms ASC");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                final UUID id = HistoryFile.uuidFromBytes(rs.getBytes(1));
                final int sbId = rs.getInt(3);
                final boolean sbNull = rs.wasNull();
                final int cbId = rs.getInt(4);
                final boolean cbNull = rs.wasNull();
                final int compression = rs.getInt(5);
                final boolean compressionWasNull = rs.wasNull();
                out.put(id, new ConnectionRow(id, rs.getString(2),
                        sbNull ? null : HistoryFile.stateFromId(sbId),
                        cbNull ? null : HistoryFile.stateFromId(cbId),
                        compressionWasNull ? -1 : compression));
            }
        }
        return out;
    }

    @Override
    public void close() {
        try { db.close(); } catch (SQLException _) {}
    }

    /// Per-session decode pump. One [NetworkBuffer] per direction — interleaved SERVERBOUND /
    /// CLIENTBOUND rows must not share a buffer or bytes bleed across parsers.
    private final class PerConnection {
        final Session session;
        final NetworkBuffer serverbound;
        final NetworkBuffer clientbound;

        PerConnection(Session session, @Nullable ConnectionRow row) {
            this.session = session;
            this.serverbound = NetworkBuffer.resizableBuffer(8 * 1024, session.registries);
            this.clientbound = NetworkBuffer.resizableBuffer(8 * 1024, session.registries);
            // Online-mode connections record their post-login state; offline-mode leaves the
            // columns NULL and we start from HANDSHAKE so the recorded handshake transitions
            // state naturally.
            if (row != null) {
                if (row.initStateSb != null) session.clientToServerState = row.initStateSb;
                if (row.initStateCb != null) session.serverToClientState = row.initStateCb;
                final boolean startsAtHandshake = row.initStateSb == ConnectionState.HANDSHAKE
                        || row.initStateCb == ConnectionState.HANDSHAKE;
                if (!startsAtHandshake && row.initCompression > 0) {
                    session.clientCompressionThreshold = row.initCompression;
                    session.upstreamCompressionThreshold = row.initCompression;
                }
            }
        }

        void feed(Direction direction, byte[] payload, long ioEventSeq) {
            final NetworkBuffer buffer = direction == Direction.SERVERBOUND ? serverbound : clientbound;
            buffer.write(NetworkBuffer.RAW_BYTES, payload);
            while (true) {
                switch (PacketDecoder.drain(session, direction, buffer)) {
                    case PacketDecoder.Result.Incomplete _ -> { return; }
                    case PacketDecoder.Result.Error _ -> {
                        LOGGER.warn("replay decode error on {} for session {}", direction, session.id);
                        return;
                    }
                    case PacketDecoder.Result.Frame frame -> {
                            final Packet packet = frame.packet();
                            session.mutateState(_ ->
                                    registry.applier().apply(session, direction, frame.beforeState(),
                                            packet, frame.sizeBytes(), ioEventSeq));
                        }
                }
            }
        }
    }

    private record ConnectionRow(UUID id, String address,
                                 @Nullable ConnectionState initStateSb,
                                 @Nullable ConnectionState initStateCb,
                                 int initCompression) {}
}
