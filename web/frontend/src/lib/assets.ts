// Vanilla asset registry — lazy-loaded sets of known item / effect / block ids.

type AssetState = {
    items: Set<string> | null;
    effects: Set<string> | null;
    blocks: Set<string> | null;
    ready: Promise<AssetState> | null;
};

const STATE: AssetState = { items: null, effects: null, blocks: null, ready: null };

async function loadList(path: string): Promise<Set<string>> {
    try {
        const r = await fetch('/assets/' + path);
        if (!r.ok) return new Set();
        return new Set(await r.json());
    } catch { return new Set(); }
}

export function ready(): Promise<AssetState> {
    if (!STATE.ready) {
        STATE.ready = (async () => {
            const [items, effects, blocks] = await Promise.all([
                loadList('items.json'),
                loadList('effects.json'),
                loadList('blocks.json'),
            ]);
            STATE.items = items;
            STATE.effects = effects;
            STATE.blocks = blocks;
            return STATE;
        })();
    }
    return STATE.ready;
}

export function effectUrl(idOrName: unknown): string | null {
    if (!STATE.effects) return null;
    const name = String(idOrName || '').replace(/^minecraft:/, '');
    return STATE.effects.has(name) ? `/assets/textures/mob_effect/${name}.png` : null;
}

export function prettifyId(id: unknown): string {
    return String(id || '').replace(/^minecraft:/, '').split('_')
        .map(w => (w[0] || '').toUpperCase() + w.slice(1))
        .join(' ');
}

/// Like [prettifyId] but falls back to `?` for empty input — for entity-type labels that
/// must always show something.
export function prettifyType(type: unknown): string {
    if (!type) return '?';
    return prettifyId(type);
}
