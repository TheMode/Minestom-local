package net.minestom.web.internal.session;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.registry.Registries;
import net.minestom.web.Action;
import net.minestom.web.PlayerState;
import net.minestom.web.RegisteredRoutine;
import net.minestom.web.Routine;
import net.minestom.web.StatePatch;
import net.minestom.web.internal.codec.MinimapCodec;
import net.minestom.web.internal.codec.PatchValue;
import net.minestom.web.internal.codec.WebJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;

/// Transport-agnostic session actor. Exactly one owner thread mutates [PlayerState] and ticks
/// cadence; external threads enqueue work through the mailbox and the owner drains it.
public final class Session {
    private static final Logger LOGGER = LoggerFactory.getLogger(Session.class);
    private static final int STATE_QUEUE_CAPACITY = 4096;
    private static final long POLL_TIMEOUT_MS = 50L;
    /// Long enough to ride out worst-case worker stalls; short enough that a wedged owner
    /// surfaces as a 504 rather than a hung browser tab.
    public static final long HTTP_READ_TIMEOUT_MS = 5_000L;

    @FunctionalInterface
    public interface ActionExecutor {
        void execute(Action action, PlayerState player) throws Exception;
    }

    private static final long PATCH_INTERVAL_MS = 100L;
    private static final long MINIMAP_INTERVAL_MS = 100L;
    private static final BooleanSupplier ALWAYS_ACTIVE = () -> true;

    /// Resolvers for [PlayerState#markDirty]ed paths — their serialized form is computed at
    /// drain time rather than at edit time.
    private static final Map<String, Function<PlayerState, Object>> COMPUTERS = Map.of(
            "visibleEntities", PatchValue::visibleEntities,
            "openedWindow", p -> p.openedWindow,
            "scoreboard", p -> p.scoreboard,
            "clientConnectionState", p -> String.valueOf(p.clientConnectionState),
            "serverConnectionState", p -> String.valueOf(p.serverConnectionState));

    public final UUID id;
    public final PacketTimeline packets;
    public final LifecycleHistory lifecycle = new LifecycleHistory();
    public final Registries registries = Registries.vanilla();
    public final Transcoder<JsonElement> jsonCoder = WebJson.coder(registries);
    public final long connectedAt;

    /// Listeners fire on the session's owner thread, synchronously, in registration order.
    /// Keep handlers cheap — forward to a queue, increment a counter, build a small JSON object.
    private final java.util.List<SessionListener> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public volatile ConnectionState clientToServerState = ConnectionState.HANDSHAKE;
    public volatile ConnectionState serverToClientState = ConnectionState.HANDSHAKE;
    public volatile int clientCompressionThreshold = -1;
    public volatile int upstreamCompressionThreshold = -1;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final PlayerState player = new PlayerState();
    private final ArrayBlockingQueue<StateTask<?>> stateTasks = new ArrayBlockingQueue<>(STATE_QUEUE_CAPACITY);
    private volatile Thread ownerThread;
    private volatile Thread defaultLoopThread;
    private volatile UUID playerUuid;
    private volatile long disconnectedAt;
    private volatile Runnable onClosed;
    private final AtomicBoolean stopping = new AtomicBoolean();
    /// Read off-owner during `onSessionOpen` before any worker has bound — `player.address`
    /// can't be reached from the queue at that point.
    private volatile String initialAddress;
    /// Backend assignment for this session. Set by the acceptor right after the router picks
    /// a target; immutable for the connection's lifetime (a `SERVER_SWITCH` always means a
    /// new `Session`, never a swap on this one).
    private volatile java.net.InetSocketAddress backendAddress;
    /// Journey id stitching this session to any previous sessions for the same player UUID.
    private volatile UUID journeyId;

    /// State-thread-only cadence trackers (last-fired wall-clock ms).
    private long lastPatchMs;
    private long lastMinimapMs;

    /// Cadence gates set by the host. `false` skips drainPatch / minimap raster on the next tick;
    /// `flushTrafficCounters` + [SessionEvent.TrafficSnapshot] keep firing either way.
    private volatile BooleanSupplier patchActive = ALWAYS_ACTIVE;
    private volatile BooleanSupplier minimapActive = ALWAYS_ACTIVE;

