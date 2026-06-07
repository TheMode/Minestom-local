package net.minestom.web.internal.proxy;

import net.kyori.adventure.nbt.BinaryTagIO;
import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.web.Direction;
import net.minestom.web.PlayerState;
import net.minestom.web.ProxyConfig;
import net.minestom.web.internal.codec.PacketDecoder;
import net.minestom.web.internal.codec.PacketDecoder.Result;
import net.minestom.web.internal.persist.HistoryFile;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/// One VT per connection: selector + sockets + ciphers + the [Session]'s owner-thread mutations
/// and cadence ticks. Decoded packets apply to state on the same iteration they were read.
public final class ConnectionWorker implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ConnectionWorker.class);
    private static final int INITIAL_BUFFER = 64 * 1024;
    private static final long SELECT_TIMEOUT_MS = 50L;
    private static final int CHECKPOINT_EVERY_PACKETS = 250;
    /// Bound on packets queued for injection toward this connection from off-worker threads.
    private static final int INJECT_QUEUE_CAPACITY = 256;

    private final SessionRegistry registry;
    private final Session session;
    private final SocketChannel clientChannel;
    private final SocketChannel upstreamChannel;
    private final ArrayBlockingQueue<Runnable> tasks;
    private final Selector selector;
    private final ThrottleManager throttles;
    private final ThrottleManager.WorkerState cbThrottle = new ThrottleManager.WorkerState();
    private final ThrottleManager.WorkerState sbThrottle = new ThrottleManager.WorkerState();
    /// Frames deferred (throttled) but not yet written, per direction. While > 0, a same-direction
    /// frame must NOT be encrypted+written inline — that would advance the stateful AES-CFB8 cipher
    /// ahead of the still-pending frames and desync the receiver. See [#encryptAndDispatch].
    private final AtomicInteger cbPendingDeferred = new AtomicInteger();
    private final AtomicInteger sbPendingDeferred = new AtomicInteger();
    private final @Nullable PersistentHistory persistence;
    private final ProxyMetrics.Live metrics;
    private long ioSeq;
    private final String dataChannel;

    private volatile @Nullable PacketDecoder.EncryptionContext clientCipher;
    private volatile @Nullable PacketDecoder.EncryptionContext upstreamCipher;

    private final NetworkBuffer clientReadBuffer;
    private final NetworkBuffer upstreamReadBuffer;
    private final NetworkBuffer writeBuffer;

    public ConnectionWorker(SessionRegistry registry, Session session,
                            SocketChannel clientChannel, SocketChannel upstreamChannel,
                            ProxyConfig config, ThrottleManager throttles,
                            @Nullable PersistentHistory persistence, ProxyMetrics.Live metrics,
                            @Nullable byte[] initialClientBytes, @Nullable byte[] initialUpstreamBytes,
                            long initialIoSeq) throws IOException {
        this.registry = registry;
        this.session = session;
        this.clientChannel = clientChannel;
        this.upstreamChannel = upstreamChannel;
        this.tasks = new ArrayBlockingQueue<>(INJECT_QUEUE_CAPACITY);
        tuneTcp(clientChannel);
        tuneTcp(upstreamChannel);
        this.selector = Selector.open();
        clientChannel.register(selector, SelectionKey.OP_READ);
        upstreamChannel.register(selector, SelectionKey.OP_READ);
        this.throttles = throttles;
        this.persistence = persistence;
        this.metrics = metrics;
        this.dataChannel = config.dataChannel();
        this.ioSeq = initialIoSeq;
        this.clientReadBuffer = NetworkBuffer.resizableBuffer(INITIAL_BUFFER, session.registries);
        this.upstreamReadBuffer = NetworkBuffer.resizableBuffer(INITIAL_BUFFER, session.registries);
        this.writeBuffer = NetworkBuffer.resizableBuffer(INITIAL_BUFFER, session.registries);
        seedBuffer(clientReadBuffer, initialClientBytes);
        seedBuffer(upstreamReadBuffer, initialUpstreamBytes);
    }

    public void installClientCipher(PacketDecoder.EncryptionContext ctx) {
        if (clientCipher != null) throw new IllegalStateException("client cipher already installed");
        clientCipher = ctx;
    }

    public void installUpstreamCipher(PacketDecoder.EncryptionContext ctx) {
        if (upstreamCipher != null) throw new IllegalStateException("upstream cipher already installed");
        upstreamCipher = ctx;
    }

    public boolean inject(Direction direction, Packet packet) {
        // Honest contract: a closed/closing worker (run() already past its isOpen() loop) would
        // never drain the task, so report the drop rather than a phantom success to movePlayer.
        if (!isOpen() || !tasks.offer(() -> writeInjected(direction, packet))) {
            metrics.injectDropped().increment();
            return false;
        }
        selector.wakeup();
        return true;
    }

    public boolean isOpen() {
        return session.isOpen();
    }

    public boolean close() {
        try {
            selector.close();
        } catch (IOException _) {
        }
        TcpAcceptor.closeQuiet(clientChannel);
        TcpAcceptor.closeQuiet(upstreamChannel);
        return session.close();
    }

    @Override
    public void run() {
        session.bindOwner();
        try {
            // Drain pre-queued synthetic-login mutations before touching the wire.
            session.drainMailbox();
            while (isOpen()) {
                drainTasks();
                session.drainMailbox();
                if (!drainBuffered()) break;
                try {
                    if (selector.select(SELECT_TIMEOUT_MS) > 0 && !handleReady()) break;
                } catch (IOException _) {
                    break;
                }
                session.tickCadence(System.currentTimeMillis());
            }
        } catch (Throwable t) {
            LOGGER.debug("connection {} terminated: {}", session.id, t);
        } finally {
            close();
        }
    }

    private void drainTasks() {
        for (Runnable t; (t = tasks.poll()) != null; ) {
            try {
                t.run();
            } catch (Throwable th) {
                LOGGER.warn("task failed on {}: {}", session.id, th);
            }
        }
    }

    private boolean drainBuffered() {
        if (clientReadBuffer.readableBytes() > 0) {
            if (!decodeAvailable(Direction.SERVERBOUND, upstreamChannel, clientReadBuffer)) return false;
            clientReadBuffer.compact();
        }
        if (upstreamReadBuffer.readableBytes() > 0) {
            if (!decodeAvailable(Direction.CLIENTBOUND, clientChannel, upstreamReadBuffer)) return false;
            upstreamReadBuffer.compact();
        }
        return true;
    }

    private boolean handleReady() {
        for (var it = selector.selectedKeys().iterator(); it.hasNext(); ) {
            final var key = it.next();
            it.remove();
            if (!key.isValid() || !key.isReadable()) continue;
            final boolean fromClient = key.channel() == clientChannel;
            if (!pumpSide(
                    fromClient ? clientChannel : upstreamChannel,
                    fromClient ? upstreamChannel : clientChannel,
                    fromClient ? Direction.SERVERBOUND : Direction.CLIENTBOUND,
                    fromClient ? clientReadBuffer : upstreamReadBuffer)) {
                return false;
            }
        }
        return true;
    }

    private boolean pumpSide(SocketChannel source, SocketChannel sink, Direction direction, NetworkBuffer readBuffer) {
        final long readStart = readBuffer.writeIndex();
        final int n;
        try {
            n = readBuffer.readChannel(source);
        } catch (IOException _) {
            return false;
        }
        if (n < 0) return false;
        if (n > 0) {
            var cipher = readCipher(direction);
            PacketDecoder.decryptInPlace(readBuffer, readStart, n, cipher == null ? null : cipher.decrypt());
            if (direction == Direction.SERVERBOUND) {
                session.playerForOwnerThread().traffic.bytesIn += n;
            }
        }
        if (!decodeAvailable(direction, sink, readBuffer)) return false;
        readBuffer.compact();
        return true;
    }

    private void maybeCheckpoint(long ioEventSeq) {
        if (persistence == null) return;
        final long pktSeq = session.packets.latestSeq();
        if (pktSeq <= 0 || pktSeq % CHECKPOINT_EVERY_PACKETS != 0) return;
        final int compression = session.upstreamCompressionThreshold > 0
                ? session.upstreamCompressionThreshold : session.clientCompressionThreshold;
        persistence.recordCheckpoint(session.id, pktSeq, ioEventSeq,
                session.clientToServerState, session.serverToClientState, compression);
    }

    private boolean decodeAvailable(Direction direction, SocketChannel sink, NetworkBuffer readBuffer) {
        while (true) switch (PacketDecoder.drain(session, direction, readBuffer, persistence != null)) {
            case Result.Incomplete _ -> {
                return true;
            }
            case Result.Error _ -> {
                metrics.decodeErrors().increment();
                return false;
            }
            case Result.Frame(var wire, var packet, var beforeState, var _, var size) -> {
                final long packetIoSeq;
                if (persistence != null) {
                    persistence.recordIo(session.id, ++ioSeq, HistoryFile.nowMs(), direction, wire);
                    packetIoSeq = ioSeq;
                } else {
                    packetIoSeq = 0;
                }
                registry.applier().apply(session, direction, beforeState, packet, size, packetIoSeq);
                maybeCheckpoint(ioSeq);
                if (!shouldForward(packet)) continue;
                if (!encodeAndFlush(direction, sink, packet, beforeState)) return false;
            }
        }
    }

    private boolean encodeAndFlush(Direction direction, SocketChannel sink, Packet packet, ConnectionState beforeState) {
        final int frameBytes = encodeIntoWriteBuffer(packet, beforeState, direction);
        if (frameBytes < 0) {
            LOGGER.warn("re-encode dropped (session {}, {})", session.id, packet.getClass().getSimpleName());
            return true;
        }
        return encryptAndDispatch(direction, sink, frameBytes);
    }

    private int encodeIntoWriteBuffer(Packet packet, ConnectionState beforeState, Direction direction) {
        writeBuffer.writeIndex(0);
        writeBuffer.readIndex(0);
        if (!PacketDecoder.encodeFramed(writeBuffer, beforeState, packet, threshold(direction))) {
            metrics.encodeDrops().increment();
            return -1;
        }
        return (int) writeBuffer.writeIndex();
    }

    private boolean encryptAndDispatch(Direction direction, SocketChannel sink, int frameBytes) {
        final var throttle = direction == Direction.CLIENTBOUND ? cbThrottle : sbThrottle;
        final long delayNanos = throttles.delayFor(throttle, session.playerUuid(), direction, frameBytes);
        final AtomicInteger pending = pendingDeferred(direction);
        // AES-CFB8 is stateful — frames on a direction must be encrypted in submission order.
        // Encrypt+write inline ONLY when nothing is delayed AND no earlier frame on this direction
        // is still pending. Otherwise defer: encrypting inline now would advance the cipher ahead
        // of the pending frame(s) and corrupt the receiver's decrypt stream. The shared
        // single-threaded DELAY executor then releases all deferred frames in submission order
        // (frames scheduled for the same instant run in submission order), so this invariant holds
        // locally regardless of ThrottleManager.delayFor's internal scheduling.
        if (delayNanos == 0L && pending.get() == 0) {
            var cipher = writeCipher(direction);
            PacketDecoder.encryptInPlace(writeBuffer, cipher == null ? null : cipher.encrypt());
            return writeFully(sink, writeBuffer, direction);
        }
        final byte[] frame = new byte[frameBytes];
        writeBuffer.copyTo(0L, frame, 0L, frameBytes);
        pending.incrementAndGet();
        ThrottleManager.schedule(delayNanos, () -> {
            if (!isOpen()) { pending.decrementAndGet(); return; }
            if (tasks.offer(() -> writeDelayed(sink, frame, direction))) selector.wakeup();
            else { pending.decrementAndGet(); metrics.injectDropped().increment(); }
        });
        return true;
    }

    private boolean writeFully(SocketChannel sink, NetworkBuffer buffer, Direction direction) {
        if (buffer.readableBytes() == 0) return true;
        final long bytes = buffer.readableBytes();
        try {
            flushToSink(sink, buffer);
            if (direction == Direction.CLIENTBOUND) {
                session.playerForOwnerThread().traffic.bytesOut += bytes;
            }
            return true;
        } catch (IOException _) {
            return false;
        }
    }

    /// Blocking, level-triggered write of all of `buffer` to `sink`. When the kernel send buffer
    /// fills this parks the worker (cheap on a virtual thread) until `sink` is writable again —
    /// intentionally pausing this connection's other-direction reads, inject queue, and cadence
    /// ticks for the duration. That backpressure is per-connection only: a slow peer can stall its
    /// own session but not the server. Non-sink ready keys are dropped here but not lost — the
    /// selector is level-triggered, so they re-select on the next outer loop.
    private void flushToSink(SocketChannel sink, NetworkBuffer buffer) throws IOException {
        final var key = sink.keyFor(selector);
        if (key == null) throw new IOException("channel not registered");
        writeLoop:
        while (buffer.readableBytes() > 0) {
            if (buffer.writeChannel(sink)) continue;
            key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
            try {
                while (isOpen()) {
                    if (selector.select(SELECT_TIMEOUT_MS) == 0) continue;
                    for (var it = selector.selectedKeys().iterator(); it.hasNext(); ) {
                        final var ready = it.next();
                        it.remove();
                        if (ready.isValid() && ready.channel() == sink && ready.isWritable()) continue writeLoop;
                    }
                }
                throw new IOException("closed");
            } finally {
                key.interestOps(SelectionKey.OP_READ);
            }
        }
    }

    private void writeDelayed(SocketChannel sink, byte[] frame, Direction direction) {
        try {
            if (!isOpen()) return;
            final var buffer = NetworkBuffer.wrap(frame, 0, frame.length, session.registries);
            var cipher = writeCipher(direction);
            PacketDecoder.encryptInPlace(buffer, cipher == null ? null : cipher.encrypt());
            if (!writeFully(sink, buffer, direction)) close();
        } finally {
            pendingDeferred(direction).decrementAndGet();
        }
    }

    private void writeInjected(Direction direction, Packet packet) {
        final boolean clientbound = direction == Direction.CLIENTBOUND;
        final ConnectionState beforeState = clientbound ? session.serverToClientState : session.clientToServerState;
        final SocketChannel sink = clientbound ? clientChannel : upstreamChannel;
        final int frameBytes = encodeIntoWriteBuffer(packet, beforeState, direction);
        if (frameBytes < 0) {
            LOGGER.warn("inject encode dropped (session {}, {})", session.id, packet.getClass().getSimpleName());
            return;
        }
        long packetIoSeq = 0;
        if (persistence != null) {
            final byte[] frame = new byte[frameBytes];
            writeBuffer.copyTo(0L, frame, 0L, frameBytes);
            persistence.recordIo(session.id, ++ioSeq, HistoryFile.nowMs(), direction, frame);
            packetIoSeq = ioSeq;
        }
        registry.applier().apply(session, direction, beforeState, packet, frameBytes, packetIoSeq);
        if (!encryptAndDispatch(direction, sink, frameBytes)) {
            LOGGER.warn("inject write failed (session {})", session.id);
            close();
        }
    }

    private int threshold(Direction direction) {
        return direction == Direction.CLIENTBOUND
                ? session.clientCompressionThreshold : session.upstreamCompressionThreshold;
    }

    private @Nullable PacketDecoder.EncryptionContext readCipher(Direction direction) {
        return direction == Direction.SERVERBOUND ? clientCipher : upstreamCipher;
    }

    private @Nullable PacketDecoder.EncryptionContext writeCipher(Direction direction) {
        return direction == Direction.CLIENTBOUND ? clientCipher : upstreamCipher;
    }

    private AtomicInteger pendingDeferred(Direction direction) {
        return direction == Direction.CLIENTBOUND ? cbPendingDeferred : sbPendingDeferred;
    }

    private boolean shouldForward(Packet packet) {
        if (!(packet instanceof PluginMessagePacket(String channel, byte[] data)) || !dataChannel.equals(channel)) {
            return true;
        }
        final var nbt = parseNbt(data);
        final PlayerState player = session.playerForOwnerThread();
        player.serverData = nbt;
        player.serverDataUpdatedAt = System.currentTimeMillis();
        return false;
    }

    private static CompoundBinaryTag parseNbt(byte[] data) {
        try {
            return BinaryTagIO.unlimitedReader().read(new ByteArrayInputStream(data), BinaryTagIO.Compression.NONE);
        } catch (Exception _) {
            return CompoundBinaryTag.empty();
        }
    }

    private static void seedBuffer(NetworkBuffer buffer, @Nullable byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        buffer.ensureWritable(bytes.length);
        var source = NetworkBuffer.wrap(bytes, 0, bytes.length, buffer.registries());
        NetworkBuffer.copy(source, 0L, buffer, buffer.writeIndex(), bytes.length);
        buffer.advanceWrite(bytes.length);
    }

    private static void tuneTcp(SocketChannel channel) throws IOException {
        channel.configureBlocking(false);
        channel.setOption(StandardSocketOptions.TCP_NODELAY, true);
    }

}
