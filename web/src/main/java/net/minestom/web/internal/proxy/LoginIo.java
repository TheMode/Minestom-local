package net.minestom.web.internal.proxy;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketReading;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.registry.Registries;
import net.minestom.web.internal.codec.PacketDecoder;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Cipher;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.util.function.BiFunction;
import java.util.zip.DataFormatException;

/// Blocking, one-packet-at-a-time wire I/O for the synchronous login handshake driven by
/// [LoginPipeline]. The streaming (non-blocking) drain/encode path lives in [PacketDecoder] and
/// is shared by the proxy + replay; this is the back-and-forth request/response leg.
final class LoginIo {

    private LoginIo() {}

    static <T extends ClientPacket> T readClient(SocketChannel channel, NetworkBuffer carry,
                                                 ConnectionState state, @Nullable Cipher decrypt,
                                                 int compressionThreshold, Class<T> expected) throws IOException {
        return cast(readOneBlocking(channel, carry, state, decrypt, compressionThreshold,
                PacketVanilla.CLIENT_PACKET_PARSER, PacketVanilla::nextClientState), expected);
    }

    static <T extends ServerPacket> T readServer(SocketChannel channel, NetworkBuffer carry,
                                                 ConnectionState state, @Nullable Cipher decrypt,
                                                 int compressionThreshold, Class<T> expected) throws IOException {
        return cast(readOneBlocking(channel, carry, state, decrypt, compressionThreshold,
                PacketVanilla.SERVER_PACKET_PARSER, PacketVanilla::nextServerState), expected);
    }

    static void writeClient(SocketChannel channel, ConnectionState state, ClientPacket packet,
                            @Nullable Cipher encrypt, int compressionThreshold, Registries registries) throws IOException {
        writeOneBlocking(channel, registries, encrypt, state, packet, compressionThreshold);
    }

    static void writeServer(SocketChannel channel, ConnectionState state, ServerPacket packet,
                            @Nullable Cipher encrypt, int compressionThreshold, Registries registries) throws IOException {
        writeOneBlocking(channel, registries, encrypt, state, packet, compressionThreshold);
    }

    private static <T> Object readOneBlocking(SocketChannel channel, NetworkBuffer carry, ConnectionState state,
                                              @Nullable Cipher decrypt, int compressionThreshold,
                                              PacketParser<T> parser,
                                              BiFunction<T, ConnectionState, ConnectionState> stateUpdater) throws IOException {
        final boolean compressed = compressionThreshold > 0;
        while (true) {
            final PacketReading.Result<T> result;
            try {
                result = PacketReading.readPacket(carry, parser, state, stateUpdater, compressed);
            } catch (DataFormatException e) {
                throw new IOException("packet decode failed", e);
            }
            switch (result) {
                case PacketReading.Result.Success<T> success -> {
                    carry.compact();
                    return success.packets().getFirst().packet();
                }
                case PacketReading.Result.Failure<T> failure -> {
                    if (failure.requiredCapacity() > PacketDecoder.MAX_BUFFER) {
                        throw new IOException("packet exceeds " + PacketDecoder.MAX_BUFFER + " bytes");
                    }
                    carry.resize(failure.requiredCapacity());
                }
                case PacketReading.Result.Empty<T> _ -> { }
            }
            final long readStart = carry.writeIndex();
            final int n = carry.readChannel(channel);
            if (n < 0) throw new EOFException("connection closed during login");
            PacketDecoder.decryptInPlace(carry, readStart, n, decrypt);
        }
    }

    private static void writeOneBlocking(SocketChannel channel, Registries registries,
                                         @Nullable Cipher encrypt, ConnectionState state,
                                         Packet packet, int compressionThreshold) throws IOException {
        final NetworkBuffer buf = PacketDecoder.newCarry(registries);
        if (!PacketDecoder.encodeFramed(buf, state, packet, compressionThreshold)) {
            throw new IOException("login packet exceeds " + PacketDecoder.MAX_BUFFER + " bytes");
        }
        PacketDecoder.encryptInPlace(buf, encrypt);
        while (buf.readableBytes() > 0) {
            if (!buf.writeChannel(channel)) Thread.yield();
        }
    }

    private static <T> T cast(Object obj, Class<T> expected) throws IOException {
        if (!expected.isInstance(obj)) {
            throw new IOException("expected " + expected.getSimpleName()
                    + " but got " + obj.getClass().getSimpleName());
        }
        return expected.cast(obj);
    }
}
