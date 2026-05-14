// Packet aggregation — maps, swimlanes, rolling rates, anomaly hints.

export const SUBJECTS = ['self', 'ent', 'world', 'hud', 'win', 'net', 'chat'];
export const BUCKETS = 20;
export const RATE_WINDOW = 3;

export type Anomaly = { kind: 'spike' | 'new' | 'drop'; msg: string; ts: number };
export type PacketRow = {
    seq: number;
    ts: number;
    direction: string;
    state: string;
    className: string;
    sizeBytes: number;
    subject: string;
    subjectGroup: string;
    subjectLabel: string;
    uuid: string;
    connectionId: string;
};
export type Lane = {
    count: number;
    cbBytes: number;
    sbBytes: number;
    buckets: Uint16Array;
    cb: Uint16Array;
    bucketTs: number;
};
export type PacketAgg = {
    byClass: Map<string, { count: number; bytes: number; cb: number; sb: number }>;
    byHeatmap: Map<string, { count: number; bytes: number }>;
    total: { count: number; bytes: number };
    byPlayer: Map<string, Lane>;
    window: { bucketTs: number; buckets: Uint16Array; byteBuckets: Uint32Array };
    anomaly: { prev: Map<string, number>; seen: Set<string> };
};

/// Canonical client-bound predicate. The backend emits uppercase `CLIENTBOUND`/`SERVERBOUND`;
/// the `toUpperCase` keeps it robust against any other casing.
export const isClientBound = (dir: string): boolean => String(dir).toUpperCase().startsWith('CLIENT');

/// Strip the `Client`/`Clientbound` direction prefix and `Packet` suffix from a packet class
/// name, leaving the bare logical name (e.g. `ClientChatMessagePacket` → `ChatMessage`).
export const normalizePacketClass = (cls: string): string =>
    (cls || '').replace(/^Clientbound|^Client/, '').replace(/Packet$/, '');

export const pktLabel = (cls: string) => cls.replace(/Packet$/, '');

export function makeAgg(): PacketAgg {
    return {
        byClass: new Map(),
        byHeatmap: new Map(),
        total: { count: 0, bytes: 0 },
        byPlayer: new Map(),
        window: { bucketTs: sec(), buckets: u16(), byteBuckets: u32() },
        anomaly: { prev: new Map(), seen: new Set() },
    };
}

export function emptyLane(): Lane {
    return { count: 0, cbBytes: 0, sbBytes: 0, buckets: u16(), cb: u16(), bucketTs: sec() };
}

export function normalizeRow(msg: Record<string, unknown>): PacketRow {
    return {
        seq: Number(msg.seq) || 0,
        ts: wireTs(msg.ts),
        direction: String(msg.direction ?? ''),
        state: String(msg.state ?? ''),
        className: String(msg.className ?? ''),
        sizeBytes: Number(msg.sizeBytes) || 0,
        subject: String(msg.subject ?? ''),
        subjectGroup: String(msg.subjectGroup ?? 'net'),
        subjectLabel: String(msg.subjectLabel ?? msg.subject ?? ''),
        uuid: String(msg.uuid ?? ''),
        connectionId: String(msg.connectionId ?? ''),
    };
}

export function applyRow(agg: PacketAgg, row: PacketRow, uuid = '', lanes = true) {
    const cls = row.className;
    if (!cls) return;
    let c = agg.byClass.get(cls);
    if (!c) { c = { count: 0, bytes: 0, cb: 0, sb: 0 }; agg.byClass.set(cls, c); }
    c.count++; c.bytes += row.sizeBytes;
    const cb = isClientBound(row.direction);
    if (cb) c.cb++; else c.sb++;
    const key = (cb ? 'cb' : 'sb') + '|' + row.subjectGroup;
    let h = agg.byHeatmap.get(key);
    if (!h) { h = { count: 0, bytes: 0 }; agg.byHeatmap.set(key, h); }
    h.count++; h.bytes += row.sizeBytes;
    agg.total.count++; agg.total.bytes += row.sizeBytes;

    const ts = Math.floor(row.ts / 1000);
    const i = BUCKETS - 1;
    agg.window.bucketTs = advance(agg.window.bucketTs, ts, agg.window.buckets, agg.window.byteBuckets);
    agg.window.buckets[i]++;
    agg.window.byteBuckets[i] += row.sizeBytes;

    if (!lanes || !uuid) return;
    let lane = agg.byPlayer.get(uuid);
    if (!lane) { lane = emptyLane(); agg.byPlayer.set(uuid, lane); }
    lane.count++;
    if (cb) lane.cbBytes += row.sizeBytes; else lane.sbBytes += row.sizeBytes;
    lane.bucketTs = advance(lane.bucketTs, ts, lane.buckets, lane.cb);
    lane.buckets[i]++;
    if (cb) lane.cb[i]++;
}

