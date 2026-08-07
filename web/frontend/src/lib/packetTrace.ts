import type { PacketRow } from './packetAgg.ts';
import { pktLabel, isClientBound, normalizePacketClass } from './packetAgg.ts';
import { parseQuery, type ParsedRow } from './packetTraceDsl.ts';
import type { PacketTape } from './packetTape.ts';
import type { FacetMode, StreamEntry, Related, Bookmark, Breakpoint } from '../components/packet-trace/types.ts';

export { isClientBound };

const PACKET_CLASS_FIELDS: Record<string, string[]> = {
    PlayerPosition: ['posX', 'posY', 'posZ'],
    PlayerRotation: ['yaw', 'pitch'],
    PlayerPositionAndRotation: ['posX', 'posY', 'posZ', 'yaw', 'pitch'],
    PlayerPositionAndLook: ['posX', 'posY', 'posZ', 'yaw', 'pitch'],
    PlayerPositionStatus: ['posX', 'posY', 'posZ'],
    EntityPosition: ['posX', 'posY', 'posZ'],
    EntityPositionAndRotation: ['posX', 'posY', 'posZ', 'yaw', 'pitch'],
    UpdateHealth: ['health', 'food'],
    SetExperience: ['xpBar', 'xpLevel'],
    HeldItemChange: ['selectedHotbar'],
    SetSlot: ['selectedHotbar'],
    PlayerAbilities: ['flying', 'flySpeed', 'walkSpeed'],
    ChangeGameState: ['health'],
    JoinGame: ['dimension', 'gamemode'],
    Respawn: ['dimension', 'gamemode'],
    KeepAlive: ['traffic.pingMs'],
};

export function fieldsForPacket(className: string): string[] {
    return PACKET_CLASS_FIELDS[normalizePacketClass(className)] || [];
}

export const LIFECYCLE_GLYPH: Record<string, string> = {
    CONNECT: '◉',
    HANDSHAKE: '↪',
    LOGIN_START: '⌗',
    COMPRESSION_SET: '≋',
    LOGIN_SUCCESS: '✓',
    CONFIGURATION_START: '⚙',
    CONFIGURATION_FINISH: '⚙',
    PLAY_START: '▶',
    DISCONNECT: '✕',
};

export function rowSummary(r: PacketRow): string {
    const subj = r.subjectLabel?.trim();
    if (subj) return subj;
    const cls = pktLabel(r.className);
    if (/Position|Rotation|Look/i.test(cls)) return 'movement';
    if (/KeepAlive/i.test(cls)) return 'keepalive';
    return cls.length > 28 ? cls.slice(0, 26) + '…' : cls;
}

const PACKET_DETAIL_MAX = 400;
const packetDetailCache = new Map<string, Record<string, unknown>>();

export function getCachedPacketDetail(uuid: string, seq: number): Record<string, unknown> | undefined {
    return packetDetailCache.get(`${uuid}:${seq}`);
}

export function setCachedPacketDetail(uuid: string, seq: number, data: Record<string, unknown>): void {
    const k = `${uuid}:${seq}`;
    if (packetDetailCache.size >= PACKET_DETAIL_MAX && !packetDetailCache.has(k)) {
        const oldest = packetDetailCache.keys().next().value;
        if (oldest) packetDetailCache.delete(oldest);
    }
    packetDetailCache.set(k, data);
}

export function clearPacketDetailCache(uuid?: string): void {
    if (!uuid) { packetDetailCache.clear(); return; }
    const prefix = uuid + ':';
    for (const k of [...packetDetailCache.keys()]) {
        if (k.startsWith(prefix)) packetDetailCache.delete(k);
    }
}

// ── PacketTrace view derivations (pure) ──────────────────────────────

export const ACCENTS = {
    phosphor: { acc: 'oklch(78% 0.18 148)', deep: 'oklch(54% 0.14 148)' },
    amber:    { acc: 'oklch(82% 0.16 75)',  deep: 'oklch(60% 0.14 75)' },
    cyan:     { acc: 'oklch(78% 0.13 200)', deep: 'oklch(56% 0.12 200)' },
    magenta:  { acc: 'oklch(72% 0.20 320)', deep: 'oklch(54% 0.16 320)' },
} as const;

export type AccentKey = keyof typeof ACCENTS;

/// Tag a row with its lazily-computed summary and bookmark flag (mutates `p` in place,
/// matching the original derivation which reused the row object as a `ParsedRow`).
export function prepareParsedRow(p: PacketRow, bookmarkMap: Map<number, Bookmark>): ParsedRow {
    const pp = p as ParsedRow;
    pp.summary ??= rowSummary(p);
    pp._bookmarked = bookmarkMap.has(p.seq);
    return pp;
}

export type FacetRule = { field: string; includes: Set<string>; excludes: Set<string> };
export type ClassRule = { includes: Set<string>; excludes: Set<string> };

