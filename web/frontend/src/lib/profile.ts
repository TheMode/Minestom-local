// View-agnostic helpers + constant tables for the player Profile view and its panels.

import { getPath } from './statePatch.ts';

export const PING_SERIES = [{ key: 'ping', label: 'Ping', color: 'var(--acc)', area: true }];

export const HEART_SPRITES = {
    empty: '/assets/textures/gui/sprites/hud/heart/container.png',
    full:  '/assets/textures/gui/sprites/hud/heart/full.png',
    half:  '/assets/textures/gui/sprites/hud/heart/half.png',
    hcEmpty: '/assets/textures/gui/sprites/hud/heart/container_hardcore.png',
    hcFull:  '/assets/textures/gui/sprites/hud/heart/hardcore_full.png',
    hcHalf:  '/assets/textures/gui/sprites/hud/heart/hardcore_half.png',
};

export const FOOD_SPRITES = {
    empty: '/assets/textures/gui/sprites/hud/food_empty.png',
    full:  '/assets/textures/gui/sprites/hud/food_full.png',
    half:  '/assets/textures/gui/sprites/hud/food_half.png',
};

export const TABS = [
    { id: 'overview',   label: 'Overview' },
    { id: 'packets',    label: 'Packets',   live: true },
    { id: 'lifecycle',  label: 'Lifecycle' },
    { id: 'inventory',  label: 'Inventory' },
    { id: 'world',      label: 'World' },
    { id: 'entities',   label: 'Entities' },
    { id: 'registries', label: 'Registries' },
    { id: 'chat',       label: 'Chat' },
    { id: 'action',     label: 'Run action' },
];

/// `xpBar` is special-cased so the provenance tooltip shows the same `nn%` form as the
/// rendered value; everything else walks the JSON shape directly.
export function provenanceCurrentValue(p, field) {
    if (!p) return null;
    if (field === 'xpBar') return Math.round((p.xpBar ?? 0) * 100) + '%';
    return getPath(p, field) ?? null;
}

export const fmtClock = ts => ts ? new Date(ts).toLocaleTimeString('en-GB', { hour12: false }) : '';

export function provFor(p, field) { return p?.provenance?.[field] || null; }

/// Vanilla-style ordering: score descending, capped at the in-game limit of 15.
export function sidebarRows(rows: Record<string, any> | null | undefined) {
    return Object.entries(rows ?? {})
        .map(([key, r]) => ({ key, ...r }))
        .sort((a, b) => (b.score ?? 0) - (a.score ?? 0))
        .slice(0, 15);
}
