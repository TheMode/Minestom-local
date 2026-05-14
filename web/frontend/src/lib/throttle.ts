// Traffic-shaper domain model + math for the Throttle view.

export type Throttle = {
    latencyMs: number;
    jitterMs: number;
    bandwidthBytesPerSec: number;
    direction: 'CLIENTBOUND' | 'SERVERBOUND' | null;
};

/// A blank draft. Used to seed sliders when nothing is engaged yet.
export const ZERO: Throttle = {
    latencyMs: 0,
    jitterMs: 0,
    bandwidthBytesPerSec: 0,
    direction: null,
};

// Logarithmic mapping for bandwidth: 0..1 maps to 0..16 MiB/s. Floored at 1 B/s so any
// non-zero slider position yields a non-zero rate (no dead zone at the low end).
export const BW_MAX = 16 * 1024 * 1024;
export const bwToFrac = (b: number) => b <= 0 ? 0 : Math.min(1, Math.log10(1 + b) / Math.log10(1 + BW_MAX));
export const fracToBw = (f: number) => {
    if (f <= 0) return 0;
    if (f >= 1) return BW_MAX;
    return Math.max(1, Math.round(Math.pow(10, f * Math.log10(1 + BW_MAX)) - 1));
};

export const fmtBw = (b: number) => {
    if (b <= 0) return 'unlimited';
    if (b < 1024) return b + ' B/s';
    if (b < 1024 * 1024) return (b / 1024).toFixed(b < 10_240 ? 1 : 0) + ' KB/s';
    return (b / (1024 * 1024)).toFixed(b < 10 * 1024 * 1024 ? 2 : 1) + ' MB/s';
};

/// A throttle is "active" iff at least one knob is non-zero. Null is bypass.
export const isActive = (t: Throttle | null | undefined): t is Throttle =>
    !!t && (t.latencyMs > 0 || t.jitterMs > 0 || t.bandwidthBytesPerSec > 0);

export function throttleEquals(a: Throttle, b: Throttle): boolean {
    return a.latencyMs === b.latencyMs
        && a.jitterMs === b.jitterMs
        && a.bandwidthBytesPerSec === b.bandwidthBytesPerSec
        && a.direction === b.direction;
}

// Deterministic 1-D pseudo-random for the scope path — repeatable per (seed, x).
function pseudo(n: number): number {
    const x = Math.sin(n * 12.9898) * 43758.5453;
    return x - Math.floor(x);
}

/// Tiny oscilloscope: a wavy line whose amplitude/frequency tracks the active throttle.
/// Latency/jitter swell the amplitude; bandwidth raises density.
export function scopePath(t: Throttle | null): string {
    const W = 200, H = 32, mid = H / 2;
    const live = isActive(t);
    const throttle = live ? t : ZERO;
    const amp = Math.min(12, live ? (throttle.latencyMs / 200 + throttle.jitterMs / 50) : 0.5);
    const freq = 0.05 + (live ? throttle.bandwidthBytesPerSec / BW_MAX : 0) * 0.15;
    const seed = live
        ? Math.floor((throttle.latencyMs + throttle.jitterMs * 3 + throttle.bandwidthBytesPerSec / 1000) % 9973)
        : 0;
    let d = '';
    for (let x = 0; x <= W; x += 2) {
        const jitterNoise = live && throttle.jitterMs ? (pseudo(x * 7 + seed) - 0.5) * (throttle.jitterMs / 40) : 0;
        const y = mid + Math.sin(x * freq) * amp + jitterNoise;
        d += (x === 0 ? 'M' : ' L') + x.toFixed(0) + ' ' + y.toFixed(2);
    }
    return d;
}
