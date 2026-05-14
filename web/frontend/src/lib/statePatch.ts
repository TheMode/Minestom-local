import type { JsonObject, JsonValue } from './types.ts';

/// Wire shape of a `StatePatch` shipped on `player:{uuid}:state`. Mirrors
/// `WebCodecs.statePatchJson` on the backend — adding a key on one side without the
/// other is a bug.
///
/// **Path scheme.** Both `values` and `appends` are keyed by dotted JSON paths anchored at the
/// player object (`health`, `posX`, `hotbar.3`, `traffic.pingMs`,
/// `attributes.minecraft:movement_speed`). The same path appears in `player.provenance` so a
/// single generic walker resolves either value or source.
export type StatePatch = JsonObject & {
    seq: number;
    ts: number;
    values?: Record<string, JsonValue>;
    appends?: Record<string, { elements: JsonValue[]; max: number }>;
    provenance?: Record<string, JsonObject>;
};

/// Path-parse cache — patches reuse a small fixed set of keys (50-ish), so the LRU pressure
/// here is effectively zero and we save the `split('.')` allocation on every read.
const PARTS_CACHE = new Map<string, string[]>();
function parts(path: string): string[] {
    let p = PARTS_CACHE.get(path);
    if (p === undefined) PARTS_CACHE.set(path, p = path.split('.'));
    return p;
}

/// Resolve a dotted path against a player object. Returns `undefined` if any segment is
/// missing — useful for "show provenance value" and similar lazy reads.
export function getPath(obj: any, path: string): any {
    if (obj == null) return undefined;
    const segs = parts(path);
    let cursor = obj;
    for (let i = 0; i < segs.length; i++) {
        if (cursor == null) return undefined;
        cursor = cursor[segs[i]];
    }
    return cursor;
}

/// Walk `path` on `obj`, materialising intermediate objects, then assign `value` at the leaf.
/// `null` value deletes the leaf key (used by activeEffects removal and similar). Preserves
/// array containers: `hotbar.3 = item` writes `hotbar[3]` if `hotbar` is already an array,
/// rather than coercing it into an object with numeric keys (which would break
/// `Array.isArray` / `arr.map` downstream).
export function setPath(obj: any, path: string, value: any): void {
    if (obj == null) return;
    const segs = parts(path);
    const last = segs[segs.length - 1];
    let cursor = obj;
    for (let i = 0; i < segs.length - 1; i++) {
        const seg = segs[i];
        if (cursor[seg] == null || typeof cursor[seg] !== 'object') cursor[seg] = {};
        cursor = cursor[seg];
    }
    if (value === null) {
        if (Array.isArray(cursor)) cursor[Number(last)] = null;
        else delete cursor[last];
    } else {
        cursor[Array.isArray(cursor) ? Number(last) : last] = value;
    }
}

/// Apply one [StatePatch] in place on a player object. Each `values` entry overwrites the
/// targeted path; each `appends` entry pushes batched elements onto a bounded array (mirrors
/// the backend's `PlayerState#append`); `provenance` entries refresh the source-of-truth map.
///
/// Returns `player` for fluent chaining (`player = applyPatch(player, msg)`).
export function applyPatch<T extends Record<string, any>>(player: T, patch: StatePatch): T {
    if (!player || !patch) return player;
    if (patch.values) {
        for (const [path, value] of Object.entries(patch.values)) setPath(player, path, value);
    }
    if (patch.appends) {
        for (const [path, op] of Object.entries(patch.appends)) {
            const existing = getPath(player, path);
            const arr = Array.isArray(existing) ? existing : [];
            for (const el of op.elements) arr.push(el);
            while (arr.length > op.max) arr.shift();
            setPath(player, path, arr);
        }
    }
    if (patch.provenance) {
        const prov = (player as any).provenance ?? ((player as any).provenance = {});
        for (const [path, src] of Object.entries(patch.provenance)) prov[path] = src;
    }
    return player;
}
