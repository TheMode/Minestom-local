package net.minestom.web;

import net.minestom.server.MinecraftServer;
import net.minestom.server.network.ConnectionState;
import net.minestom.web.internal.Uuids;
import net.minestom.web.internal.persist.HistoryFile;
import net.minestom.web.internal.persist.PersistentHistory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistentHistoryTest {

    @Test
    void persistsConnectsDisconnectsAndIo(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("history.db");
        final UUID conn = UUID.randomUUID();
        final byte[] frame1 = {0x10, 0x00, 0x71, 0x07, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't'};
        final byte[] frame2 = {0x01, 0x00};

        try (PersistentHistory hist = new PersistentHistory(db)) {
            hist.recordConnect(conn, null, null, "/127.0.0.1:50000", 1_000L);
            hist.recordIo(conn, 1, 1_001L, Direction.SERVERBOUND, frame1);
            hist.recordIo(conn, 2, 1_002L, Direction.CLIENTBOUND, frame2);
            hist.recordDisconnect(conn, 2_000L);

            final Path out = tmp.resolve("export.sqlite");
            hist.exportSnapshot(out);

            try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + out.toAbsolutePath());
                 Statement s = c.createStatement()) {
                ResultSet r = s.executeQuery("SELECT protocol_version FROM format WHERE id = 1");
                r.next();
                assertEquals(MinecraftServer.PROTOCOL_VERSION, r.getInt(1));

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT address, connect_ms, disconnect_ms FROM connections WHERE id = ?")) {
                    ps.setBytes(1, Uuids.toBytes(conn));
                    r = ps.executeQuery();
                    r.next();
                    assertEquals("/127.0.0.1:50000", r.getString(1));
                    assertEquals(1_000L, r.getLong(2));
                    assertEquals(2_000L, r.getLong(3));
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT seq, ts_ms, direction, payload FROM io_events WHERE connection_id = ? ORDER BY seq")) {
                    ps.setBytes(1, Uuids.toBytes(conn));
                    r = ps.executeQuery();
                    r.next();
                    assertEquals(1, r.getLong(1));
                    assertEquals(1_001L, r.getLong(2));
                    assertEquals(HistoryFile.directionId(Direction.SERVERBOUND), r.getInt(3));
                    assertArrayEquals(frame1, r.getBytes(4));
                    r.next();
                    assertEquals(2, r.getLong(1));
                    assertEquals(HistoryFile.directionId(Direction.CLIENTBOUND), r.getInt(3));
                    assertArrayEquals(frame2, r.getBytes(4));
                }
            }
        }
    }

    @Test
    void rejectsIncompatibleProtocolVersion(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("mismatched.db");
        // Hand-craft a file whose `format.protocol_version` differs from the running build.
        Files.createDirectories(db.getParent());
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE format (id INTEGER PRIMARY KEY CHECK(id = 1), protocol_version INTEGER NOT NULL, created_at_ms INTEGER NOT NULL)");
            s.execute("INSERT INTO format(id, protocol_version, created_at_ms) VALUES(1, "
                    + (MinecraftServer.PROTOCOL_VERSION + 1) + ", 0)");
        }

        Throwable thrown = assertThrows(Exception.class, () -> {
            try (var _ = new PersistentHistory(db)) { /* should fail in constructor */ }
        });
        // Walk to the SQLException root — Gradle test runner might wrap.
        Throwable root = thrown;
        while (root.getCause() != null) root = root.getCause();
        assertTrue(root.getMessage() != null && root.getMessage().contains("incompatible Minecraft protocol"),
                "expected incompatible protocol error, got: " + root.getMessage());
    }

    @Test
    void persistsPacketCheckpoints(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("cp.db");
        final UUID conn = UUID.randomUUID();

        try (PersistentHistory hist = new PersistentHistory(db)) {
            hist.recordConnect(conn, null, null, "/127.0.0.1:50000", 1_000L);
            hist.recordCheckpoint(conn, 250, 400, ConnectionState.PLAY, ConnectionState.PLAY, 256);
        }

        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var ps = c.prepareStatement(
                     "SELECT packet_seq, io_event_seq, state_sb, compression FROM packet_checkpoints WHERE connection_id = ?")) {
            ps.setBytes(1, Uuids.toBytes(conn));
            try (ResultSet r = ps.executeQuery()) {
                assertTrue(r.next());
                assertEquals(250, r.getLong(1));
                assertEquals(400, r.getLong(2));
                assertEquals(HistoryFile.stateId(ConnectionState.PLAY), r.getInt(3));
                assertEquals(256, r.getInt(4));
            }
        }
    }

    @Test
    void persistsPacketEvents(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("events.db");
        final UUID conn = UUID.randomUUID();

        try (PersistentHistory hist = new PersistentHistory(db)) {
            hist.recordConnect(conn, null, null, "/127.0.0.1:50000", 1_000L);
            hist.recordPacketEvent(conn, new PacketEvent(7, 1_234L, Direction.CLIENTBOUND,
                    ConnectionState.PLAY, "KeepAlivePacket", 5,
                    "net.io", "Network", "net", 2));
        }

        final var events = PersistentHistory.readPacketEvents(db, conn, 0, 10, null, null, null);
        assertEquals(1, events.size());
        assertEquals(7, events.getFirst().seq());
        assertEquals("KeepAlivePacket", events.getFirst().className());
        assertEquals("net.io", events.getFirst().subject());
        // subjectLabel + subjectGroup are rehydrated from PacketCatalog.subjectById, not stored.
        assertEquals("Network", events.getFirst().subjectLabel());
        assertEquals("net", events.getFirst().subjectGroup());
        assertEquals(2, events.getFirst().ioEventSeq());
    }

    @Test
    void roundTripProtocolIsStamped(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("v.db");
        try (PersistentHistory hist = new PersistentHistory(db)) {
            assertEquals(MinecraftServer.PROTOCOL_VERSION, hist.protocolVersion());
        }
        // Reopening the same file should succeed (protocol matches what we wrote).
        try (PersistentHistory reopened = new PersistentHistory(db)) {
            assertNotNull(reopened);
        }
    }
}
