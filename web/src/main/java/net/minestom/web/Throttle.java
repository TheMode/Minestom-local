package net.minestom.web;

import org.jetbrains.annotations.Nullable;

/// Socket-level throttle profile applied to a TCP byte stream by
/// [net.minestom.web.internal.proxy.ThrottleManager] — nothing here is protocol-aware. A `null`
/// reference (rather than a throttle) is how callers disable throttling.
///
///   - `latencyMs` — fixed delay bytes are held in transit.
///   - `jitterMs` — random extra delay in `[0, jitterMs]` on top of `latencyMs`, clamped
///     monotonic per direction so it can't reorder the stream.
///   - `bandwidthBytesPerSec` — outgoing byte-rate cap; 0 = unlimited.
///   - `direction` — if non-null, applies only to that direction; null = both.
public record Throttle(
        int latencyMs,
        int jitterMs,
        long bandwidthBytesPerSec,
        @Nullable Direction direction
) {
    public Throttle {
        if (latencyMs < 0) latencyMs = 0;
        if (jitterMs < 0) jitterMs = 0;
        if (bandwidthBytesPerSec < 0L) bandwidthBytesPerSec = 0L;
    }

    public boolean isActive() {
        return latencyMs > 0 || jitterMs > 0 || bandwidthBytesPerSec > 0L;
    }

    public boolean appliesTo(Direction actual) {
        return direction == null || direction == actual;
    }
}
