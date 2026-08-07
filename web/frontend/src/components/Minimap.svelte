<script lang="ts">
    import { MinimapCore } from '../lib/minimap.ts';

    let { uuid, player, paused = false } = $props();

    let host;
    let core = null;

    $effect(() => {
        uuid;
        core = new MinimapCore(host, uuid);
        core.boot();
        return () => core?.destroy();
    });

    $effect(() => { core?.setPaused(paused); });
    $effect(() => { core?.updatePlayer(player); });
</script>

<div bind:this={host}></div>

<style>
    @layer components {
        :global {
    /* ---- Minimap ---- */
    /* === Host ============================================================================ */
    .mm-host {
        display: flex; flex-direction: column;
        overflow: hidden; position: relative;
        height: 100%; min-height: 320px;
        > header.mm-header {
            display: grid; grid-template-columns: auto 1fr auto;
            align-items: center; gap: var(--pad-3);
            padding: var(--pad-3) var(--pad-4);
            border-bottom: 1px solid var(--line);
            h2 { font-size: var(--t-sm); text-transform: uppercase; }
            .meta {
                font-size: var(--t-xs); color: var(--ink-3);
                text-transform: uppercase;
                white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
            }
        }
    }
    
    .mm-header-actions {
        display: inline-flex; gap: 4px;
        .icon {
            padding: 0; width: 26px; min-width: 26px; height: 26px; min-height: 26px;
            &.is-on { color: var(--acc); border-color: var(--acc-line); background: var(--acc-soft); }
        }
    }
    
    /* === Disabled CTA ==================================================================== */
    .mm-disabled {
        display: grid; gap: var(--pad-4);
        padding: var(--pad-5) var(--pad-4) var(--pad-4);
        grid-template-columns: 96px 1fr; align-items: center;
        > .primary { grid-column: 1 / -1; }
    }
    .mm-disabled-art { width: 96px; height: 96px; background: var(--sunk); box-shadow: var(--bevel-sunk); padding: 8px; }
    .mm-disabled-body { color: var(--ink-3); font-size: var(--t-sm); line-height: 1.25; }
    .mm-disabled-stats {
        grid-column: 1 / -1; margin: 0; padding: 0; list-style: none;
        display: grid; gap: 4px; font-size: var(--t-xs);
        li {
            display: grid; grid-template-columns: 1fr auto;
            padding: 6px 0; border-top: 1px solid var(--line);
            color: var(--ink-3); text-transform: uppercase;
            &:first-child { border-top: 0; }
        }
        code {
            color: var(--ink); background: transparent;
            padding: 0; font-size: var(--t-xs);
            text-transform: none;
        }
    }
    
    /* === Stage (map + toolbar) =========================================================== */
    .mm-body {
        flex: 1 1 0; min-height: 0;
        display: grid; grid-template-columns: 1fr auto;
        gap: var(--pad-2); padding: var(--pad-3);
        background: var(--sunk);
    }
    
    /* Min-height keeps the canvas readable in a small overview column. */
    .mm-stage { position: relative; width: 100%; height: 100%; min-height: 280px; }
    
    .mm-viewport {
        position: absolute; inset: 0; overflow: hidden;
        background: var(--mm-deep); border: 1px solid #111;
        box-shadow: inset 0 0 0 1px rgba(0, 0, 0, .6), inset 0 0 0 2px rgba(255, 255, 255, .06);
        touch-action: none; user-select: none;
        cursor: grab; image-rendering: pixelated;
        &:active { cursor: grabbing; }
    }
    
    .mm-canvas {
        position: absolute; inset: 0;
        width: 100%; height: 100%; display: block;
        image-rendering: pixelated;
    }
    .mm-overlay { pointer-events: none; }
    .mm-markers {
        position: absolute; inset: 0; pointer-events: none;
        .mm-wp { pointer-events: auto; }
    }
    
    /* === Player marker ==================================================================== */
    /* Entity markers are painted on .mm-overlay; see minimap.ts drawEntities(). */
    .mm-player {
        position: absolute; left: 50%; top: 50%;
        pointer-events: none;
        z-index: 5; image-rendering: pixelated;
    }

    /* === Waypoint markers ================================================================ */
    .mm-wp {
        position: absolute; left: 50%; top: 50%;
        cursor: pointer; z-index: 4;
        &:hover { z-index: 7; }
        &:hover .mm-wp-label { opacity: 1; transform: translateX(0); }
    }
    
    .mm-wp-dot {
        display: grid; place-items: center;
        width: 14px; height: 14px;
        color: #fff; background: var(--wp-c);
        font-size: var(--t-xs);
        transform: translate(-50%, -50%);
        box-shadow: 0 0 0 1px #000, inset 0 0 0 1px rgba(255, 255, 255, .55);
        animation: mmwp-pop 240ms ease-out;
        image-rendering: pixelated;
    }
    @keyframes mmwp-pop {
        from { transform: translate(-50%, -50%) scale(0.4); opacity: 0; }
        to   { transform: translate(-50%, -50%) scale(1);   opacity: 1; }
    }
    
    .mm-wp-label {
        position: absolute; left: 12px; top: -5px;
        color: #fff; background: var(--mm-scrim);
        padding: 1px 5px; font-size: var(--t-xs);
        text-shadow: 1px 1px 0 rgba(0, 0, 0, .6);
        pointer-events: none; white-space: nowrap;
        opacity: 0; transform: translateX(-4px);
        transition: opacity 80ms ease-out, transform 80ms ease-out;
        z-index: 6;
        i { font-style: normal; margin-left: 6px; color: color-mix(in oklab, var(--wp-c) 70%, #fff); }
    }
    
    /* === Cardinal letters + in-map readouts ============================================== */
    .mm-cardinals { position: absolute; inset: 0; pointer-events: none; }
    
    .mm-cardinal {
        position: absolute; left: 50%; top: 50%;
        color: var(--ink-2); font-size: var(--t-xs);
        background: var(--mm-scrim); padding: 1px 4px;
        &.is-n { color: var(--acc); background: var(--mm-scrim-2); }
    }
    
    .mm-coords-inset {
        position: absolute; left: 6px; bottom: 6px;
        background: var(--mm-scrim); padding: 2px 6px;
        font-size: var(--t-xs);
        color: #fff; pointer-events: none;
        .acc { color: var(--acc); }
    }
    
    .mm-scale {
        position: absolute; right: 6px; bottom: 6px;
        display: inline-flex; align-items: center; gap: 6px;
        padding: 2px 5px; background: var(--mm-scrim);
        color: #fff; font-size: var(--t-xs);
        pointer-events: none;
    }
    
    .mm-scale-bar {
        display: inline-flex; height: 6px;
        border-top: 1px solid #fff;
        i {
            display: block; flex: 1; height: 100%;
            &:nth-child(odd)  { background: var(--mm-scrim-2); }
            &:nth-child(even) { background: rgba(255, 255, 255, .85); }
        }
    }
    
    /* === Toolbar (right of the map) ====================================================== */
    .mm-toolbar {
        display: flex; flex-direction: column; gap: 4px;
        padding: 6px 4px;
        background: var(--bg-2); border: 1px solid var(--line);
        box-shadow: var(--bevel-sunk); align-self: center;
    }
    
    .mm-tool {
        width: 28px; height: 28px;
        display: grid; place-items: center; padding: 0;
        background: var(--bg-1); border: 1px solid var(--line);
        box-shadow: var(--bevel);
        color: var(--ink-2); font-size: var(--t-md);
        cursor: pointer; text-transform: none;
        &:hover { color: var(--ink); border-color: var(--acc-line); }
        &.is-on { color: var(--acc); border-color: var(--acc-line); background: var(--acc-soft); }
    }
    
    .mm-tool-sep { display: block; height: 1px; background: var(--line); margin: 4px 2px; }
    
    .mm-zoom-track {
        width: 4px; height: 60px;
        background: var(--sunk); box-shadow: var(--bevel-sunk);
        margin: 0 auto; position: relative;
    }
    
    .mm-zoom-bar {
        position: absolute; left: 0; right: 0; top: 0;
        background: var(--acc); transition: height 80ms;
    }
    
    /* === Filter chips ==================================================================== */
    .mm-filters {
        display: flex; flex-wrap: wrap;
        padding: var(--pad-2) var(--pad-3);
        background: var(--bg-1);
        border-top: 1px solid var(--line); border-bottom: 1px solid var(--line);
    }
    
    .mm-chip {
        display: inline-flex; align-items: center; gap: 6px;
        padding: 4px 10px;
        background: var(--bg-2); border: 1px solid var(--line);
        color: var(--ink-3); font-size: var(--t-xs);
     text-transform: uppercase;
        margin-left: -1px; cursor: pointer;
        text-decoration: line-through; text-decoration-color: var(--ink-4);
        &:first-child { margin-left: 0; }
        &.is-on {
            color: var(--ink);
            background: color-mix(in oklab, var(--chip-c) 12%, var(--bg-2));
            border-color: color-mix(in oklab, var(--chip-c) 45%, var(--line));
            text-decoration: none;
        }
        &:not(.is-on) .mm-chip-glyph,
        &:not(.is-on) .mm-chip-count { color: var(--ink-4); }
    }
    .mm-chip-glyph { color: var(--chip-c); font-size: 12px; }
    .mm-chip-count { font-variant-numeric: tabular-nums; color: var(--chip-c); }
    
    /* === Waypoints list ================================================================== */
    .mm-waypoints { padding: var(--pad-2) var(--pad-3); }
    
    .mm-wp-head {
        display: flex; align-items: center; justify-content: space-between;
        padding: 4px 0;
        h3 { font-size: var(--t-xs); text-transform: uppercase; color: var(--ink-2); }
    }
    
    .mm-wp-list {
        list-style: none; padding: 0; margin: 0;
        display: grid; gap: 2px;
        max-height: 220px;
    }
    
    .mm-wp-item {
        display: grid; grid-template-columns: 22px 1fr auto 18px;
        align-items: center; gap: var(--pad-2);
        padding: 4px 6px;
        background: var(--sunk); box-shadow: var(--bevel-sunk);
        cursor: pointer; font-size: var(--t-sm);
        transition: background var(--motion), outline var(--motion);
        &:hover {
            background: var(--bg-2); outline: 1px solid var(--acc-line);
            .mm-wp-x { opacity: 1; }
        }
    }
    
    .mm-wp-color {
        width: 18px; height: 18px;
        display: grid; place-items: center;
        color: #fff; text-shadow: 1px 1px 0 rgba(0, 0, 0, .5);
        font-size: 12px;
    }
    
    .mm-wp-text { display: grid; gap: 1px; min-width: 0; }
    .mm-wp-name { color: var(--ink); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mm-wp-meta { font-size: var(--t-xs); color: var(--ink-4); font-variant-numeric: tabular-nums; }
    
    .mm-wp-bearing {
        text-align: right; font-size: var(--t-xs);
        display: grid; gap: 1px; min-width: 38px;
        b { font-weight: 400; color: var(--ink); }
        i { font-style: normal; color: var(--acc); font-variant-numeric: tabular-nums; }
    }
    
    .mm-wp-x {
        width: 18px; height: 18px; padding: 0;
        background: transparent; border: 1px solid transparent;
        color: var(--ink-4); font-size: var(--t-xs);
        cursor: pointer; line-height: 1;
        opacity: 0; transition: opacity var(--motion);
        &:hover { color: var(--danger); border-color: color-mix(in oklab, var(--danger) 40%, var(--line)); }
    }
    
    /* === Context menu ==================================================================== */
    .mm-ctx {
        position: fixed; z-index: 80; min-width: 200px;
        background: var(--bg-1); border: 1px solid var(--acc-line);
        box-shadow: var(--bevel), var(--float-2);
        padding: 4px 0; transform: translate(0, 6px);
        button {
            width: 100%;
            display: grid; grid-template-columns: 20px 1fr;
            gap: 6px; align-items: center; text-align: left;
            background: transparent; border: 0;
            padding: 6px 10px; box-shadow: none;
            color: var(--ink-2); font-size: var(--t-sm);
            text-transform: none; cursor: pointer;
            &:hover { background: var(--acc-soft); color: var(--ink); }
            > span:first-child { color: var(--acc); text-align: center; }
    
            &.danger {
                color: var(--danger);
                &:hover { background: color-mix(in oklab, var(--danger) 14%, transparent); color: var(--danger); }
                > span:first-child { color: var(--danger); }
            }
        }
    }
    
    .mm-ctx-head {
        padding: 6px 10px; border-bottom: 1px solid var(--line);
        font-size: var(--t-xs); color: var(--ink);
        font-variant-numeric: tabular-nums;
    }
    
    /* === Waypoint dialog ================================================================= */
    .mm-modal {
        width: min(440px, calc(100vw - 32px));
        background: var(--bg-1); border: 1px solid var(--line);
        box-shadow: var(--bevel), 0 16px 40px rgba(0, 0, 0, .6);
        > header {
            display: flex; align-items: center; justify-content: space-between;
            padding: var(--pad-3) var(--pad-4);
            border-bottom: 1px solid var(--line);
            h2 { font-size: var(--t-sm); text-transform: uppercase; color: var(--acc); }
        }
        > footer {
            display: flex; justify-content: flex-end; gap: var(--pad-2);
            padding: var(--pad-3) var(--pad-4);
            border-top: 1px solid var(--line);
        }
    }
    
    .mm-modal-body {
        padding: var(--pad-4);
        display: grid; gap: var(--pad-3);
        .field {
            display: grid; gap: 6px;
            > span { font-size: var(--t-xs); color: var(--ink-3); text-transform: uppercase; }
        }
        .field-row { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: var(--pad-3); }
    }
    
    .mm-color-swatches { display: flex; gap: 6px; }
    
    .mm-sw {
        width: 28px; height: 28px; padding: 0;
        box-shadow: var(--bevel); border: 1px solid var(--line); cursor: pointer;
        &.is-on { outline: 2px solid var(--ink); outline-offset: 2px; }
    }
    
    .mm-icon-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 4px; }
    
    .mm-ig {
        aspect-ratio: 1; padding: 0;
        background: var(--bg-2); border: 1px solid var(--line);
        box-shadow: var(--bevel);
        color: var(--ink-2); font-size: var(--t-md);
        text-transform: none; cursor: pointer;
        &.is-on { color: var(--bg-0); background: var(--acc); border-color: var(--acc-deep); }
    }
    
    /* === Fullscreen mode — exit via the header ⛶ button or Escape. ====================== */
    .mm-root.is-fullscreen {
        position: fixed; z-index: 90; inset: 60px 24px 24px 24px;
        width: auto; min-width: 0;
        background: var(--bg-1);
        box-shadow: var(--bevel), 0 24px 60px rgba(0, 0, 0, .7);
        overflow: auto;
        .mm-stage { width: 100%; height: 100%; }
    }
        }
    }
</style>
