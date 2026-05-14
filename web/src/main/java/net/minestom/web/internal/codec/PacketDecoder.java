package net.minestom.web.internal.codec;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.PacketWriting;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.registry.Registries;
import net.minestom.web.Direction;
import net.minestom.web.internal.session.Session;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;

/// Wire framing for live proxy, replay, and blocking login. Decode updates [Session] states.
public final class PacketDecoder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PacketDecoder.class);
    private static final int INITIAL_BUFFER = 8 * 1024;
    public static final int MAX_BUFFER = 8 * 1024 * 1024;

    private PacketDecoder() {}

    public record EncryptionContext(Cipher encrypt, Cipher decrypt) {}

    public sealed interface Result {
        Incomplete INCOMPLETE = new Incomplete();
        Error ERROR = new Error();

        record Incomplete() implements Result {}
        record Error() implements Result {}
        record Frame(@Nullable byte[] wireBytes, Packet packet,
                     ConnectionState beforeState, ConnectionState nextState, int sizeBytes) implements Result {}
    }

    public static Result drain(Session session, Direction direction, NetworkBuffer buffer) {
        return drain(session, direction, buffer, true);
    }

    public static Result drain(Session session, Direction direction, NetworkBuffer buffer, boolean captureWireBytes) {
        final ConnectionState beforeState = direction == Direction.SERVERBOUND
                ? session.clientToServerState : session.serverToClientState;
        final int threshold = direction == Direction.SERVERBOUND
                ? session.clientCompressionThreshold : session.upstreamCompressionThreshold;
        final long start = buffer.readIndex();
        final PacketReading.Result<? extends Packet> result;
        try {
            result = direction == Direction.SERVERBOUND
                    ? PacketReading.readPacket(buffer, PacketVanilla.CLIENT_PACKET_PARSER, beforeState,
                            PacketVanilla::nextClientState, threshold > 0)
                    : PacketReading.readPacket(buffer, PacketVanilla.SERVER_PACKET_PARSER, beforeState,
                            PacketVanilla::nextServerState, threshold > 0);
        } catch (Exception e) {
            LOGGER.warn("decode error on {}", direction, e);
            return Result.ERROR;
        }
        return switch (result) {
            case PacketReading.Result.Empty<? extends Packet> _ -> Result.INCOMPLETE;
            case PacketReading.Result.Failure<? extends Packet> failure -> {
                prepareForMoreBytes(buffer, failure.requiredCapacity());
                yield Result.INCOMPLETE;
            }
            case PacketReading.Result.Success<? extends Packet> success -> {
                final PacketReading.ParsedPacket<? extends Packet> parsed = success.packets().getFirst();
                final Packet packet = parsed.packet();
                final int sizeBytes = (int) (buffer.readIndex() - start);
                final byte[] wireBytes;
                if (captureWireBytes) {
                    wireBytes = new byte[sizeBytes];
                    buffer.copyTo(start, wireBytes, 0, sizeBytes);
                } else {
                    wireBytes = null;
                }
                advanceSessionState(session, direction, packet, parsed.nextState());
                reclaimReadHead(buffer);
                yield new Result.Frame(wireBytes, packet, beforeState, parsed.nextState(), sizeBytes);
            }
        };
    }

    public static boolean encodeFramed(NetworkBuffer buffer, ConnectionState state, Packet packet,
                                       int compressionThreshold) {
        buffer.writeIndex(0);
        buffer.readIndex(0);
        while (true) {
            try {
                writeFramed(buffer, state, packet, compressionThreshold);
                return true;
            } catch (IndexOutOfBoundsException oob) {
                if (buffer.capacity() >= MAX_BUFFER) return false;
                buffer.resize(buffer.capacity() * 2L);
                buffer.writeIndex(0);
                buffer.readIndex(0);
            }
        }
    }

    public static byte[] encodeToBytes(Registries registries, ConnectionState state, Packet packet,
                                         int compressionThreshold) {
        final NetworkBuffer buf = NetworkBuffer.resizableBuffer(INITIAL_BUFFER, registries);
        if (!encodeFramed(buf, state, packet, compressionThreshold)) {
            throw new IllegalStateException("packet exceeds " + MAX_BUFFER + " bytes");
        }
        return buf.read(NetworkBuffer.RAW_BYTES);
    }

    public static NetworkBuffer newCarry(Registries registries) {
        return NetworkBuffer.resizableBuffer(INITIAL_BUFFER, registries);
    }

    public static void decryptInPlace(NetworkBuffer buffer, long readStart, int nbytes, @Nullable Cipher decrypt) {
        if (decrypt != null && nbytes > 0) buffer.cipher(decrypt, readStart, nbytes);
    }

    public static void encryptInPlace(NetworkBuffer buffer, @Nullable Cipher encrypt) {
        if (encrypt != null) buffer.cipher(encrypt, 0L, buffer.writeIndex());
    }

    private static void writeFramed(NetworkBuffer buffer, ConnectionState state, Packet packet, int threshold) {
        switch (packet) {
            case ServerPacket sp -> PacketWriting.writeFramedPacket(buffer, state, sp, threshold);
            case ClientPacket cp -> PacketWriting.writeFramedPacket(buffer, state, cp, threshold);
        }
    }

    private static void prepareForMoreBytes(NetworkBuffer buffer, long frameBytes) {
        if (frameBytes > buffer.capacity()) buffer.resize(frameBytes);
        else reclaimReadHead(buffer);
    }

    private static void reclaimReadHead(NetworkBuffer buffer) {
        if (buffer.readIndex() > 0) buffer.compact();
    }

    private static void advanceSessionState(Session session, Direction direction,
                                            Packet packet, ConnectionState nextState) {
        if (packet instanceof ClientHandshakePacket handshake) {
            final ConnectionState target = switch (handshake.intent()) {
                case STATUS -> ConnectionState.STATUS;
                case LOGIN, TRANSFER -> ConnectionState.LOGIN;
            };
            session.clientToServerState = target;
            session.serverToClientState = target;
            return;
        }
        if (packet instanceof SetCompressionPacket(int t)) {
            session.clientCompressionThreshold = t;
            session.upstreamCompressionThreshold = t;
        }
        if (packet instanceof RegistryDataPacket registryData) {
            Registries.applyRegistryDataPacket(session.registries, registryData);
        }
        if (direction == Direction.SERVERBOUND) session.clientToServerState = nextState;
        else session.serverToClientState = nextState;
    }
}
