// HTML string builders for the imperative minimap shell, disabled state, and its two
// transient popovers (context menu + waypoint draft). Pure — no DOM side effects.

import { escapeHtml as escAttr } from './util.ts';

export const WP_COLORS = ['#3ec27a', '#e6c560', '#7b9cbf', '#b97aff', '#e57878', '#d97cff', '#ffffff'];
export const WP_ICONS = ['◇', '⌂', '★', '⛏', '◈', '$', '✦', '✚', '☠', '♣', '⚑', '○'];

export const DISABLED_HTML = (uuid: string) => `
    <header class="mm-header">
        <h2>Minimap <em class="acc">off</em></h2>
        <span class="meta">saves ~24 KB/s · ~3% CPU</span>
    </header>
    <div class="mm-disabled">
        <div class="mm-disabled-art" aria-hidden="true">
            <svg viewBox="0 0 16 16" width="100%" height="100%" shape-rendering="crispEdges">
                <rect width="16" height="16" fill="#3f76e4"/>
                <rect x="2" y="3" width="12" height="10" fill="#dbd3a0"/>
                <rect x="3" y="4" width="10" height="8" fill="#5e9c4d"/>
                <rect x="5" y="5" width="2" height="2" fill="#3a7036"/>
                <rect x="9" y="9" width="2" height="2" fill="#3a7036"/>
                <polygon points="8,6 10,11 8,9.5 6,11" fill="#fff" stroke="#000" stroke-width="0.4"/>
            </svg>
        </div>
        <p class="mm-disabled-body">
            Subscribes to pre-rasterized chunk tiles, player pose, and entity markers on one
            WebSocket topic (10 Hz). Optional — disabled by default when nobody is watching.
        </p>
        <ul class="mm-disabled-stats">
            <li><span>Bandwidth</span><span class="acc">~4–12 KB/s</span></li>
            <li><span>Cadence</span><span class="acc">10 Hz unified</span></li>
            <li><span>WS topic</span><code>player:${String(uuid || '').slice(0, 8)}:minimap</code></li>
        </ul>
        <button class="primary" data-act="enable">Enable minimap</button>
    </div>`;

export const SHELL_HTML = () => `
    <header class="mm-header">
        <h2>Minimap</h2>
        <span class="meta"><span class="dim" data-mm-coords>0 0 0</span></span>
        <div class="mm-header-actions">
            <button class="ghost sm icon" title="Recenter on player" data-act="recenter">⊕</button>
            <button class="ghost sm icon is-on" title="North-up · click to follow" data-act="north">N</button>
            <button class="ghost sm icon" title="Fullscreen" data-act="full">⛶</button>
            <button class="ghost sm icon" title="Disable" data-act="disable">✕</button>
        </div>
    </header>
    <div class="mm-body">
        <div class="mm-stage" data-shape="square" data-density="comfy">
            <div class="mm-viewport" data-mm-viewport>
                <canvas data-mm-canvas class="mm-canvas"></canvas>
                <canvas data-mm-overlay class="mm-canvas mm-overlay"></canvas>
                <div class="mm-markers" data-mm-markers></div>
                <div class="mm-cardinals" data-mm-cardinals></div>
                <div class="mm-player" data-mm-player>
                    <svg viewBox="0 0 12 12" width="14" height="14" shape-rendering="crispEdges" aria-hidden="true">
                        <polygon points="6,1 11,11 6,8 1,11" fill="#fff" stroke="#000" stroke-width="1"/>
                    </svg>
                </div>
                <div class="mm-coords-inset" data-mm-inset><span class="acc">0</span> <span>64</span> <span class="acc">0</span></div>
                <div class="mm-scale" data-mm-scale>
                    <div class="mm-scale-bar" style="width:60px"><i></i><i></i><i></i><i></i></div>
                    <span data-mm-scale-l>16m</span>
                </div>
            </div>
        </div>
        <div class="mm-toolbar" aria-label="Map controls">
            <button class="mm-tool" title="Zoom in" data-act="zin">＋</button>
            <div class="mm-zoom-track"><div class="mm-zoom-bar" data-mm-zoombar></div></div>
            <button class="mm-tool" title="Zoom out" data-act="zout">−</button>
            <span class="mm-tool-sep"></span>
            <button class="mm-tool" title="Chunk grid" data-act="grid">▦</button>
            <button class="mm-tool" title="Cardinal letters" data-act="card">N</button>
            <button class="mm-tool" title="Gestures help" data-act="help">?</button>
        </div>
    </div>
    <div class="mm-filters" role="group" aria-label="Entity filters" data-mm-filters></div>
    <div class="mm-waypoints" data-mm-wp>
        <div class="mm-wp-head">
            <h3>Waypoints <span class="dim small" data-mm-wp-count>·0</span></h3>
            <div class="row gap-sm"><button class="ghost sm" data-act="wp-here">＋ Here</button></div>
        </div>
        <ul class="mm-wp-list scroll-thin" data-mm-wp-list></ul>
    </div>`;

/// Context-menu popover body (coordinates header + actions). `worldXZ` is [x, z].
export const CONTEXT_MENU_HTML = (worldXZ: [number, number]) =>
    `<div class="mm-ctx-head"><b>${Math.round(worldXZ[0])}</b> <span class="dim">·</span> <b>${Math.round(worldXZ[1])}</b></div>
            <button data-a="add"><span>＋</span>Add waypoint here</button>
            <button data-a="copy"><span>⎘</span>Copy coordinates</button>`;

type WaypointDraft = { name: string; x: number; y: number; z: number; color: string; icon: string };

/// Waypoint-draft modal body. Pre-selects the draft's current color/icon swatches.
export const WAYPOINT_DRAFT_HTML = (draft: WaypointDraft) => `<div class="mm-modal">
            <header><h2>New waypoint</h2><button class="ghost sm icon" data-a="cancel">✕</button></header>
            <div class="mm-modal-body">
                <label class="field"><span>Name</span><input data-f="name" value="${escAttr(draft.name)}" autofocus/></label>
                <div class="field-row">
                    <label class="field"><span>X</span><input type="number" data-f="x" value="${draft.x}"/></label>
                    <label class="field"><span>Y</span><input type="number" data-f="y" value="${draft.y}"/></label>
                    <label class="field"><span>Z</span><input type="number" data-f="z" value="${draft.z}"/></label>
                </div>
                <label class="field"><span>Color</span><div class="mm-color-swatches">
                    ${WP_COLORS.map(c => `<button class="mm-sw ${c === draft.color ? 'is-on' : ''}" data-color="${c}" style="background:${c}"></button>`).join('')}
                </div></label>
                <label class="field"><span>Icon</span><div class="mm-icon-grid">
                    ${WP_ICONS.map(i => `<button class="mm-ig ${i === draft.icon ? 'is-on' : ''}" data-icon="${i}">${i}</button>`).join('')}
                </div></label>
            </div>
            <footer>
                <button class="ghost" data-a="cancel">Cancel</button>
                <button class="primary" data-a="save">Save waypoint</button>
            </footer>
        </div>`;
