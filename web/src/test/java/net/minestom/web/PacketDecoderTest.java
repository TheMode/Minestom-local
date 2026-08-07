package net.minestom.web;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.web.internal.codec.PacketDecoder;
import net.minestom.web.internal.session.Session;
import org.junit.jupiter.api.Test;

import static net.minestom.server.network.NetworkBuffer.RAW_BYTES;
import static org.junit.jupiter.api.Assertions.assertSame;

/// Regression: truncated var-int on a large replay buffer must not call resize(smaller).
class PacketDecoderTest {

    @Test
    void truncatedVarIntOnLargeBufferReturnsIncomplete() {
        final Session session = new Session(64);
        session.clientToServerState = ConnectionState.PLAY;
        session.serverToClientState = ConnectionState.PLAY;

        final NetworkBuffer buffer = NetworkBuffer.resizableBuffer(16 * 1024, session.registries);
        buffer.write(RAW_BYTES, new byte[12 * 1024]);
        buffer.readIndex(12 * 1024);
        buffer.write(RAW_BYTES, new byte[] {(byte) 0x80});

        assertSame(PacketDecoder.Result.INCOMPLETE,
                PacketDecoder.drain(session, Direction.SERVERBOUND, buffer));
    }
}
