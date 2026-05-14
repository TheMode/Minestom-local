<script lang="ts">
    import { floatingTipStyle } from '../../lib/floatingPopover.svelte.ts';
    import { prettifyType } from '../../lib/assets.ts';
    import { entityTooltip } from '../../state/entityTooltip.svelte.ts';

    const W = 200;
    const H = 92;

    let style = $derived(entityTooltip.state
        ? floatingTipStyle(entityTooltip.state.x, entityTooltip.state.y, W, H)
        : '');
</script>

{#if entityTooltip.state}
    {@const e = entityTooltip.state.entity}
    <div class="ent-tip" style={style}>
        <div class="ent-tip__type">{prettifyType(e.type)}</div>
        <div class="ent-tip__row">
            <span class="lbl">id</span> <span class="v">#{e.id}</span>
            {#if e.uuid}<span class="dim"> · {String(e.uuid).slice(0, 8)}</span>{/if}
        </div>
        {#if e.group}
            <div class="ent-tip__row"><span class="lbl">group</span> <span class="v">{e.group}</span></div>
        {/if}
        <div class="ent-tip__row">
            <span class="lbl">pos</span>
            <span class="v mono">{Math.round(e.x)} {Math.round(e.y)} {Math.round(e.z)}</span>
        </div>
        {#if e.distance != null}
            <div class="ent-tip__row"><span class="lbl">dist</span> <span class="v">{Math.round(e.distance)}m</span></div>
        {/if}
    </div>
{/if}

<style>
    @layer pages {
        :global {
    /* Floating entity tooltip — shared between radar and minimap. */
    .ent-tip {
        position: fixed;
        z-index: 200;
        pointer-events: none;
        padding: 6px 8px;
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel), 0 6px 16px rgba(0, 0, 0, 0.45);
        color: var(--ink-2);
        font-size: var(--t-xs);
        line-height: 1;
        min-width: 140px;
        max-width: 240px;
        .ent-tip__type { color: var(--ink); font-size: var(--t-sm); margin-bottom: 4px; }
        .ent-tip__row {
            display: flex;
            gap: 6px;
            align-items: baseline;
            color: var(--ink-3);
            .lbl { color: var(--ink-4); text-transform: uppercase; }
            .v {
                color: var(--ink);
                font-variant-numeric: tabular-nums;
            }
            .dim { color: var(--ink-4); }
        }
    }
        }
    }
</style>
