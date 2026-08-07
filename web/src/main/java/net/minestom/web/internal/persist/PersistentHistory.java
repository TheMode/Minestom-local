package net.minestom.web.internal.persist;

import net.minestom.server.MinecraftServer;
import net.minestom.server.network.ConnectionState;
import net.minestom.web.Direction;
import net.minestom.web.PacketEvent;
import net.minestom.web.internal.Uuids;
import net.minestom.web.internal.http.PacketCatalog;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/// Append-only recorder for replay and packet timeline queries. Public `record…` methods enqueue
/// a typed [Op] and return; a single writer thread owns the JDBC [Connection], the
/// [PreparedStatement]s, and every batch commit (flushed per [#FLUSH_INTERVAL_NS] window or
/// [#FLUSH_THRESHOLD] ops).
///
/// **Shutdown.** [#close] enqueues [Op.Shutdown]; any [Op.Sync] still pending after the writer
/// exits is completed with the captured error so blocked [#flushSync] callers fail fast instead
/// of deadlocking.
public final class PersistentHistory implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(PersistentHistory.class);

    private static final int FLUSH_THRESHOLD = 256;
    private static final long FLUSH_INTERVAL_NS = 100_000_000L;
    private static final long SHUTDOWN_JOIN_MS = 5_000L;

    private final Path path;
    private final long sessionId;
    private final BlockingQueue<Op> queue = new LinkedBlockingQueue<>();
    private final Thread writerThread;
    private volatile boolean writerExited;
    private volatile @Nullable Throwable writerError;

    public PersistentHistory(Path path) throws SQLException, IOException {
        this(path, RunMetadata.EMPTY);
    }

    public PersistentHistory(Path path, RunMetadata metadata) throws SQLException, IOException {
        this.path = path;
        // Open + insert the session row on the calling thread so callers see a valid sessionId
        // before any record* call. After this point the writer thread takes exclusive ownership
        // of the connection.
        try (Connection bootstrap = HistoryFile.openWritable(path)) {
            this.sessionId = insertSession(bootstrap, metadata);
        }
        this.writerThread = Thread.ofVirtual()
                .name("Minestom-Web-Persist")
                .unstarted(this::runWriter);
        this.writerThread.start();
        LOGGER.info("Persistent history opened at {} (session id {}, protocol v{})",
                path, sessionId, MinecraftServer.PROTOCOL_VERSION);
    }

    private static long insertSession(Connection db, RunMetadata m) throws SQLException {
        try (PreparedStatement ps = db.prepareStatement("""
                INSERT INTO sessions(started_at_ms, bind_address, upstream_address, auth_mode, data_channel, host_info)
                VALUES(?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, HistoryFile.nowMs());
            ps.setString(2, m.bindAddress());
            ps.setString(3, m.upstreamAddress());
            ps.setString(4, m.authMode() == null ? null : m.authMode().name().toLowerCase());
            ps.setString(5, m.dataChannel());
            ps.setString(6, m.hostInfo());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                return keys.next() ? keys.getLong(1) : 1L;
            }
        }
    }

    public long sessionId() {
        return sessionId;
    }

    public Path path() {
        return path;
    }

    public int protocolVersion() {
        return MinecraftServer.PROTOCOL_VERSION;
    }

    // ---------------------------------------------------------------- producer API

    /// Open a connection row. `journeyId` / `upstreamAddress` may be null for status pings.
    public void recordConnect(UUID connectionId, @Nullable UUID journeyId,
                              @Nullable String upstreamAddress,
                              String address, long connectMs) {
        if (connectionId == null || writerExited) return;
        queue.add(new Op.OpenConnection(connectionId, sessionId, journeyId, upstreamAddress,
                address, connectMs));
    }

    /// Record a fresh player journey. The first connection on a journey calls this; subsequent
    /// transfer-stitched connections only call `recordConnect` again pointing at the same
    /// journey id.
    public void recordJourneyOpen(UUID journeyId, @Nullable UUID playerUuid, long tsMs) {
        if (journeyId == null || writerExited) return;
        queue.add(new Op.OpenJourney(journeyId, playerUuid, tsMs));
    }

    /// Backfill a player UUID onto a journey row once the upstream's LoginSuccess reveals it.
    public void recordJourneyPlayerUuid(UUID journeyId, UUID playerUuid) {
        if (journeyId == null || playerUuid == null || writerExited) return;
        queue.add(new Op.JourneyPlayerUuid(journeyId, playerUuid));
    }

    /// Stamp the post-login session state onto an already-recorded connection (online-mode only,
    /// where the login pipeline consumes handshake + LoginStart + SetCompression before the
    /// worker records). Offline-mode leaves these columns NULL and replay starts at HANDSHAKE.
    public void recordConnectInit(UUID connectionId,
                                  @Nullable ConnectionState sb, @Nullable ConnectionState cb, int compression) {
        if (connectionId == null || writerExited) return;
        queue.add(new Op.InitConnection(connectionId, sb, cb, compression));
    }

    public void recordDisconnect(UUID connectionId, long disconnectMs) {
        if (connectionId == null || writerExited) return;
        queue.add(new Op.CloseConnection(connectionId, disconnectMs));
    }

    /// Persist one wire frame. `payload` is referenced, not copied — the writer never mutates it.
    public void recordIo(UUID connectionId, long seq, long tsMs, Direction direction, byte[] payload) {
        if (connectionId == null || payload == null || payload.length == 0 || writerExited) return;
        queue.add(new Op.Io(connectionId, seq, tsMs, direction, payload));
    }

    /// Snapshot decode state after `packetSeq` was assigned (paired with the current
    /// `ioEventSeq`). Producer is [net.minestom.web.internal.proxy.ConnectionWorker], which
    /// always supplies positive seqs.
    public void recordCheckpoint(UUID connectionId, long packetSeq, long ioEventSeq,
                                 @Nullable ConnectionState stateSb, @Nullable ConnectionState stateCb,
                                 int compression) {
        if (connectionId == null || writerExited) return;
        queue.add(new Op.Checkpoint(connectionId, packetSeq, ioEventSeq, stateSb, stateCb, compression));
    }

    public void recordPacketEvent(UUID connectionId, PacketEvent event) {
        if (connectionId == null || event == null || writerExited) return;
        queue.add(new Op.PacketRow(connectionId, event));
    }

    // ---------------------------------------------------------------- reader API

    /// Read packet events for a connection from the live writer's file. Blocks on a synchronous
    /// flush so the caller sees rows enqueued up to this moment; rethrows the batch's failure
    /// if the flush rolled back.
    public List<PacketEvent> packetEvents(UUID connectionId, long sinceSeq, int limit,
                                          @Nullable Direction dirFilter,
                                          @Nullable String classFilter,
                                          @Nullable String subjectFilter) throws SQLException {
        flushSync();
        return readPacketEvents(path, connectionId, sinceSeq, limit, dirFilter, classFilter, subjectFilter);
    }

    /// Read packet events for a connection from an arbitrary archived file. Static so the
    /// dashboard can serve uploaded `sessions.sqlite` files without instantiating a writer.
    public static List<PacketEvent> readPacketEvents(Path sqlitePath, UUID connectionId, long sinceSeq, int limit,
                                                     @Nullable Direction dirFilter,
                                                     @Nullable String classFilter,
                                                     @Nullable String subjectFilter) throws SQLException {
        if (sqlitePath == null || connectionId == null || limit <= 0) return List.of();
        try (Connection db = HistoryFile.openReadOnly(sqlitePath)) {
            final StringBuilder sql = new StringBuilder("""
                    SELECT seq, ts_ms, direction, state, class_name, size_bytes, subject, io_event_seq
                    FROM packet_events
                    WHERE connection_id = ? AND seq > ?
                    """);
            if (dirFilter != null) sql.append(" AND direction = ?");
            if (classFilter != null && !classFilter.isEmpty()) sql.append(" AND lower(class_name) = lower(?)");
            if (subjectFilter != null && !subjectFilter.isEmpty()) sql.append(" AND subject = ?");
            sql.append(" ORDER BY seq ASC LIMIT ?");
            try (PreparedStatement ps = db.prepareStatement(sql.toString())) {
                int i = 1;
                ps.setBytes(i++, Uuids.toBytes(connectionId));
                ps.setLong(i++, sinceSeq);
                if (dirFilter != null) ps.setInt(i++, HistoryFile.directionId(dirFilter));
                if (classFilter != null && !classFilter.isEmpty()) ps.setString(i++, classFilter);
                if (subjectFilter != null && !subjectFilter.isEmpty()) ps.setString(i++, subjectFilter);
                ps.setInt(i, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    final List<PacketEvent> out = new ArrayList<>();
                    while (rs.next()) out.add(readPacketEvent(rs));
                    return out;
                }
            }
        }
    }

    private static PacketEvent readPacketEvent(ResultSet rs) throws SQLException {
        final long seq = rs.getLong(1);
        final long tsMs = rs.getLong(2);
        final Direction direction = HistoryFile.directionFromId(rs.getInt(3));
        final ConnectionState state = HistoryFile.stateFromId(rs.getInt(4));
        final String className = rs.getString(5);
        final int sizeBytes = rs.getInt(6);
        final String subjectId = rs.getString(7);
        final long ioEventSeq = rs.getLong(8);
        final boolean ioEventSeqNull = rs.wasNull();
        final PacketCatalog.Subject subject = PacketCatalog.subjectById(subjectId);
        return new PacketEvent(seq, tsMs, direction, state, className, sizeBytes,
                subject.id(), subject.label(), subject.groupId(),
                ioEventSeqNull ? 0 : ioEventSeq);
    }

    /// Write a self-contained snapshot to `target` (no WAL side files). Runs `VACUUM INTO` on
    /// an independent read-only connection so the writer keeps draining the proxy's recording
    /// queue while the export is in flight. On VACUUM failure the partial target is removed.
    public void exportSnapshot(Path target) throws SQLException, IOException {
        Files.createDirectories(target.getParent() == null ? Path.of(".") : target.getParent());
        Files.deleteIfExists(target);
        // Make sure every queued op is on disk before the snapshot connection reads.
        flushSync();
        // VACUUM doesn't bind a target-path parameter, so single-quote escape and inline.
        final String dstSql = target.toAbsolutePath().toString().replace("'", "''");
        try (Connection snap = HistoryFile.openReadOnly(path);
             Statement s = snap.createStatement()) {
            s.execute("VACUUM INTO '" + dstSql + "'");
        } catch (SQLException e) {
            try { Files.deleteIfExists(target); } catch (IOException _) {}
            throw e;
        }
    }

    /// Block until the writer has drained the queue up to this moment, then rethrow if the
    /// batch the barrier rode in on rolled back.
    private void flushSync() throws SQLException {
        if (writerExited) throw writerExitedException();
        final Op.Sync sync = new Op.Sync();
        queue.add(sync);
        // Race: writer could have exited (and drained the queue, marking remaining Syncs as
        // failed) between our writerExited check and the add. Re-check and self-complete to
        // avoid an indefinite await.
        if (writerExited) sync.complete(writerError);
        try {
            sync.await();
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new SQLException("interrupted waiting for flush");
        }
        final Throwable err = sync.error();
        if (err != null) {
            throw err instanceof SQLException se ? se : new SQLException(err);
        }
    }

    private SQLException writerExitedException() {
        return writerError instanceof SQLException se
                ? se
                : new SQLException("persistence writer is not running", writerError);
    }

    @Override
    public void close() {
        if (writerExited) return;
        queue.add(Op.Shutdown.INSTANCE);
        try {
            writerThread.join(SHUTDOWN_JOIN_MS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
        if (writerThread.isAlive()) {
            LOGGER.warn("persistence writer did not shut down within {} ms ({} ops still queued)",
                    SHUTDOWN_JOIN_MS, queue.size());
        }
    }

    // ---------------------------------------------------------------- writer thread

    private void runWriter() {
        try (Connection db = HistoryFile.openWritable(path);
             Writer writer = new Writer(db, sessionId)) {
            final List<Op> drain = new ArrayList<>(FLUSH_THRESHOLD);
            boolean shutdown = false;
            while (!shutdown) {
                drain.clear();
                final Op head = queue.poll(FLUSH_INTERVAL_NS, TimeUnit.NANOSECONDS);
                if (head != null) drain.add(head);
                queue.drainTo(drain, FLUSH_THRESHOLD - drain.size());
                for (Op op : drain) {
                    if (op instanceof Op.Shutdown) {
                        shutdown = true;
                        // Catch ops queued concurrently with close() so disconnect/ts updates
                        // racing the sentinel still land.
                        queue.drainTo(drain);
                        break;
                    }
                }
                writer.process(drain);
            }
        } catch (Throwable t) {
            writerError = t;
            LOGGER.error("persistence writer terminated: {}", t.toString(), t);
        } finally {
            writerExited = true;
            failPendingBarriers();
        }
    }

    /// Release every still-queued [Op.Sync] with the writer error so callers blocked on
    /// `flushSync` fail rather than deadlock.
    private void failPendingBarriers() {
        final Throwable err = writerError != null ? writerError : new SQLException("persistence writer closed");
        Op op;
        while ((op = queue.poll()) != null) {
            if (op instanceof Op.Sync sync) sync.complete(err);
        }
    }

    /// Sole owner of the [Connection] and [PreparedStatement]s once construction finishes.
    /// Batches every commit so one transaction lands per flush window or per
    /// [#FLUSH_THRESHOLD] ops.
    private static final class Writer implements AutoCloseable {
        private final Connection db;
        private final long sessionId;
        private final PreparedStatement insertConnect;
        private final PreparedStatement updateConnectInit;
        private final PreparedStatement updateDisconnect;
        private final PreparedStatement insertIo;
        private final PreparedStatement insertCheckpoint;
        private final PreparedStatement insertPacketEvent;
        private final PreparedStatement updateSessionEnd;
        private final List<PreparedStatement> batched;
        private final List<PreparedStatement> all;

        private final PreparedStatement insertJourney;
        private final PreparedStatement updateJourneyPlayer;

        Writer(Connection db, long sessionId) throws SQLException {
            this.db = db;
            this.sessionId = sessionId;
            this.insertConnect = db.prepareStatement(
                    "INSERT OR REPLACE INTO connections(id, session_id, journey_id, upstream_address, address, connect_ms, disconnect_ms) VALUES(?,?,?,?,?,?,?)");
            this.updateConnectInit = db.prepareStatement(
                    "UPDATE connections SET init_state_sb = ?, init_state_cb = ?, init_compression = ? WHERE id = ?");
            this.updateDisconnect = db.prepareStatement(
                    "UPDATE connections SET disconnect_ms = ? WHERE id = ?");
            this.insertIo = db.prepareStatement(
                    "INSERT INTO io_events(connection_id, seq, ts_ms, direction, payload) VALUES(?,?,?,?,?)");
            this.insertCheckpoint = db.prepareStatement(
                    "INSERT OR REPLACE INTO packet_checkpoints(connection_id, packet_seq, io_event_seq, state_sb, state_cb, compression) VALUES(?,?,?,?,?,?)");
            this.insertPacketEvent = db.prepareStatement("""
                    INSERT INTO packet_events(
                        connection_id, seq, ts_ms, direction, state, class_name, size_bytes, subject, io_event_seq
                    ) VALUES(?,?,?,?,?,?,?,?,?)
                    """);
            this.insertJourney = db.prepareStatement(
                    "INSERT OR IGNORE INTO player_journeys(id, player_uuid, started_at_ms) VALUES(?,?,?)");
            this.updateJourneyPlayer = db.prepareStatement(
                    "UPDATE player_journeys SET player_uuid = ? WHERE id = ?");
            this.updateSessionEnd = db.prepareStatement("UPDATE sessions SET ended_at_ms = ? WHERE id = ?");
            // FK enforcement runs per-statement, so the batch order must follow the FK graph:
            // journeys before connections (FK target — we don't enforce it on the column but
            // logical order matters for queries reading both), connections before any table
            // that references them.
            this.batched = List.of(insertJourney, updateJourneyPlayer, insertConnect,
                    updateConnectInit, updateDisconnect,
                    insertIo, insertCheckpoint, insertPacketEvent);
            this.all = List.of(insertJourney, updateJourneyPlayer, insertConnect,
                    updateConnectInit, updateDisconnect,
                    insertIo, insertCheckpoint, insertPacketEvent, updateSessionEnd);
        }

        void process(List<Op> ops) {
            if (ops.isEmpty()) return;
            Throwable batchError = null;
            if (hasBatchable(ops)) {
                try {
                    db.setAutoCommit(false);
                    for (Op op : ops) bind(op);
                    for (PreparedStatement ps : batched) ps.executeBatch();
                    db.commit();
                } catch (Throwable t) {
                    batchError = t;
                    LOGGER.warn("persistence flush failed: {}", t.toString());
                    try { db.rollback(); } catch (Throwable _) {}
                    clearBatches();
                } finally {
                    try { db.setAutoCommit(true); } catch (SQLException _) {}
                }
            }
            // Sync barriers ride alongside the data ops; complete them with the batch outcome
            // so flushSync callers can't mistake a rolled-back batch for a durable commit.
            for (Op op : ops) {
                if (op instanceof Op.Sync sync) sync.complete(batchError);
            }
        }

        private static boolean hasBatchable(List<Op> ops) {
            for (Op op : ops) {
                if (!(op instanceof Op.Sync) && !(op instanceof Op.Shutdown)) return true;
            }
            return false;
        }

        private void bind(Op op) throws SQLException {
            switch (op) {
                case Op.OpenConnection(UUID id, long sid, UUID journeyId,
                                       String upstreamAddress, String addr, long ts) -> {
                    insertConnect.setBytes(1, Uuids.toBytes(id));
                    insertConnect.setLong(2, sid);
                    if (journeyId == null) insertConnect.setNull(3, Types.BLOB);
                    else insertConnect.setBytes(3, Uuids.toBytes(journeyId));
                    if (upstreamAddress == null) insertConnect.setNull(4, Types.VARCHAR);
                    else insertConnect.setString(4, upstreamAddress);
                    insertConnect.setString(5, addr);
                    insertConnect.setLong(6, ts);
                    insertConnect.setNull(7, Types.INTEGER);
                    insertConnect.addBatch();
                }
                case Op.OpenJourney(UUID journeyId, UUID playerUuid, long ts) -> {
                    insertJourney.setBytes(1, Uuids.toBytes(journeyId));
                    if (playerUuid == null) insertJourney.setNull(2, Types.BLOB);
                    else insertJourney.setBytes(2, Uuids.toBytes(playerUuid));
                    insertJourney.setLong(3, ts);
                    insertJourney.addBatch();
                }
                case Op.JourneyPlayerUuid(UUID journeyId, UUID playerUuid) -> {
                    updateJourneyPlayer.setBytes(1, Uuids.toBytes(playerUuid));
                    updateJourneyPlayer.setBytes(2, Uuids.toBytes(journeyId));
                    updateJourneyPlayer.addBatch();
                }
                case Op.InitConnection(UUID id, var sb, var cb, int compression) -> {
                    setNullableInt(updateConnectInit, 1, HistoryFile.stateId(sb));
                    setNullableInt(updateConnectInit, 2, HistoryFile.stateId(cb));
                    setNullableInt(updateConnectInit, 3, compression > 0 ? compression : -1);
                    updateConnectInit.setBytes(4, Uuids.toBytes(id));
                    updateConnectInit.addBatch();
                }
                case Op.CloseConnection(UUID id, long ts) -> {
                    updateDisconnect.setLong(1, ts);
                    updateDisconnect.setBytes(2, Uuids.toBytes(id));
                    updateDisconnect.addBatch();
                }
                case Op.Io(UUID id, long seq, long ts, Direction dir, byte[] payload) -> {
                    insertIo.setBytes(1, Uuids.toBytes(id));
                    insertIo.setLong(2, seq);
                    insertIo.setLong(3, ts);
                    insertIo.setInt(4, HistoryFile.directionId(dir));
                    insertIo.setBytes(5, payload);
                    insertIo.addBatch();
                }
                case Op.Checkpoint(UUID id, long packetSeq, long ioEventSeq, var sb, var cb, int compression) -> {
                    insertCheckpoint.setBytes(1, Uuids.toBytes(id));
                    insertCheckpoint.setLong(2, packetSeq);
                    insertCheckpoint.setLong(3, ioEventSeq);
                    setNullableInt(insertCheckpoint, 4, HistoryFile.stateId(sb));
                    setNullableInt(insertCheckpoint, 5, HistoryFile.stateId(cb));
                    setNullableInt(insertCheckpoint, 6, compression > 0 ? compression : -1);
                    insertCheckpoint.addBatch();
                }
                case Op.PacketRow(UUID id, PacketEvent ev) -> {
                    insertPacketEvent.setBytes(1, Uuids.toBytes(id));
                    insertPacketEvent.setLong(2, ev.seq());
                    insertPacketEvent.setLong(3, ev.ts());
                    insertPacketEvent.setInt(4, HistoryFile.directionId(ev.direction()));
                    insertPacketEvent.setInt(5, HistoryFile.stateId(ev.state()));
                    insertPacketEvent.setString(6, ev.className());
                    insertPacketEvent.setInt(7, ev.sizeBytes());
                    insertPacketEvent.setString(8, ev.subject());
                    setNullableLong(insertPacketEvent, 9, ev.ioEventSeq() > 0 ? ev.ioEventSeq() : -1);
                    insertPacketEvent.addBatch();
                }
                case Op.Sync _, Op.Shutdown _ -> { /* completed in process() after commit */ }
            }
        }

        private static void setNullableInt(PreparedStatement ps, int idx, int value) throws SQLException {
            if (value < 0) ps.setNull(idx, Types.INTEGER);
            else ps.setInt(idx, value);
        }

        private static void setNullableLong(PreparedStatement ps, int idx, long value) throws SQLException {
            if (value < 0) ps.setNull(idx, Types.INTEGER);
            else ps.setLong(idx, value);
        }

        private void clearBatches() {
            for (PreparedStatement ps : batched) {
                try { ps.clearBatch(); } catch (SQLException _) {}
            }
        }

        @Override
        public void close() {
            try {
                updateSessionEnd.setLong(1, HistoryFile.nowMs());
                updateSessionEnd.setLong(2, sessionId);
                updateSessionEnd.executeUpdate();
            } catch (SQLException e) {
                LOGGER.debug("session close update failed: {}", e.toString());
            }
            for (PreparedStatement ps : all) {
                try { ps.close(); } catch (SQLException _) {}
            }
        }
    }
}