    /// State-thread-only routine evaluator state. Mutated only from the session worker.
    private List<RegisteredRoutine> routines = List.of();
    private final Map<UUID, Boolean> routineMatched = new HashMap<>();
    private final Map<UUID, Long> routineLastFired = new HashMap<>();
    private volatile ActionExecutor actionExecutor;

    public Session(int decodedPacketCacheSize) {
        this(UUID.randomUUID(), decodedPacketCacheSize);
    }

    public Session(UUID id, int decodedPacketCacheSize) {
        this.id = id;
        this.connectedAt = player.connectedAt;
        this.player.connectionId = id;
        this.packets = new PacketTimeline(decodedPacketCacheSize);
    }

    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    public int listenerCount() {
        return listeners.size();
    }

    public void publish(SessionEvent event) {
        for (SessionListener listener : listeners) {
            try { listener.onEvent(event); }
            catch (Throwable t) { LOGGER.debug("listener failed for {}: {}", id, t.toString()); }
        }
    }

    /// Direct write before any owner binds — used by the registry to seed `address` before the
    /// session is exposed.
    public void initAddress(String address) {
        if (ownerThread != null) throw new IllegalStateException("owner already bound; cannot init");
        player.address = address;
        this.initialAddress = address;
    }

    public String initialAddress() {
        return initialAddress;
    }

    public void setBackendAddress(java.net.InetSocketAddress address) {
        this.backendAddress = address;
        final String label = address == null ? null : address.getHostString() + ":" + address.getPort();
        if (ownerThread == null) player.backendAddress = label;
        else send(new SessionMessage.Mutate(p -> p.backendAddress = label, new CompletableFuture<>()));
    }

    public void setJourneyId(UUID id) {
        this.journeyId = id;
        if (ownerThread == null) player.journeyId = id;
        else send(new SessionMessage.Mutate(p -> p.journeyId = id, new CompletableFuture<>()));
    }

    public java.net.InetSocketAddress backendAddress() { return backendAddress; }
    public UUID journeyId() { return journeyId; }

    public void onClosed(Runnable callback) {
        final Runnable prev = this.onClosed;
        this.onClosed = prev == null ? callback : () -> { prev.run(); callback.run(); };
    }

    public boolean isOpen() {
        return !closed.get();
    }

    public UUID playerUuid() {
        return playerUuid;
    }

    public long disconnectedAt() {
        return disconnectedAt;
    }

    public int stateQueueDepth() {
        return stateTasks.size();
    }

    public void bindOwner() {
        final Thread current = Thread.currentThread();
        if (ownerThread == current) return;
        if (ownerThread != null) {
            throw new IllegalStateException(
                    "session " + id + " already bound to " + ownerThread.getName());
        }
        ownerThread = current;
    }

    public boolean isOwnerThread() {
        return Thread.currentThread() == ownerThread;
    }

    /// For sessions without a proxy worker (replay, tests): spawn a VT that binds as owner and
    /// just drains the mailbox + ticks cadence forever.
    public synchronized void startDefaultLoop() {
        if (ownerThread != null) return;
        defaultLoopThread = Thread.ofVirtual().name("Minestom-Web-Session-" + id).start(() -> {
            bindOwner();
            runDefaultLoop();
        });
    }