export function tickBuckets(agg: PacketAgg) {
    const ts = sec();
    agg.window.bucketTs = advance(agg.window.bucketTs, ts, agg.window.buckets, agg.window.byteBuckets);
    for (const lane of agg.byPlayer.values())
        lane.bucketTs = advance(lane.bucketTs, ts, lane.buckets, lane.cb);
}

export function stepAggSecond(agg: PacketAgg, anomalies: Anomaly[], scan: boolean): Anomaly[] {
    tickBuckets(agg);
    if (!scan) return anomalies;
    const found = scanAnomalies(agg);
    return found.length ? [...found, ...anomalies].slice(0, 8) : anomalies;
}

export function aggView(agg: PacketAgg, players?: { uuid: string }[]) {
    let cbBytes = 0;
    for (const [k, v] of agg.byHeatmap) if (k.startsWith('cb|')) cbBytes += v.bytes;
    const totalBytes = agg.total.bytes;
    let topClass: { k: string; count: number } | null = null;
    for (const [k, v] of agg.byClass) {
        if (!topClass || v.count > topClass.count) topClass = { k, count: v.count };
    }
    const view = {
        totalCount: agg.total.count,
        totalBytes,
        cbBytes,
        sbBytes: totalBytes - cbBytes,
        cbPct: totalBytes ? (cbBytes / totalBytes) * 100 : 50,
        pps: tail(agg.window.buckets) / RATE_WINDOW,
        bps: tail(agg.window.byteBuckets) / RATE_WINDOW,
        topClass,
        classCount: agg.byClass.size,
        streamCount: agg.byPlayer.size,
        lanes: [] as { p: { uuid: string }; lane: Lane }[],
        gmax: 1,
    };
    if (!players?.length) return view;
    view.lanes = players
        .map(p => ({ p, lane: agg.byPlayer.get(p.uuid) ?? emptyLane() }))
        .sort((a, b) => (b.lane.buckets[BUCKETS - 1] || 0) - (a.lane.buckets[BUCKETS - 1] || 0));
    for (const { lane } of view.lanes) for (const v of lane.buckets) if (v > view.gmax) view.gmax = v;
    return view;
}

export function scanAnomalies(agg: PacketAgg, max = 8): Anomaly[] {
    const { prev, seen } = agg.anomaly;
    const out: Anomaly[] = [];
    const ts = Date.now();
    for (const [cls, info] of agg.byClass) {
        const p = prev.get(cls) ?? 0;
        const label = pktLabel(cls);
        if (!seen.has(cls) && info.count >= 5) {
            seen.add(cls);
            out.push({ kind: 'new', msg: `New class ${label} on the wire`, ts });
        } else if (p >= 25 && info.count <= Math.max(1, p * 0.15)) {
            out.push({ kind: 'drop', msg: `${label} fell to near-zero vs prior sample`, ts });
        } else if (info.count - p >= Math.max(12, p * 0.5)) {
            out.push({ kind: 'spike', msg: `${label} +${(p ? Math.round(((info.count - p) / p) * 100) : 100)}% since last second`, ts });
        }
    }
    agg.anomaly.prev = new Map([...agg.byClass].map(([k, v]) => [k, v.count]));
    return out.slice(0, max);
}

const sec = () => Math.floor(Date.now() / 1000);
const u16 = () => new Uint16Array(BUCKETS);

function wireTs(raw: unknown): number {
    const n = Number(raw);
    if (!Number.isFinite(n) || n <= 0) return Date.now();
    if (n > 1e14) return Date.now();
    if (n < 1e11) return n * 1000;
    return n;
}
const u32 = () => new Uint32Array(BUCKETS);

function advance(bucketTs: number, ts: number, ...arrays: (Uint16Array | Uint32Array)[]): number {
    const slideBy = ts - bucketTs;
    if (slideBy <= 0) return bucketTs;
    for (const a of arrays) {
        if (slideBy >= BUCKETS) a.fill(0);
        else {
            a.copyWithin(0, slideBy);
            for (let i = BUCKETS - slideBy; i < BUCKETS; i++) a[i] = 0;
        }
    }
    return ts;
}

function tail(arr: ArrayLike<number>) {
    let s = 0;
    for (let i = Math.max(0, arr.length - RATE_WINDOW); i < arr.length; i++) s += arr[i] || 0;
    return s;
}
