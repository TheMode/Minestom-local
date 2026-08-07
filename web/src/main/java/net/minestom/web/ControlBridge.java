package net.minestom.web;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/// Two-way mailbox between the dashboard and the embedding game. Each direction has its own VT
/// and queue, so neither side blocks the other.
public final class ControlBridge implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ControlBridge.class);

    public static final int HISTORY_LIMIT = 500;
    private static final int OUTBOUND_CAPACITY = 1024;

    private final BlockingQueue<ControlPacket> inbound = new LinkedBlockingQueue<>();
    private final BlockingQueue<ControlPacket> outbound = new java.util.concurrent.ArrayBlockingQueue<>(OUTBOUND_CAPACITY);
    private final Thread inboundWorker;
    private final Thread outboundWorker;

    private final Deque<ControlPacket.ConsoleLine> recent = new ArrayDeque<>();
    private volatile ControlPacket.Metrics latestMetrics;
    private volatile CompoundBinaryTag globalData = CompoundBinaryTag.empty();

    private volatile Consumer<ControlPacket.ConsoleLine> onConsoleLine;
    private volatile Consumer<ControlPacket.Metrics> onMetrics;
    private volatile Consumer<CompoundBinaryTag> onGlobalData;
    private volatile Consumer<ControlPacket> onOutbound;

    public ControlBridge() {
        this.inboundWorker  = Thread.ofVirtual().name("Minestom-Web-Control-In").start(this::runInbound);
        this.outboundWorker = Thread.ofVirtual().name("Minestom-Web-Control-Out").start(this::runOutbound);
    }

    // ---- game → web --------------------------------------------------------------------

    /// Enqueue a packet from the game side. Non-blocking. The worker thread picks it up,
    /// updates caches, and fires the relevant dashboard sink.
    public void receive(ControlPacket packet) {
        inbound.offer(packet);
    }

    /// Register the single outbound sink the dashboard pushes packets through. Replaces any
    /// previous sink; pass `null` to detach.
    public void setOnOutbound(Consumer<ControlPacket> sink) {
        this.onOutbound = sink;
    }

    // ---- web → game (dashboard's send-side) --------------------------------------------

    public void send(ControlPacket packet) {
        if (!outbound.offer(packet)) {
            LOGGER.warn("control outbound queue full; dropping {}", packet.getClass().getSimpleName());
        }
    }
    public void sendCommand   (String command)                  { send(new ControlPacket.Command(command)); }
    public void sendBroadcast (Component message)               { send(new ControlPacket.Broadcast(message)); }
    public void sendKick      (java.util.UUID t, String reason) { send(new ControlPacket.Kick(t, reason)); }
    public void sendServerData(CompoundBinaryTag data)          { send(new ControlPacket.ServerData(data)); }

    // ---- dashboard inbound sinks -------------------------------------------------------

    public void setOnConsoleLine(Consumer<ControlPacket.ConsoleLine> sink) { this.onConsoleLine = sink; }
    public void setOnMetrics    (Consumer<ControlPacket.Metrics> sink)     { this.onMetrics = sink; }
    public void setOnGlobalData (Consumer<CompoundBinaryTag> sink)         { this.onGlobalData = sink; }

    // ---- cache snapshots (dashboard HTTP reads) ---------------------------------------

    /// Consistent snapshot for HTTP readers. `recent` is an [ArrayDeque] (not thread-safe), so the
    /// copy is taken under the same lock the inbound worker holds while mutating it.
    public List<ControlPacket.ConsoleLine> consoleHistory() {
        synchronized (recent) { return new ArrayList<>(recent); }
    }
    public ControlPacket.Metrics latestMetrics() { return latestMetrics; }
    public CompoundBinaryTag      globalData()    { return globalData; }

    // ---- worker ------------------------------------------------------------------------

    private void runInbound() {
        while (true) {
            final ControlPacket packet;
            try { packet = inbound.take(); }
            catch (InterruptedException _) { return; }
            try { dispatch(packet); }
            catch (Throwable t) { LOGGER.warn("control inbound dispatch failed: {}", t.toString()); }
        }
    }

    private void runOutbound() {
        while (true) {
            final ControlPacket packet;
            try { packet = outbound.take(); }
            catch (InterruptedException _) { return; }
            final Consumer<ControlPacket> sink = onOutbound;
            if (sink == null) continue;
            try { sink.accept(packet); }
            catch (Throwable t) { LOGGER.debug("outbound sink failed: {}", t.toString()); }
        }
    }

    private void dispatch(ControlPacket packet) {
        switch (packet) {
            case ControlPacket.ConsoleLine line -> {
                synchronized (recent) {
                    recent.addLast(line);
                    while (recent.size() > HISTORY_LIMIT) recent.removeFirst();
                }
                deliver(onConsoleLine, line);
            }
            case ControlPacket.Metrics m -> {
                latestMetrics = m;
                deliver(onMetrics, m);
            }
            case ControlPacket.ServerData(CompoundBinaryTag data) -> {
                globalData = data;
                deliver(onGlobalData, data);
            }
            // Web→game packets that round-trip back here through `receive(...)` (by mistake or
            // by design) are ignored — the dashboard doesn't consume them.
            case ControlPacket.Command _, ControlPacket.Broadcast _, ControlPacket.Kick _ -> {}
        }
    }

    private static <T> void deliver(Consumer<T> sink, T value) {
        if (sink == null) return;
        try { sink.accept(value); }
        catch (Throwable t) { LOGGER.debug("inbound sink failed: {}", t.toString()); }
    }

    @Override
    public void close() {
        inboundWorker.interrupt();
        outboundWorker.interrupt();
        try {
            inboundWorker.join(1_000);
            outboundWorker.join(1_000);
        } catch (InterruptedException _) { Thread.currentThread().interrupt(); }
        onConsoleLine = null;
        onMetrics = null;
        onGlobalData = null;
        onOutbound = null;
        synchronized (recent) { recent.clear(); }
        latestMetrics = null;
        globalData = CompoundBinaryTag.empty();
    }
}
