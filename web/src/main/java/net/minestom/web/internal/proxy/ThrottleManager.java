package net.minestom.web.internal.proxy;

import net.minestom.web.Direction;
import net.minestom.web.Throttle;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/// Per-process throttle policy store: one global profile plus a per-player overlay, consulted on
/// every byte chunk via [#delayFor].
///
/// Stream-level, never packet-level — it only sees a byte count, not contents. A null reference
/// at either layer is bypass; setters canonicalise no-op throttles (all knobs zero) to `null` so
/// the per-packet path never re-checks `isActive`.
public final class ThrottleManager {

    private volatile @Nullable Throttle global;
    private final Map<UUID, Throttle> perPlayer = new ConcurrentHashMap<>();

    public @Nullable Throttle global() {
        return global;
    }

    public void setGlobal(@Nullable Throttle throttle) {
        this.global = (throttle != null && throttle.isActive()) ? throttle : null;
    }

    public Map<UUID, Throttle> perPlayer() {
        return Collections.unmodifiableMap(perPlayer);
    }

    /// `null` or a no-op throttle clears any existing entry for `uuid`.
    public void setForPlayer(UUID uuid, @Nullable Throttle throttle) {
        if (uuid == null) return;
        if (throttle == null || !throttle.isActive()) perPlayer.remove(uuid);
        else perPlayer.put(uuid, throttle);
    }

    /// Effective throttle for a connection. Per-player overrides global; global is the fallback;
    /// returns `null` if neither applies. Setters canonicalise no-op throttles to `null`, so a
    /// non-null map entry is always active.
    public @Nullable Throttle resolve(@Nullable UUID playerUuid) {
        final Throttle g = global;
        // Hot path: zero connections throttled. Single volatile read + cheap sumCount on CHM.
        if (g == null && perPlayer.isEmpty()) return null;
        if (playerUuid != null) {
            final Throttle t = perPlayer.get(playerUuid);
            if (t != null) return t;
        }
        return g;
    }

    /// Per-direction outgoing bookkeeping. Tracks the latest scheduled send time so jitter and
    /// bandwidth spacing can't reorder bytes on the wire.
    public static final class WorkerState {
        private long nextSendNanos;
    }

    /// How many nanoseconds the worker should hold this chunk of `bytes` before letting it leave
    /// on `direction`. Returns 0 for "send now". Mutates `state.nextSendNanos` so subsequent
    /// chunks on the same direction can't be scheduled to leave earlier than this one.
    public long delayFor(WorkerState state, @Nullable UUID playerUuid, Direction direction, int bytes) {
        final Throttle t = resolve(playerUuid);
        if (t == null || !t.appliesTo(direction)) return 0L;

        final long now = System.nanoTime();
        long sendAt = now;
        if (t.latencyMs() > 0 || t.jitterMs() > 0) {
            int extra = t.jitterMs() > 0 ? ThreadLocalRandom.current().nextInt(t.jitterMs() + 1) : 0;
            sendAt += (long) (t.latencyMs() + extra) * 1_000_000L;
        }
        sendAt = Math.max(sendAt, state.nextSendNanos);

        final long bps = t.bandwidthBytesPerSec();
        if (bps > 0L && bytes > 0) {
            final long spacing = (long) bytes * 1_000_000_000L / bps;
            state.nextSendNanos = sendAt + spacing;
        } else {
            state.nextSendNanos = sendAt;
        }

        final long delay = sendAt - now;
        return delay <= 0L ? 0L : delay;
    }

    /// Shared scheduler that fires deferred writes back onto each connection's worker queue.
    /// Single-threaded so tasks scheduled for the same instant run in submission order
    /// (preserves per-connection FIFO when many connections all hit the same `sendAt`).
    private static final ScheduledExecutorService DELAY = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("Minestom-Web-Throttle-Delay").factory());

    public static void schedule(long delayNanos, Runnable task) {
        DELAY.schedule(task, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
    }
}
