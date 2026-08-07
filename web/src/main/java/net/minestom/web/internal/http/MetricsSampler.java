package net.minestom.web.internal.http;

/// Rolling buffer of per-second server samples — bytes/s and packets/s (in + out), plus the
/// current connection gauge. Tick at a steady cadence (1Hz) with the running totals; deltas
/// between successive totals become rates. Player disconnects can drop the global counters, so
/// negative deltas are clamped to zero.
public final class MetricsSampler {
    public record Sample(long ts,
                         long bytesIn, long bytesOut,
                         long packetsIn, long packetsOut,
                         int connections) {}

    private final Sample[] buf;
    private int head;
    private int size;
    private long lastTs;
    private long lastBytesIn, lastBytesOut, lastPacketsIn, lastPacketsOut;

    public MetricsSampler(int capacity) {
        this.buf = new Sample[capacity];
    }

    public synchronized Sample tick(long ts, long bytesIn, long bytesOut,
                                    long packetsIn, long packetsOut, int connections) {
        if (lastTs == 0) {
            lastTs = ts;
            lastBytesIn = bytesIn; lastBytesOut = bytesOut;
            lastPacketsIn = packetsIn; lastPacketsOut = packetsOut;
            return null;
        }
        double dt = Math.max(0.001, (ts - lastTs) / 1000.0);
        Sample s = new Sample(ts,
                rate(bytesIn, lastBytesIn, dt),    rate(bytesOut, lastBytesOut, dt),
                rate(packetsIn, lastPacketsIn, dt), rate(packetsOut, lastPacketsOut, dt),
                connections);
        lastTs = ts;
        lastBytesIn = bytesIn; lastBytesOut = bytesOut;
        lastPacketsIn = packetsIn; lastPacketsOut = packetsOut;
        buf[head] = s;
        head = (head + 1) % buf.length;
        if (size < buf.length) size++;
        return s;
    }

    public synchronized Sample[] snapshot() {
        Sample[] out = new Sample[size];
        int start = (head - size + buf.length) % buf.length;
        for (int i = 0; i < size; i++) out[i] = buf[(start + i) % buf.length];
        return out;
    }

    private static long rate(long now, long prev, double dt) {
        return Math.round(Math.max(0, (now - prev) / dt));
    }
}
