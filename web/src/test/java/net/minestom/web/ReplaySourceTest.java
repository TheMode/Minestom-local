package net.minestom.web;

import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.replay.ReplaySource;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionEvent;
import net.minestom.web.internal.session.SessionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplaySourceTest {

    /// End-to-end: write a few real wire frames into a SQLite history, then replay it through
    /// a fresh SessionRegistry and assert the decoder produced the expected packets via the
    /// observer. Verifies that the live + replay paths converge on the same shared engine.
    @Test
    void roundTripDecodesPacketsThroughObserver(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("replay.sqlite");
        final UUID sessionId = UUID.randomUUID();

        // 1) Write two known frames (handshake + login start) into the SQLite history.
        final byte[] handshakeFrame = encodeFrame(
                new ClientHandshakePacket(MC_PROTOCOL, "localhost", 25565, ClientHandshakePacket.Intent.LOGIN),
                net.minestom.server.network.ConnectionState.HANDSHAKE);
        final byte[] loginFrame = encodeFrame(
                new ClientLoginStartPacket("alice", new UUID(0, 1)),
                net.minestom.server.network.ConnectionState.LOGIN);

        try (PersistentHistory hist = new PersistentHistory(db)) {
            hist.recordConnect(sessionId, null, null, "/127.0.0.1:50000", 1_000L);
            hist.recordIo(sessionId, 1, 1_001L, Direction.SERVERBOUND, handshakeFrame);
            hist.recordIo(sessionId, 2, 1_002L, Direction.SERVERBOUND, loginFrame);
            final Path out = tmp.resolve("export.sqlite");
            hist.exportSnapshot(out);

            // Listeners fire synchronously on the session worker, so by the time runBlocking
            // returns every PacketSeen has been delivered.
            final List<String> packetClasses = new ArrayList<>();
            final SessionRegistry registry = new SessionRegistry(64);
            registry.onSessionOpen(session -> session.addListener(event -> {
                if (event instanceof SessionEvent.PacketSeen p) {
                    packetClasses.add(p.packet().getClass().getSimpleName());
                }
            }));

            try (ReplaySource replay = new ReplaySource(out, registry, true)) {
                replay.runBlocking();
            }

            assertTrue(packetClasses.contains("ClientHandshakePacket"),
                    "expected handshake to decode, got " + packetClasses);
            assertTrue(packetClasses.contains("ClientLoginStartPacket"),
                    "expected login start to decode, got " + packetClasses);
            assertEquals(2, packetClasses.size(), "exactly the two frames we wrote");
        }
    }

    private static byte[] encodeFrame(net.minestom.server.network.packet.client.ClientPacket packet,
                                      net.minestom.server.network.ConnectionState state) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(1024, null);
        PacketWriting.writeFramedPacket(buffer, state, packet, -1);
        final byte[] out = new byte[(int) buffer.writeIndex()];
        buffer.copyTo(0, out, 0, out.length);
        return out;
    }

    private static final int MC_PROTOCOL = net.minestom.server.MinecraftServer.PROTOCOL_VERSION;
}
