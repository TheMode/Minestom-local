package net.minestom.web.internal.http;

import java.util.concurrent.ConcurrentHashMap;

/// Simple per-key token bucket. All mutation happens under the bucket's monitor so the tokens
/// count is plain `long` — no need for {@link java.util.concurrent.atomic.AtomicLong} inside the
/// critical section. Used to gate POST endpoints per auth token / IP; the proxy itself is
/// unaffected by HTTP rate limits.
public final class RateLimiter {
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long capacity;
    private final long refillPerSecond;

    public RateLimiter(long capacity, long refillPerSecond) {
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
    }

    public boolean tryAcquire(String key) {
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(capacity));
        synchronized (b) {
            long now = System.nanoTime();
            b.lastAccessNanos = now;
            long elapsed = now - b.lastRefillNanos;
            if (elapsed > 0) {
                long add = elapsed * refillPerSecond / 1_000_000_000L;
                if (add > 0) {
                    b.tokens = Math.min(capacity, b.tokens + add);
                    b.lastRefillNanos = now;
                }
            }
            if (b.tokens > 0) {
                b.tokens--;
                return true;
            }
            return false;
        }
    }

    /// Drop buckets untouched for longer than `idleNanos`. An idle bucket would refill to full
    /// capacity anyway, so recreating it on the next request loses no meaningful rate state —
    /// this just keeps the per-key map from growing without bound. Call periodically.
    public void sweepIdle(long idleNanos) {
        final long cutoff = System.nanoTime() - idleNanos;
        buckets.values().removeIf(b -> {
            synchronized (b) { return b.lastAccessNanos < cutoff; }
        });
    }

    private static final class Bucket {
        long tokens;
        long lastRefillNanos = System.nanoTime();
        long lastAccessNanos = System.nanoTime();

        Bucket(long initialTokens) {
            this.tokens = initialTokens;
        }
    }
}
