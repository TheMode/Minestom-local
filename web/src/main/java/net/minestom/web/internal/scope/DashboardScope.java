package net.minestom.web.internal.scope;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.javalin.websocket.WsContext;
import net.minestom.web.ControlBridge;
import net.minestom.web.Direction;
import net.minestom.web.PacketEvent;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.expression.ExpressionEngine;
import net.minestom.web.internal.http.MetricsSampler;
import net.minestom.web.internal.http.Topics;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.proxy.TcpAcceptor;
import net.minestom.web.internal.expression.QueryEngine;
import net.minestom.web.internal.replay.ReplaySource;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionRegistry;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/// One isolated dashboard "world". Live mode runs one scope owning the TCP proxy and the
/// persistence writer; replay mode creates one scope per uploaded SQLite file so each browser
/// tab sees only its own data — independent registries, independent WS subscribers, independent
/// routines.
public final class DashboardScope implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(DashboardScope.class);

    public final String id;
    public final String label;
    public final long createdAt;

    public final SessionRegistry registry;
    public final ControlBridge control;
    public final QueryEngine queries;
    public final ExpressionEngine expressions;
    public final MetricsSampler metrics;

    public final @Nullable PersistentHistory persistence;
    public final @Nullable TcpAcceptor proxy;

    public volatile @Nullable ReplaySource replaySource;
    public final @Nullable Path replaySourcePath;
    public volatile @Nullable Thread replayThread;
    public volatile ReplayStatus replayStatus = ReplayStatus.PENDING;
    public volatile @Nullable String replayError;
    /// Wall-clock ms at which the replay loop returned; 0 while still running.
    public volatile long replayEndedAt;

    private final ConcurrentHashMap<WsContext, Subscriber> subscribers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> subscriberCounts = new ConcurrentHashMap<>();
    private final AtomicLong lastActiveAt = new AtomicLong(System.currentTimeMillis());
    private final List<WebPayloads.PlayerPacketEvent> pendingPacketAggregate = new ArrayList<>();
    /// Coalesced summary rows by player UUID — bridge writes per patch, scope ticker drains.
    private final Map<UUID, WebPayloads.PlayersSummaryRow> pendingSummary = new ConcurrentHashMap<>();
    /// Latest traffic snapshot per session id, folded into per-second rates by the metrics sampler.
    private final Map<UUID, long[]> sessionTraffic = new ConcurrentHashMap<>();

    public enum ReplayStatus { PENDING, RUNNING, DONE, ERROR }

    public static DashboardScope live(String id, SessionRegistry registry, ControlBridge control,
                                      QueryEngine queries, ExpressionEngine expressions,
                                      MetricsSampler metrics,
                                      @Nullable PersistentHistory persistence, TcpAcceptor proxy) {
        return new DashboardScope(id, "live", registry, control, queries, expressions,
                metrics, persistence, proxy, null);
    }

    public static DashboardScope replay(String id, String label, SessionRegistry registry,
                                        ControlBridge control, QueryEngine queries,
                                        ExpressionEngine expressions,
                                        MetricsSampler metrics, Path replaySourcePath) {
        return new DashboardScope(id, label, registry, control, queries, expressions,
                metrics, null, null, replaySourcePath);
    }

    private DashboardScope(String id, String label,
                           SessionRegistry registry, ControlBridge control,
                           QueryEngine queries, ExpressionEngine expressions,
                           MetricsSampler metrics,
                           @Nullable PersistentHistory persistence, @Nullable TcpAcceptor proxy,
                           @Nullable Path replaySourcePath) {
        this.id = id;
        this.label = label;
        this.createdAt = System.currentTimeMillis();
        this.registry = registry;
        this.control = control;
        this.queries = queries;
        this.expressions = expressions;
        this.metrics = metrics;
        this.persistence = persistence;
        this.proxy = proxy;
        this.replaySourcePath = replaySourcePath;
    }

    public boolean isReplay() { return replaySourcePath != null; }

    /// SQLite path packets can be resolved from — replay source if uploaded, else live persistence.
    public @Nullable Path archivePath() {
        if (replaySourcePath != null) return replaySourcePath;
        return persistence == null ? null : persistence.path();
    }
    public void touch() { lastActiveAt.set(System.currentTimeMillis()); }
    public long lastActiveAt() { return lastActiveAt.get(); }

    // ---- Summary / publishing -----------------------------------------------------------

    public WebPayloads.ScopeSummary summary() {
        return new WebPayloads.ScopeSummary(
                id, label, isReplay(), createdAt, registry.players().size(),
                isReplay() ? replayStatus.name().toLowerCase() : null,
                isReplay() ? replayError : null,
                isReplay() && replayEndedAt != 0 ? replayEndedAt : null);
    }

    public void publishStatus() {
        publish(Topics.SCOPE, WebJson.encodeAsObject(WebCodecs.SCOPE_SUMMARY, summary()));
    }

    /// Forward control-bridge events (console / metrics / global) onto WS topics.
    public void wireControlSinks() {
        control.setOnConsoleLine(line -> publish(Topics.CONSOLE,
                WebJson.encodeAsObject(WebCodecs.CONSOLE_LINE, line)));
        control.setOnMetrics(m -> publish(Topics.METRICS,
                WebJson.encodeAsObject(WebCodecs.CONTROL_METRICS, m)));
        control.setOnGlobalData(data -> publish(Topics.GLOBAL,
                WebJson.encodeAsObject(WebCodecs.GLOBAL_DATA, new WebPayloads.GlobalData(data))));
    }

    /// Compute and broadcast a one-second metrics sample. Run on the scheduler.
    public void sampleMetrics() {
        try {
            final TrafficTotals t = trafficTotals();
            MetricsSampler.Sample s = metrics.tick(System.currentTimeMillis(),
                    t.bytesIn(), t.bytesOut(), t.packetsIn(), t.packetsOut(), t.connections());
            if (s != null) publish(Topics.SERVER_METRICS,
                    WebJson.encodeAsObject(WebCodecs.METRICS_SAMPLE, s));
        } catch (Throwable e) {
            LOGGER.warn("metrics sampler for {} failed", id, e);
        }
    }

    /// Read packet events for `session` from persistence / archive / in-memory ring buffer.
    public List<PacketEvent> packetEvents(Session session, long sinceSeq, int limit,
                                          @Nullable Direction dirFilter,
                                          @Nullable String classFilter,
                                          @Nullable String subjectFilter) {
        if (limit <= 0) return List.of();
        try {
            if (persistence != null) {
                return persistence.packetEvents(session.id, sinceSeq, limit, dirFilter, classFilter, subjectFilter);
            }
            final Path archive = archivePath();
            if (archive != null) {
                return PersistentHistory.readPacketEvents(archive, session.id, sinceSeq, limit,
                        dirFilter, classFilter, subjectFilter);
            }
        } catch (SQLException e) {
            LOGGER.debug("packet event read failed for {}: {}", session.id, e.toString());
        }
        return session.packets.events(sinceSeq, limit, dirFilter, classFilter, subjectFilter);
    }

    // ---- WS plumbing -------------------------------------------------------------------

    public Subscriber addSubscriber(WsContext ctx) {
        final Subscriber sub = new Subscriber(ctx);
        subscribers.put(ctx, sub);
        return sub;
    }

    public @Nullable Subscriber subscriber(WsContext ctx) {
        return subscribers.get(ctx);
    }

    public boolean hasSubscribers() {
        return !subscribers.isEmpty();
    }

    public void removeSubscriber(WsContext ctx) {
        final Subscriber sub = subscribers.remove(ctx);
        if (sub == null) return;
        sub.close();
        for (String topic : sub.topics) decrementCount(topic);
    }

    public void subscribe(Subscriber sub, String topic) {
        if (!sub.topics.add(topic)) return;
        subscriberCounts.computeIfAbsent(topic, _ -> new LongAdder()).increment();
    }

    public void unsubscribe(Subscriber sub, String topic) {
        if (!sub.topics.remove(topic)) return;
        decrementCount(topic);
    }

    public boolean hasSubscriber(String topic) {
        final LongAdder count = subscriberCounts.get(topic);
        return count != null && count.sum() > 0;
    }

    /// Fan a message out to every subscriber of `topic` in this scope. No-op when nobody is
    /// listening so the caller can avoid building the payload (also gated upstream).
    public void broadcast(String topic, JsonElement message) {
        if (!hasSubscriber(topic)) return;
        final String body = message.toString();
        for (Subscriber sub : subscribers.values()) {
            if (sub.topics.contains(topic)) sub.enqueue(body);
        }
    }

    /// Stamp `topic` onto the payload and broadcast. No-op if nobody is listening.
    public void publish(String topic, JsonObject payload) {
        if (!hasSubscriber(topic)) return;
        payload.addProperty("topic", topic);
        broadcast(topic, payload);
    }

    public void notePacketAggregate(WebPayloads.PlayerPacketEvent event) {
        if (event.uuid() == null) return;
        synchronized (pendingPacketAggregate) {
            pendingPacketAggregate.add(event);
        }
    }

    /// Flush buffered packet rows to aggregate subscribers. No-op when nobody is listening.
    public void flushPacketAggregate() {
        if (!hasSubscriber(Topics.PACKETS_AGGREGATE)) return;
        final List<WebPayloads.PlayerPacketEvent> rows;
        synchronized (pendingPacketAggregate) {
            if (pendingPacketAggregate.isEmpty()) return;
            rows = List.copyOf(pendingPacketAggregate);
            pendingPacketAggregate.clear();
        }
        publish(Topics.PACKETS_AGGREGATE,
                WebJson.encodeAsObject(WebCodecs.PACKETS_AGGREGATE, new WebPayloads.PacketsAggregate(rows)));
    }

    public void notePlayerSummary(WebPayloads.PlayersSummaryRow row) {
        pendingSummary.put(row.uuid(), row);
    }

    public void publishPlayersSummary() {
        if (!hasSubscriber(Topics.PLAYERS_SUMMARY) || pendingSummary.isEmpty()) return;
        final List<WebPayloads.PlayersSummaryRow> rows = new ArrayList<>(pendingSummary.values());
        pendingSummary.clear();
        publish(Topics.PLAYERS_SUMMARY,
                WebJson.encodeAsObject(WebCodecs.PLAYERS_SUMMARY, new WebPayloads.PlayersSummaryPayload(rows)));
    }

    public void recordSessionTraffic(UUID sessionId, long bytesIn, long bytesOut,
                                     long packetsIn, long packetsOut) {
        sessionTraffic.put(sessionId, new long[] { bytesIn, bytesOut, packetsIn, packetsOut });
    }

    public void forgetSessionTraffic(UUID sessionId) {
        sessionTraffic.remove(sessionId);
    }

    public TrafficTotals trafficTotals() {
        long bi = 0, bo = 0, pi = 0, po = 0;
        for (long[] t : sessionTraffic.values()) {
            bi += t[0]; bo += t[1]; pi += t[2]; po += t[3];
        }
        return new TrafficTotals(bi, bo, pi, po, sessionTraffic.size());
    }

    public record TrafficTotals(long bytesIn, long bytesOut, long packetsIn, long packetsOut,
                                int connections) {}

    private void decrementCount(String topic) {
        final LongAdder count = subscriberCounts.get(topic);
        if (count == null) return;
        count.decrement();
        if (count.sum() <= 0) subscriberCounts.remove(topic, count);
    }

    @Override
    public void close() {
        // Stop the replay driver first so it doesn't try to write into a closing registry.
        final Thread rt = replayThread;
        if (rt != null) rt.interrupt();
        final ReplaySource rs = replaySource;
        if (rs != null) try { rs.close(); } catch (Exception _) {}
        try { registry.closeAll(); } catch (Exception _) {}
        try { control.close(); } catch (Exception _) {}
        for (Subscriber sub : subscribers.values()) sub.close();
        subscribers.clear();
        subscriberCounts.clear();
        if (persistence != null) try { persistence.close(); } catch (Exception _) {}
        if (replaySourcePath != null) {
            try { Files.deleteIfExists(replaySourcePath); }
            catch (IOException e) { LOGGER.debug("failed to delete replay temp {}: {}", replaySourcePath, e.toString()); }
        }
    }

    /// Per-WS outbox carrier. Workers enqueue and return; a VT drains onto the wire so a slow
    /// client never stalls the proxy. Overflow drops the new message.
    public static final class Subscriber {
        final Set<String> topics = ConcurrentHashMap.newKeySet();
        private final WsContext ctx;
        private final ArrayBlockingQueue<String> outbox = new ArrayBlockingQueue<>(1024);
        private final Thread drainer;
        private volatile boolean alive = true;

        Subscriber(WsContext ctx) {
            this.ctx = ctx;
            this.drainer = Thread.ofVirtual().name("web-ws-out").start(this::drain);
        }

        void enqueue(String body) {
            if (alive) outbox.offer(body);
        }

        private void drain() {
            while (alive) {
                final String first;
                try { first = outbox.take(); }
                catch (InterruptedException _) { return; }
                try {
                    final java.util.ArrayList<String> batch = new java.util.ArrayList<>();
                    batch.add(first);
                    outbox.drainTo(batch, 63);
                    if (batch.size() == 1) {
                        ctx.send(batch.getFirst());
                    } else {
                        com.google.gson.JsonArray arr = new com.google.gson.JsonArray(batch.size());
                        for (String body : batch) arr.add(com.google.gson.JsonParser.parseString(body));
                        com.google.gson.JsonObject wrap = new com.google.gson.JsonObject();
                        wrap.add("batch", arr);
                        ctx.send(wrap.toString());
                    }
                } catch (Exception _) { alive = false; }
            }
        }

        void close() {
            alive = false;
            drainer.interrupt();
        }
    }
}