    private void runDefaultLoop() {
        while (true) {
            try {
                final StateTask<?> task = stateTasks.poll(POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (task != null) task.run(player);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
            if (stopping.get()) return;
            tickCadence(System.currentTimeMillis());
        }
    }

    public int drainMailbox() {
        assertOwnerThread();
        int n = 0;
        for (StateTask<?> task; (task = stateTasks.poll()) != null; ) {
            task.run(player);
            n++;
        }
        return n;
    }

    public <T> T readState(Function<PlayerState, T> body) {
        try {
            return callState(body::apply);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public <T> T callState(StateCall<T> body) throws Exception {
        if (isOwnerThread()) return body.apply(player);
        if (stopping.get()) {
            throw new MailboxException(MailboxException.Reason.BUSY,
                    "session worker stopped for session " + id);
        }
        final var task = new StateTask<>(body);
        enqueueStateTask(task);
        return task.get();
    }

    /// Inbox-full → [MailboxException] with [MailboxException.Reason#BUSY] (HTTP 503);
    /// worker didn't finish within `timeoutMs` → [MailboxException.Reason#TIMEOUT] (HTTP 504).
    public <T> T tryReadState(Function<PlayerState, T> body, long timeoutMs) {
        if (isOwnerThread()) return body.apply(player);
        if (stopping.get()) {
            throw new MailboxException(MailboxException.Reason.BUSY,
                    "session worker stopped for session " + id);
        }
        final var task = new StateTask<T>(body::apply);
        if (!stateTasks.offer(task)) {
            throw new MailboxException(MailboxException.Reason.BUSY,
                    "state worker queue full for session " + id);
        }
        try {
            return task.getWithin(timeoutMs);
        } catch (MailboxException e) {
            throw e;
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public void tryMutateState(Consumer<PlayerState> body, long timeoutMs) {
        tryReadState(player -> { body.accept(player); return null; }, timeoutMs);
    }

    public void mutateState(Consumer<PlayerState> body) {
        readState(player -> {
            body.accept(player);
            return null;
        });
    }

    public boolean send(SessionMessage message) {
        return enqueueMessage(message);
    }

    public void setActionExecutor(ActionExecutor executor) {
        this.actionExecutor = executor;
    }

    public void setActivityProbes(BooleanSupplier patch, BooleanSupplier minimap) {
        this.patchActive = patch == null ? ALWAYS_ACTIVE : patch;
        this.minimapActive = minimap == null ? ALWAYS_ACTIVE : minimap;
    }

    private boolean enqueueMessage(SessionMessage message) {
        return stateTasks.offer(adapt(message));
    }

    private StateTask<?> adapt(SessionMessage message) {
        return switch (message) {
            case SessionMessage.Mutate m -> new StateTask<>(p -> {
                try { m.body().accept(p); m.ack().complete(null); }
                catch (Throwable t) { m.ack().completeExceptionally(t); }
                return null;
            });
            case SessionMessage.SetRoutines set -> new StateTask<>(_ -> {
                routines = List.copyOf(set.routines());
                routineMatched.keySet().retainAll(routineIds(routines));
                routineLastFired.keySet().retainAll(routineIds(routines));
                return null;
            });
        };
    }

    private static java.util.Set<UUID> routineIds(List<RegisteredRoutine> routines) {
        final java.util.Set<UUID> ids = new java.util.HashSet<>(routines.size());
        for (RegisteredRoutine r : routines) ids.add(r.routine().id());
        return ids;
    }

    public void evaluateRoutinesOnPacket(Packet packet) {
        if (routines.isEmpty()) return;
        final long now = System.currentTimeMillis();
        for (RegisteredRoutine reg : routines) {
            if (!reg.enabled()) continue;
            final Routine r = reg.routine();
            if (!(r.trigger() instanceof Routine.Trigger.OnPacket(Class<? extends Packet> cls))) continue;
            if (!cls.isInstance(packet)) continue;
            if (!r.ql().matches(player)) continue;
            tryFire(reg, now);
        }
    }

    private void evaluateRoutinesCadence(long now) {
        if (routines.isEmpty()) return;
        for (RegisteredRoutine reg : routines) {
            if (!reg.enabled()) continue;
            final Routine r = reg.routine();
            switch (r.trigger()) {
                case Routine.Trigger.OnMatch _ -> evaluateMatchEdge(reg, true, now);
                case Routine.Trigger.OnUnmatch _ -> evaluateMatchEdge(reg, false, now);
                case Routine.Trigger.Interval interval -> {
                    if (playerUuid == null || !r.ql().matches(player)) continue;
                    final Long last = routineLastFired.get(r.id());
                    if (last == null || (now - last) >= interval.millis()) tryFire(reg, now);
                }
                case Routine.Trigger.OnPacket _ -> { /* handled by evaluateRoutinesOnPacket */ }
            }
        }
    }

    private void evaluateMatchEdge(RegisteredRoutine reg, boolean fireOnMatch, long now) {
        final UUID id = reg.routine().id();
        final boolean matches = playerUuid != null && reg.routine().ql().matches(player);
        final boolean was = routineMatched.getOrDefault(id, false);
        if (matches != was && matches == fireOnMatch) tryFire(reg, now);
        routineMatched.put(id, matches);
    }

    private void tryFire(RegisteredRoutine reg, long now) {
        final Routine r = reg.routine();
        if (r.debounceMs() > 0) {
            final Long last = routineLastFired.get(r.id());
            if (last != null && (now - last) < r.debounceMs()) return;
        }
        routineLastFired.put(r.id(), now);
        final ActionExecutor exec = actionExecutor;
        if (exec == null) return;
        try { exec.execute(r.action(), player); }
        catch (Throwable t) { LOGGER.warn("routine {} action failed: {}", r.id(), t.toString()); }
    }

    public void assertOwnerThread() {
        if (ownerThread == null) {
            throw new IllegalStateException("session " + id + " has no owner thread bound");
        }
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "PlayerState access must run on " + ownerThread.getName()
                            + " (was " + Thread.currentThread().getName() + ")");
        }
    }

    public PlayerState playerForOwnerThread() {
        assertOwnerThread();
        return player;
    }

    public UUID refreshPlayerUuid() {
        assertOwnerThread();
        playerUuid = player.uuid;
        return playerUuid;
    }

    public boolean close() {
        if (!closed.compareAndSet(false, true)) return false;
        if (isOwnerThread()) {
            finishClose();
        } else {
            try { mutateState(_ -> finishClose()); }
            catch (Throwable t) { LOGGER.debug("close ack failed for {}: {}", id, t.toString()); }
        }
        final Thread defaultLoop = defaultLoopThread;
        if (defaultLoop != null) defaultLoop.interrupt();
        return true;
    }

    private void finishClose() {
        assertOwnerThread();
        disconnectedAt = System.currentTimeMillis();
        player.disconnectedAt = disconnectedAt;
        final Runnable cb = onClosed;
        if (cb != null) cb.run();
        publish(new SessionEvent.Closed(disconnectedAt));
        listeners.clear();
        stopping.set(true);
    }

    private void enqueueStateTask(StateTask<?> task) {
        if (!stateTasks.offer(task)) {
            throw new MailboxException(MailboxException.Reason.BUSY,
                    "state worker queue full for session " + id);
        }
    }

    public void tickCadence(long now) {
        assertOwnerThread();
        if (listeners.isEmpty()) {
            lastPatchMs = lastMinimapMs = now;
            return;
        }
        if (now - lastPatchMs >= PATCH_INTERVAL_MS) {
            lastPatchMs = now;
            player.flushTrafficCounters();
            // Bridge sums these into the scope's global metrics — fires unconditionally so a
            // late metrics subscriber sees fresh totals without a mailbox roundtrip.
            publish(new SessionEvent.TrafficSnapshot(
                    now,
                    player.traffic.bytesIn,
                    player.traffic.bytesOut,
                    player.traffic.packetsIn,
                    player.traffic.packetsOut));
            if (patchActive.getAsBoolean() && player.hasPending()) {
                StatePatch patch = player.drainPatch(path -> {
                    Function<PlayerState, Object> fn = COMPUTERS.get(path);
                    return fn == null ? null : fn.apply(player);
                });
                if (patch != null && !patch.isEmpty()) {
                    publish(new SessionEvent.Patch(patch));
                }
            }
        }
        if (now - lastMinimapMs >= MINIMAP_INTERVAL_MS) {
            lastMinimapMs = now;
            if (minimapActive.getAsBoolean()) {
                JsonObject frame = MinimapCodec.frameJson(player);
                if (frame != null) publish(new SessionEvent.MinimapFrame(frame));
            }
        }
        evaluateRoutinesCadence(now);
    }

    private static final class StateTask<T> {
        private final StateCall<T> body;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        StateTask(StateCall<T> body) {
            this.body = body;
        }

        void run(PlayerState player) {
            try {
                result.complete(body.apply(player));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        }

        T get() throws Exception {
            try {
                return result.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                if (cause instanceof Exception exception) throw exception;
                throw new RuntimeException(cause);
            }
        }

        T getWithin(long timeoutMs) throws Exception {
            try {
                return result.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            } catch (java.util.concurrent.TimeoutException _) {
                throw new MailboxException(MailboxException.Reason.TIMEOUT,
                        "session worker exceeded " + timeoutMs + "ms");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                if (cause instanceof Exception exception) throw exception;
                throw new RuntimeException(cause);
            }
        }
    }

    @FunctionalInterface
    public interface StateCall<T> {
        T apply(PlayerState player) throws Exception;
    }
}
