package net.minestom.web;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.replay.PacketSeqResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PacketSeqResolverTest {

    private static final int MC_PROTOCOL = net.minestom.server.MinecraftServer.PROTOCOL_VERSION;

    @Test
    void resolvesPacketSeqFromSqlite(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("history.sqlite");
        final UUID connectionId = UUID.randomUUID();

        final byte[] handshakeFrame = encodeFrame(
                new ClientHandshakePacket(MC_PROTOCOL, "localhost", 25565, ClientHandshakePacket.Intent.LOGIN),
                net.minestom.server.network.ConnectionState.HANDSHAKE);
        final byte[] loginFrame = encodeFrame(
                new ClientLoginStartPacket("alice", new UUID(0, 1)),
                net.minestom.server.network.ConnectionState.LOGIN);

        try (PersistentHistory hist = new PersistentHistory(db)) {
            hist.recordConnect(connectionId, null, null, "/127.0.0.1:50000", 1_000L);
            hist.recordIo(connectionId, 1, 1_001L, Direction.SERVERBOUND, handshakeFrame);
            hist.recordIo(connectionId, 2, 1_002L, Direction.SERVERBOUND, loginFrame);
            hist.recordCheckpoint(connectionId, 2, 2,
                    ConnectionState.LOGIN, ConnectionState.LOGIN, -1);
        }

        final var second = PacketSeqResolver.resolve(db, connectionId, 2);
        assertNotNull(second);
        assertEquals("ClientLoginStartPacket", second.className());

        final var first = PacketSeqResolver.resolve(db, connectionId, 1);
        assertNotNull(first);
        assertEquals("ClientHandshakePacket", first.className());
    }

    @Test
    void ignoresConnectCompressionWhenTimelineStartsAtHandshake(@TempDir Path tmp) throws Exception {
        final Path db = tmp.resolve("history.sqlite");
        final UUID connectionId = UUID.randomUUID();
        final byte[] handshakeFrame = encodeFrame(
                new ClientHandshakePacket(MC_PROTOCOL, "localhost", 25565, ClientHandshakePacket.Intent.LOGIN),
                ConnectionState.HANDSHAKE);

        try (PersistentHistory hist = new PersistentHistory(db)) {
            hist.recordConnect(connectionId, null, null, "/127.0.0.1:50000", 1_000L);
            hist.recordConnectInit(connectionId, ConnectionState.HANDSHAKE, ConnectionState.HANDSHAKE, 256);
            hist.recordIo(connectionId, 1, 1_001L, Direction.SERVERBOUND, handshakeFrame);
        }

        final var first = PacketSeqResolver.resolve(db, connectionId, 1);
        assertNotNull(first);
        assertEquals("ClientHandshakePacket", first.className());
    }

    private static byte[] encodeFrame(net.minestom.server.network.packet.client.ClientPacket packet,
                                      net.minestom.server.network.ConnectionState state) {
        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(1024, null);
        PacketWriting.writeFramedPacket(buffer, state, packet, -1);
        final byte[] out = new byte[(int) buffer.writeIndex()];
        buffer.copyTo(0, out, 0, out.length);
        return out;
    }
}