export function buildFacetRules(filters: Record<string, Record<string, FacetMode>>): FacetRule[] {
    return Object.entries(filters).map(([field, vals]) => {
        const includes = new Set<string>();
        const excludes = new Set<string>();
        for (const [value, mode] of Object.entries(vals)) {
            if (mode === 'include') includes.add(value);
            else if (mode === 'exclude') excludes.add(value);
        }
        return { field, includes, excludes };
    });
}

export function buildClassRules(classFilter: Record<string, FacetMode>): ClassRule {
    const includes = new Set<string>();
    const excludes = new Set<string>();
    for (const [cls, mode] of Object.entries(classFilter)) {
        if (mode === 'include') includes.add(cls);
        else if (mode === 'exclude') excludes.add(cls);
    }
    return { includes, excludes };
}

export function matchFacets(p: PacketRow, facetRules: FacetRule[], classRules: ClassRule): boolean {
    for (const { field, includes, excludes } of facetRules) {
        const v = (p as unknown as Record<string, string>)[field];
        if (excludes.has(v)) return false;
        if (includes.size && !includes.has(v)) return false;
    }
    if (classRules.excludes.has(p.className)) return false;
    if (classRules.includes.size && !classRules.includes.has(p.className)) return false;
    return true;
}

/// Build the row stream, optionally collapsing runs (≥4) of the same class+direction that
/// arrive within 200ms of each other into a single group entry. Lifecycle markers are
/// interleaved by sequence.
export function buildStreamEntries(
    rows: readonly PacketRow[],
    lifecycleMarkers: readonly { seq: number; label: string }[],
    collapse: boolean,
    bookmarkMap: Map<number, Bookmark>,
): StreamEntry[] {
    const lifeBySeq = new Map<number, { label: string }>();
    for (const l of lifecycleMarkers) lifeBySeq.set(l.seq, l);

    const out: StreamEntry[] = [];
    let prevTs: number | null = null;
    let i = 0;
    while (i < rows.length) {
        const p = rows[i];
        const life = lifeBySeq.get(p.seq);
        if (life) out.push({ kind: 'lifecycle', seq: p.seq, label: life.label });

        if (collapse) {
            let j = i + 1;
            while (
                j < rows.length
                && rows[j].className === p.className
                && rows[j].direction === p.direction
                && rows[j].ts - rows[j - 1].ts < 200
            ) j++;
            const count = j - i;
            if (count >= 4) {
                out.push({ kind: 'group', first: p, last: rows[j - 1], count, seqStart: p.seq, seqEnd: rows[j - 1].seq });
                prevTs = rows[j - 1].ts;
                i = j;
                continue;
            }
        }

        out.push({ kind: 'row', p, delta: prevTs == null ? null : p.ts - prevTs, bookmark: bookmarkMap.get(p.seq) });
        prevTs = p.ts;
        i++;
    }
    return out;
}

/// Packets near `selected` (±50 in tape order) correlated by subject or class.
export function buildRelatedView(selected: PacketRow | null, allRows: readonly PacketRow[], tape: PacketTape | null): Related[] {
    if (!selected) return [];
    const ai = tape?.indexOfSeq(selected.seq) ?? -1;
    if (ai < 0) return [];
    const out: Related[] = [];
    const span = 50;
    const lo = Math.max(0, ai - span), hi = Math.min(allRows.length, ai + span);
    for (let k = lo; k < hi; k++) {
        if (k === ai) continue;
        const p = allRows[k];
        const dt = p.ts - selected.ts;
        if (selected.subjectLabel && p.subjectLabel === selected.subjectLabel) {
            out.push({ row: p, dt, reason: 'Same subject' });
        } else if (p.className === selected.className && Math.abs(dt) < 5000) {
            out.push({ row: p, dt, reason: 'Same class' });
        }
    }
    return out;
}

export type CompiledBreakpoint = Breakpoint & { matcher: ((row: ParsedRow) => boolean) | null };

export function compileBreakpoints(breakpoints: Breakpoint[]): CompiledBreakpoint[] {
    return breakpoints.map(bp => ({
        ...bp,
        matcher: bp.enabled ? parseQuery(bp.match).match : null,
    }));
}

export function computeBreakpointMatches(
    compiled: CompiledBreakpoint[],
    allRows: readonly PacketRow[],
    bookmarkMap: Map<number, Bookmark>,
): Breakpoint[] {
    return compiled.map(bp => {
        const { matcher, ...plain } = bp;
        if (!matcher) return { ...plain, matchedSeqs: [], hitCount: plain.hitCount ?? 0 };
        const matched: number[] = [];
        let hitCount = 0;
        for (const p of allRows) {
            if (!matcher(prepareParsedRow(p, bookmarkMap))) continue;
            hitCount++;
            if (matched.length < 6) matched.push(p.seq);
        }
        return { ...plain, matchedSeqs: matched, hitCount };
    });
}
