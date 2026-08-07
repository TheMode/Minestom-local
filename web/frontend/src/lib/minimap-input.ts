// Pointer gesture state machine for the minimap viewport: drag-pan, pinch-zoom + twist,
// double-tap waypoint, long-press / right-click context menu, and hover hit-testing.
// Extracted from MinimapCore so the core stays a state container + render orchestrator.

import { toast } from '../state/toasts.svelte.ts';
import type { MinimapCore } from './minimap.ts';

const DBL_TAP_MS = 340;
const LONG_PRESS_MS = 500;
const DRAG_THRESHOLD_PX = 5;

function entityLabel(type: string | undefined): string {
    if (!type) return 'Entity';
    const name = type.startsWith('minecraft:') ? type.slice('minecraft:'.length) : type;
    return name.split('_').map(s => s ? s[0].toUpperCase() + s.slice(1) : s).join(' ');
}

/// Wire the full pointer/wheel gesture set onto `core`'s viewport element. Mutates the
/// core's camera state (pan/zoom/twist) and drives renders through its public methods.
export function bindViewport(core: MinimapCore) {
    const v = core._els!.viewport;
    const ptrs = new Map<number, { x: number; y: number; sx: number; sy: number }>();
    let longPress = 0, lastTap = { t: 0, x: 0, y: 0 }, moved = false;
    let pinch: { d: number; zoom: number; angle: number } | null = null;
    const rel = (ev: PointerEvent) => { const r = v.getBoundingClientRect(); return { x: ev.clientX - r.left, y: ev.clientY - r.top }; };
    const cancelLong = () => { clearTimeout(longPress); longPress = 0; };

    v.addEventListener('pointerdown', ev => {
        ev.preventDefault(); v.setPointerCapture(ev.pointerId);
        const p = rel(ev);
        ptrs.set(ev.pointerId, { x: p.x, y: p.y, sx: p.x, sy: p.y });
        moved = false; cancelLong();
        if (ptrs.size === 1) {
            const cx = ev.clientX, cy = ev.clientY;
            longPress = setTimeout(() => { if (!moved && ptrs.size === 1) core.openContextMenu(cx, cy, core.viewportToWorld(p.x, p.y)); }, LONG_PRESS_MS);
        } else if (ptrs.size === 2) {
            const [a, b] = [...ptrs.values()];
            pinch = { d: Math.hypot(a.x - b.x, a.y - b.y), zoom: core.zoom, angle: Math.atan2(b.y - a.y, b.x - a.x) };
        }
    });
    v.addEventListener('pointermove', ev => {
        const rec = ptrs.get(ev.pointerId);
        if (!rec) {
            // No active drag — run entity hover hit-test. Touch devices skip (no hover).
            if (ev.pointerType !== 'touch') core._handleHover(ev);
            return;
        }
        const p = rel(ev);
        const dx = p.x - rec.x, dy = p.y - rec.y;
        rec.x = p.x; rec.y = p.y;
        if (ptrs.size === 1) {
            if (Math.hypot(p.x - rec.sx, p.y - rec.sy) > DRAG_THRESHOLD_PX) {
                moved = true; cancelLong();
                core._clearHover();
                core.startManualPan();
                const cam = core.buildCamera(v.clientWidth, v.clientHeight);
                const [dwx, dwz] = cam.screenPanDelta(dx, dy);
                core.panX += dwx; core.panZ += dwz;
                core.requestRender();
            }
        } else if (ptrs.size === 2 && pinch) {
            const [a, b] = [...ptrs.values()];
            const nd = Math.hypot(a.x - b.x, a.y - b.y);
            const nAng = Math.atan2(b.y - a.y, b.x - a.x);
            core.twist += (nAng - pinch.angle);
            pinch.angle = nAng;
            core.setZoom(pinch.zoom * (pinch.d / nd));
            moved = true;
        }
    });
    v.addEventListener('pointerleave', () => core._clearHover());
    const up = (ev: PointerEvent) => {
        cancelLong();
        const rec = ptrs.get(ev.pointerId);
        ptrs.delete(ev.pointerId);
        if (ptrs.size < 2) pinch = null;
        try { v.releasePointerCapture(ev.pointerId); } catch {}
        if (!rec || moved) return;
        // Single-tap on an entity → toast. Falls through to waypoint dbl-tap otherwise.
        const ent = core._entityHitAt(rec.x, rec.y);
        if (ent) {
            toast(`${entityLabel(ent.type)} · ${Math.round(ent.x)} ${Math.round(ent.y)} ${Math.round(ent.z)}`);
            lastTap = { t: 0, x: 0, y: 0 };
            return;
        }
        const now = performance.now();
        if (now - lastTap.t < DBL_TAP_MS && Math.abs(rec.x - lastTap.x) < 14 && Math.abs(rec.y - lastTap.y) < 14) {
            core.openWaypointDraft(core.viewportToWorld(rec.x, rec.y));
            lastTap = { t: 0, x: 0, y: 0 };
        } else { lastTap = { t: now, x: rec.x, y: rec.y }; }
    };
    v.addEventListener('pointerup', up);
    v.addEventListener('pointercancel', up);
    v.addEventListener('wheel', ev => { ev.preventDefault(); core.setZoom(core.zoom * (ev.deltaY > 0 ? 1.15 : 1 / 1.15)); }, { passive: false });
    v.addEventListener('contextmenu', ev => { ev.preventDefault(); const p = rel(ev); core.openContextMenu(ev.clientX, ev.clientY, core.viewportToWorld(p.x, p.y)); });
}
