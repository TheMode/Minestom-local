package net.minestom.web.internal.persist;

import net.minestom.server.MinecraftServer;
import net.minestom.server.network.ConnectionState;
import net.minestom.web.Direction;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.UUID;

/// Single entry point for opening a recorded SQLite history — writer, replay reader and seq
/// resolver all route through here so pragmas, schema, and the version check stay in one place.
///
/// The single-row `format` table is stamped with the [MinecraftServer#PROTOCOL_VERSION] that
/// produced the bytes; reopening a file built by a different protocol throws, since frames are
/// only decodable by their original codec.
///
/// Schema notes: UUIDs as 16-byte BLOBs, [Direction] / [ConnectionState] as ordinal INTEGERs,
/// timestamps as epoch ms. The three hot tables are `WITHOUT ROWID` so the composite primary key
/// is the storage order.
public final class HistoryFile {
    private HistoryFile() {}

    /// Open a history file for read/write. Creates the file and schema if missing, then stamps
    /// or verifies the protocol version. The returned connection is configured with WAL,
    /// `synchronous = NORMAL`, foreign keys on, and an 8 MB page cache.
    public static Connection openWritable(Path path) throws SQLException, IOException {
        Files.createDirectories(path.getParent() == null ? Path.of(".") : path.getParent());
        final Connection db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath());
        applyPragmas(db);
        ensureSchema(db);
        stampProtocolVersion(db);
        verifyProtocolVersion(db);
        return db;
    }

    /// Open a history file for read-only consumers (replay, resolver, archived `packet_events`
    /// scans). Read-only mode skips the write-pragmas, which would otherwise fail on the file or
    /// race the live writer's WAL state.
    public static Connection openReadOnly(Path path) throws SQLException {
        final Properties props = new Properties();
        // sqlite-jdbc reads open_mode as SQLite's OPEN flags bitfield; 1 == SQLITE_OPEN_READONLY.
        props.setProperty("open_mode", "1");
        final Connection db = DriverManager.getConnection(
                "jdbc:sqlite:" + path.toAbsolutePath(), props);
        verifyProtocolVersion(db);
        return db;
    }

    private static void applyPragmas(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("PRAGMA journal_mode = WAL");
            s.execute("PRAGMA synchronous  = NORMAL");
            s.execute("PRAGMA temp_store   = MEMORY");
            s.execute("PRAGMA cache_size   = -8000"); // ~8 MB page cache
            s.execute("PRAGMA foreign_keys = ON");
        }
    }

    private static void ensureSchema(Connection db) throws SQLException {
        try (Statement s = db.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS format (
                        id               INTEGER PRIMARY KEY CHECK(id = 1),
                        protocol_version INTEGER NOT NULL,
                        created_at_ms    INTEGER NOT NULL
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS sessions (
                        id               INTEGER PRIMARY KEY AUTOINCREMENT,
                        started_at_ms    INTEGER NOT NULL,
                        ended_at_ms      INTEGER,
                        bind_address     TEXT,
                        upstream_address TEXT,
                        auth_mode        TEXT,
                        data_channel     TEXT,
                        host_info        TEXT
                    )""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS player_journeys (
                        id            BLOB NOT NULL PRIMARY KEY,
                        player_uuid   BLOB,
                        started_at_ms INTEGER NOT NULL,
                        ended_at_ms   INTEGER
                    ) WITHOUT ROWID""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_journey_player ON player_journeys(player_uuid)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS connections (
                        id               BLOB    NOT NULL PRIMARY KEY,
                        session_id       INTEGER NOT NULL REFERENCES sessions(id),
                        journey_id       BLOB,
                        upstream_address TEXT,
                        address          TEXT,
                        connect_ms       INTEGER NOT NULL,
                        disconnect_ms    INTEGER,
                        init_state_sb    INTEGER,
                        init_state_cb    INTEGER,
                        init_compression INTEGER
                    ) WITHOUT ROWID""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_conn_journey ON connections(journey_id)");
            s.execute("CREATE INDEX IF NOT EXISTS idx_conn_upstream ON connections(upstream_address)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS io_events (
                        connection_id BLOB    NOT NULL REFERENCES connections(id),
                        seq           INTEGER NOT NULL,
                        ts_ms         INTEGER NOT NULL,
                        direction     INTEGER NOT NULL,
                        payload       BLOB    NOT NULL,
                        PRIMARY KEY (connection_id, seq)
                    ) WITHOUT ROWID""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_io_conn_ts ON io_events(connection_id, ts_ms)");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS packet_checkpoints (
                        connection_id BLOB    NOT NULL REFERENCES connections(id),
                        packet_seq    INTEGER NOT NULL,
                        io_event_seq  INTEGER NOT NULL,
                        state_sb      INTEGER,
                        state_cb      INTEGER,
                        compression   INTEGER,
                        PRIMARY KEY (connection_id, packet_seq)
                    ) WITHOUT ROWID""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS packet_events (
                        connection_id BLOB    NOT NULL REFERENCES connections(id),
                        seq           INTEGER NOT NULL,
                        ts_ms         INTEGER NOT NULL,
                        direction     INTEGER NOT NULL,
                        state         INTEGER NOT NULL,
                        class_name    TEXT    NOT NULL,
                        size_bytes    INTEGER NOT NULL,
                        subject       TEXT    NOT NULL,
                        io_event_seq  INTEGER,
                        PRIMARY KEY (connection_id, seq)
                    ) WITHOUT ROWID""");
            s.execute("CREATE INDEX IF NOT EXISTS idx_pkt_events_conn_ts ON packet_events(connection_id, ts_ms)");
        }
    }

    private static void stampProtocolVersion(Connection db) throws SQLException {
        // INSERT OR IGNORE: stamps on a fresh file, no-op on an existing one (CHECK(id=1) keeps
        // the row a singleton). Mismatches are caught by verifyProtocolVersion.
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT OR IGNORE INTO format(id, protocol_version, created_at_ms) VALUES(1, ?, ?)")) {
            ps.setInt(1, MinecraftServer.PROTOCOL_VERSION);
            ps.setLong(2, nowMs());
            ps.executeUpdate();
        }
    }

    private static void verifyProtocolVersion(Connection db) throws SQLException {
        try (Statement s = db.createStatement();
             ResultSet rs = s.executeQuery("SELECT protocol_version FROM format WHERE id = 1")) {
            if (!rs.next()) {
                throw new SQLException("not a Proxy history (missing format row)");
            }
            final int found = rs.getInt(1);
            if (found != MinecraftServer.PROTOCOL_VERSION) {
                throw new SQLException("incompatible Minecraft protocol: file is v" + found
                        + ", this build speaks v" + MinecraftServer.PROTOCOL_VERSION);
            }
        }
    }

    /// Wall-clock epoch milliseconds. Used everywhere a timestamp lands on disk so cross-thread
    /// and cross-connection ordering is well-defined (in contrast to `System.nanoTime`, whose
    /// epoch is unspecified and whose values cannot be compared to wall-clock anchors). Matches
    /// the unit of in-memory [net.minestom.web.PacketEvent#ts] and the dashboard wire format —
    /// no conversion at the persist/read boundary.
    public static long nowMs() {
        return System.currentTimeMillis();
    }

    // ---------------------------------------------------------------- UUID <-> BLOB(16)

    public static byte[] uuidBytes(UUID uuid) {
        final ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    public static UUID uuidFromBytes(byte[] bytes) {
        if (bytes == null || bytes.length != 16) {
            throw new IllegalArgumentException("not a 16-byte UUID: " + (bytes == null ? "null" : bytes.length));
        }
        final ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }

    // ---------------------------------------------------------------- enum <-> ordinal

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final ConnectionState[] STATES = ConnectionState.values();

    public static int directionId(Direction direction) {
        return direction.ordinal();
    }

    public static Direction directionFromId(int id) {
        if (id < 0 || id >= DIRECTIONS.length) {
            throw new IllegalArgumentException("bad direction ordinal: " + id);
        }
        return DIRECTIONS[id];
    }

    public static int stateId(@Nullable ConnectionState state) {
        return state == null ? -1 : state.ordinal();
    }

    public static @Nullable ConnectionState stateFromId(int id) {
        if (id < 0) return null;
        if (id >= STATES.length) throw new IllegalArgumentException("bad state ordinal: " + id);
        return STATES[id];
    }
}
