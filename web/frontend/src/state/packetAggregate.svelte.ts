import { api, bus } from '../lib/api.ts';
import { playerPackets, Topics, type PlayerPacketsMessage, type PacketsAggregateMessage } from '../lib/topics.ts';
import {
    applyRow,
    makeAgg,
    normalizeRow,
    stepAggSecond,
    type Anomaly,
    type PacketAgg,
    type PacketRow,
} from '../lib/packetAgg.ts';

function scheduleBump(tick: () => void) {
    let due = false;
    return () => {
        if (due) return;
        due = true;
        requestAnimationFrame(() => { due = false; tick(); });
    };
}

type BaseOpts = { lanes?: boolean; anomalies?: boolean };

/// Shared aggregate state + 1Hz tick + seq-level dedup. The per-player and global
/// hooks layer their own subscription/seed strategies on top.
function createAggregateBase(opts: BaseOpts) {
    const agg = $state.raw(makeAgg());
    let version = $state(0);
    let now = $state(Date.now());
    let anomalies = $state<Anomaly[]>([]);
    const seen = new Set<string>();
    const lanes = opts.lanes !== false;
    const bump = scheduleBump(() => { version++; });

    const tryIngest = (msg: Record<string, unknown>): { row: PacketRow; fresh: boolean } => {
        const row = normalizeRow(msg);
        const key = `${row.connectionId || row.uuid}:${row.seq}`;
        if (seen.has(key)) return { row, fresh: false };
        seen.add(key);
        applyRow(agg, row, String(msg.uuid ?? row.uuid ?? ''), lanes);
        return { row, fresh: true };
    };

    const reset = () => {
        Object.assign(agg, makeAgg());
        seen.clear();
        anomalies = [];
        version = 0;
    };

    $effect(() => {
        const id = setInterval(() => {
            now = Date.now();
            anomalies = stepAggSecond(agg, anomalies, !!opts.anomalies);
            bump();
        }, 1000);
        return () => clearInterval(id);
    });

    return {
        get agg(): PacketAgg { return agg; },
        get version() { return version; },
        get now() { return now; },
        get anomalies() { return anomalies; },
        bump,
        tryIngest,
        reset,
    };
}

type Options = {
    history?: boolean;
    historyLimit?: number;
    lanes?: boolean;
    anomalies?: boolean;
    enabled?: () => boolean;
    resetKey?: () => string | null | undefined;
    onRow?: (row: PacketRow) => void;
};

type PacketSource = { uuid: string; connectionId: string };

export function usePacketAggregate(sources: () => PacketSource[], opts: Options = {}) {
    const base = createAggregateBase({ lanes: opts.lanes, anomalies: opts.anomalies });
    const seeded = new Set<string>();

    const ingest = (msg: Record<string, unknown>, live: boolean): PacketRow | undefined => {
        if (live && opts.enabled && !opts.enabled()) return undefined;
        const { row, fresh } = base.tryIngest(msg);
        if (!fresh) return row;
        if (live) { opts.onRow?.(row); base.bump(); }
        return row;
    };

    const ingestRows = (rows: PacketRow[], uuid = '') => {
        let changed = false;
        for (const row of rows) {
            const { fresh } = base.tryIngest({ ...row, uuid: uuid || row.uuid });
            if (fresh) changed = true;
        }
        if (changed) base.bump();
    };

    const reset = () => {
        seeded.clear();
        base.reset();
    };

    $effect(() => {
        if (opts.resetKey?.() == null) return;
        reset();
    });

    $effect(() => {
        const srcs = sources().filter(s => s.uuid);
        if (!srcs.length) return undefined;
        const unsubs = srcs.map(s => bus.subscribe(playerPackets(s.uuid), (m: PlayerPacketsMessage) => ingest(m, true)));
        return () => unsubs.forEach(u => u());
    });

    $effect(() => {
        let alive = true;
        if (opts.history === false) return () => { alive = false; };
        const pending = sources().filter(s => s.uuid && s.connectionId && !seeded.has(s.connectionId));
        if (!pending.length) return () => { alive = false; };

        const limit = opts.historyLimit ?? 400;
        const pageSize = limit <= 0 ? 5000 : limit;
        const load = async (source: PacketSource) => {
            const recs: Record<string, unknown>[] = [];
            let since = 0;
            while (true) {
                const page = await api<Record<string, unknown>[]>(`/connections/${source.connectionId}/packets?since=${since}&limit=${pageSize}`);
                if (!page.length) break;
                recs.push(...page);
                since = Number(page[page.length - 1]?.seq) || since;
                if (limit > 0 || page.length < pageSize) break;
            }
            return { source, recs };
        };
        Promise.all(pending.map(source =>
            load(source).catch(() => ({ source, recs: [] as Record<string, unknown>[] })),
        )).then(results => {
            if (!alive) return;
            for (const { source, recs } of results) {
                seeded.add(source.connectionId);
                for (const rec of recs) {
                    ingest({ ...rec, uuid: source.uuid, connectionId: source.connectionId }, false);
                }
            }
            if (results.some(r => r.recs.length)) base.bump();
        });

        return () => { alive = false; };
    });

    return {
        get agg(): PacketAgg { return base.agg; },
        get version() { return base.version; },
        get now() { return base.now; },
        get anomalies() { return base.anomalies; },
        ingestRows,
        reset,
    };
}

type GlobalOpts = {
    anomalies?: boolean;
    enabled?: () => boolean;
};

/// Global packet analysis — one `packets:aggregate` subscription instead of per-player streams.
export function useGlobalPacketAggregate(opts: GlobalOpts = {}) {
    const base = createAggregateBase({ anomalies: opts.anomalies });

    $effect(() => {
        return bus.subscribe<PacketsAggregateMessage>(Topics.packetsAggregate, msg => {
            const rows = msg.rows;
            if (!rows?.length) return;
            let changed = false;
            for (const rec of rows) {
                if (opts.enabled && !opts.enabled()) continue;
                if (base.tryIngest(rec).fresh) changed = true;
            }
            if (changed) base.bump();
        });
    });

    return {
        get agg(): PacketAgg { return base.agg; },
        get version() { return base.version; },
        get now() { return base.now; },
        get anomalies() { return base.anomalies; },
    };
}
