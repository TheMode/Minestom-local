package net.minestom.web.internal.replay;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.web.Direction;
import net.minestom.web.PacketRecord;
import net.minestom.web.internal.Uuids;
import net.minestom.web.internal.codec.PacketDecoder;
import net.minestom.web.internal.persist.HistoryFile;
import net.minestom.web.internal.session.Session;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static net.minestom.server.network.NetworkBuffer.VAR_INT;

/// Resolves a [PacketRecord] by [PacketRecord#seq] from SQLite. Replays `io_events` from the
/// nearest `packet_checkpoints` row when present; otherwise from the start.
public final class PacketSeqResolver {
    private static final int CACHE_MAX = 512;
    private static final Map<String, PacketRecord> CACHE = new LinkedHashMap<>(CACHE_MAX, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, PacketRecord> eldest) {
            return size() > CACHE_MAX;
        }
    };

    private PacketSeqResolver() {}

    public static @Nullable PacketRecord resolve(Path sqlitePath, UUID connectionId, long packetSeq)
            throws SQLException {
        if (sqlitePath == null || connectionId == null || packetSeq <= 0) return null;

        // Cache key includes mtime + size so a path reused across uploads (replay tempfile
        // recycling, JVM rerun) doesn't return stale frames from the previous file.
        final String key = cacheKey(sqlitePath, connectionId, packetSeq);
        if (key != null) {
            synchronized (CACHE) {
                final PacketRecord hit = CACHE.get(key);
                if (hit != null) return hit;
            }
        }

        final int capacity = (int) Math.clamp(packetSeq + 256, 1024, 500_000);
        try (Connection db = HistoryFile.openReadOnly(sqlitePath)) {
            final Init init = loadInit(db, connectionId);
            if (init == null) return null;

            final Checkpoint cp = loadCheckpoint(db, connectionId, packetSeq);

            final Session session = new Session(connectionId, capacity);
            final long afterIo = applyInit(session, init, cp);

            final NetworkBuffer sb = NetworkBuffer.resizableBuffer(8 * 1024, session.registries);
            final NetworkBuffer cb = NetworkBuffer.resizableBuffer(8 * 1024, session.registries);
            final String sql = afterIo > 0
                    ? "SELECT direction, payload FROM io_events WHERE connection_id = ? AND seq > ? ORDER BY seq ASC"
                    : "SELECT direction, payload FROM io_events WHERE connection_id = ? ORDER BY seq ASC";
            try (PreparedStatement ps = db.prepareStatement(sql)) {
                ps.setBytes(1, Uuids.toBytes(connectionId));
                if (afterIo > 0) ps.setLong(2, afterIo);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        final Direction dir = HistoryFile.directionFromId(rs.getInt(1));
                        final byte[] payload = rs.getBytes(2);
                        if (payload == null || payload.length == 0) continue;

                        final PacketRecord hit = feed(session, dir,
                                dir == Direction.SERVERBOUND ? sb : cb, payload, packetSeq);
                        if (hit != null) {
                            if (key != null) synchronized (CACHE) { CACHE.put(key, hit); }
                            return hit;
                        }
                        if (session.packets.latestSeq() > packetSeq) return null;
                    }
                }
            }
            final PacketRecord tail = session.packets.decoded(packetSeq);
            if (tail != null && key != null) synchronized (CACHE) { CACHE.put(key, tail); }
            return tail;
        }
    }

    private static @Nullable String cacheKey(Path path, UUID connectionId, long packetSeq) {
        try {
            return path.toAbsolutePath()
                    + "|" + Files.size(path)
                    + "|" + Files.getLastModifiedTime(path).toMillis()
                    + "|" + connectionId
                    + "|" + packetSeq;
        } catch (IOException _) {
            return null;
        }
    }

    /// @return `io_events.seq` cursor — replay rows with `seq >` this value
    private static long applyInit(Session session, Init init, @Nullable Checkpoint cp) {
        if (init.stateSb != null) session.clientToServerState = init.stateSb;
        if (init.stateCb != null) session.serverToClientState = init.stateCb;
        int compression = startsAtHandshake(init) ? -1 : init.compression;
        long afterIo = 0L;
        if (cp != null) {
            if (cp.stateSb != null) session.clientToServerState = cp.stateSb;
            if (cp.stateCb != null) session.serverToClientState = cp.stateCb;
            if (cp.compression > 0) compression = cp.compression;
            // Resume one before the checkpoint so decoding its io_event produces cp.packetSeq.
            session.packets.seedAfter(Math.max(0, cp.packetSeq() - 1));
            afterIo = Math.max(0, cp.ioEventSeq() - 1);
        }
        if (compression > 0) {
            session.clientCompressionThreshold = compression;
            session.upstreamCompressionThreshold = compression;
        }
        return afterIo;
    }

    private static boolean startsAtHandshake(Init init) {
        return init.stateSb == ConnectionState.HANDSHAKE || init.stateCb == ConnectionState.HANDSHAKE;
    }

    private static @Nullable PacketRecord feed(Session session, Direction dir, NetworkBuffer buffer,
            byte[] payload, long targetSeq) {
        buffer.write(NetworkBuffer.RAW_BYTES, payload);
        while (true) {
            final long next = session.packets.latestSeq() + 1;
            if (next != targetSeq && inPlay(session)) {
                final int skipped = skipFrame(session, dir, buffer);
                if (skipped > 0) {
                    session.packets.bumpSeq();
                    continue;
                }
                if (skipped < 0) return null;
            }
            switch (PacketDecoder.drain(session, dir, buffer)) {
                case PacketDecoder.Result.Incomplete _ -> { return null; }
                case PacketDecoder.Result.Error _ -> { return null; }
                case PacketDecoder.Result.Frame frame -> {
                    final PacketRecord rec = session.packets.recordDecoded(dir, frame.beforeState(),
                            frame.packet(), frame.sizeBytes(), 0);
                    if (rec.seq() >= targetSeq) return rec.seq() == targetSeq ? rec : null;
                }
            }
        }
    }

    private static int skipFrame(Session session, Direction dir, NetworkBuffer buffer) {
        final ConnectionState state = dir == Direction.SERVERBOUND
                ? session.clientToServerState : session.serverToClientState;
        final long mark = buffer.readIndex();
        final int packetLength;
        try {
            packetLength = buffer.read(VAR_INT);
        } catch (IndexOutOfBoundsException e) {
            return 0;
        }
        if (packetLength > PacketReading.maxPacketSize(state)) return -1;
        if (buffer.readableBytes() < packetLength) {
            buffer.readIndex(mark);
            return 0;
        }
        buffer.readIndex(buffer.readIndex() + packetLength);
        return (int) (buffer.readIndex() - mark);
    }

    private static boolean inPlay(Session session) {
        return session.clientToServerState == ConnectionState.PLAY
                && session.serverToClientState == ConnectionState.PLAY;
    }

    private static @Nullable Init loadInit(Connection db, UUID connectionId) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "SELECT init_state_sb, init_state_cb, init_compression FROM connections WHERE id = ?")) {
            ps.setBytes(1, Uuids.toBytes(connectionId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                final int sbId = rs.getInt(1);
                final boolean sbNull = rs.wasNull();
                final int cbId = rs.getInt(2);
                final boolean cbNull = rs.wasNull();
                final int compression = rs.getInt(3);
                final boolean compressionNull = rs.wasNull();
                return new Init(
                        sbNull ? null : HistoryFile.stateFromId(sbId),
                        cbNull ? null : HistoryFile.stateFromId(cbId),
                        compressionNull ? -1 : compression);
            }
        }
    }

    private static @Nullable Checkpoint loadCheckpoint(Connection db, UUID connectionId, long packetSeq)
            throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                SELECT packet_seq, io_event_seq, state_sb, state_cb, compression
                FROM packet_checkpoints
                WHERE connection_id = ? AND packet_seq <= ?
                ORDER BY packet_seq DESC
                LIMIT 1
                """)) {
            ps.setBytes(1, Uuids.toBytes(connectionId));
            ps.setLong(2, packetSeq);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                final int sbId = rs.getInt(3);
                final boolean sbNull = rs.wasNull();
                final int cbId = rs.getInt(4);
                final boolean cbNull = rs.wasNull();
                final int compression = rs.getInt(5);
                final boolean compressionNull = rs.wasNull();
                return new Checkpoint(
                        rs.getLong(1),
                        rs.getLong(2),
                        sbNull ? null : HistoryFile.stateFromId(sbId),
                        cbNull ? null : HistoryFile.stateFromId(cbId),
                        compressionNull ? -1 : compression);
            }
        }
    }

    private record Init(@Nullable ConnectionState stateSb,
                        @Nullable ConnectionState stateCb,
                        int compression) {}

    private record Checkpoint(long packetSeq, long ioEventSeq,
                              @Nullable ConnectionState stateSb,
                              @Nullable ConnectionState stateCb,
                              int compression) {}
}
