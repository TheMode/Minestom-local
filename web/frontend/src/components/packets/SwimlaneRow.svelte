<script lang="ts">
    import { BUCKETS } from '../../lib/packetAgg.ts';
    import { humanBytes, humanNumber } from '../../lib/util.ts';

    let { player, lane, gmax, onclick } = $props();
</script>

<div
    class="swimlane data-row data-row--panel data-row--interactive"
    {onclick}
    onkeydown={ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); onclick(); } }}
    role="button"
    tabindex="0"
>
    <span class="siderail__avatar avatar-sm">
        {(player.username || '?').slice(0, 2).toUpperCase()}
    </span>
    <span class="swimlane__name">
        {player.username || player.uuid.slice(0, 8)}
        <span class="dim">{(player.dimension || '').replace('minecraft:', '')} · {player.gamemode || '—'}</span>
    </span>
    <span class="swimlane__track">
        <svg viewBox="0 0 {BUCKETS} 100" preserveAspectRatio="none">
            {#each Array.from(lane.buckets) as v, i (i)}
                {#if v}
                    {@const cb = lane.cb[i] || 0}
                    {@const sb = v - cb}
                    {@const cbH = Math.max(2, (cb / gmax) * 92)}
                    {@const sbH = Math.max(0, (sb / gmax) * 92)}
                    <g>
                        {#if cbH > 0}
                            <rect x={i + 0.05} y={100 - cbH} width="0.9" height={cbH} fill="var(--dir-cb)" opacity="0.85" />
                        {/if}
                        {#if sbH > 0}
                            <rect x={i + 0.05} y={100 - cbH - sbH} width="0.9" height={sbH} fill="var(--dir-sb)" opacity="0.85" />
                        {/if}
                    </g>
                {/if}
            {/each}
        </svg>
    </span>
    <div class="swimlane__metrics">
        <div class="swimlane__metric"><span class="lbl">pkt</span><span class="val">{humanNumber(lane.count)}</span></div>
        <div class="swimlane__metric"><span class="lbl dir-cb">↓ in</span><span class="val">{humanBytes(lane.cbBytes)}</span></div>
        <div class="swimlane__metric"><span class="lbl dir-sb">↑ out</span><span class="val">{humanBytes(lane.sbBytes)}</span></div>
    </div>
</div>

<style>
    @layer pages {
        :global {
    .swimlane {
        display: grid;
        grid-template-columns: 22px minmax(80px, 1fr) minmax(140px, 2fr) auto;
        align-items: center;
        gap: var(--pad-3);

        .swimlane__name {
            display: grid;
            gap: 2px;
            color: var(--ink);
            .dim {
                font-size: var(--t-xs);
                color: var(--ink-4);
                text-transform: uppercase;
            }
        }
        .swimlane__track {
            display: block;
            height: 28px;
            background: var(--sunk);
            box-shadow: var(--bevel-sunk);
            min-width: 0;
            svg { width: 100%; height: 100%; display: block; }
        }
        .swimlane__metrics { display: grid; grid-auto-flow: column; gap: var(--pad-3); }
        .swimlane__metric {
            display: grid;
            grid-template-rows: auto auto;
            text-align: right;
            font-size: var(--t-xs);
            .lbl { color: var(--ink-4); text-transform: uppercase; }
            .val { color: var(--ink); font-variant-numeric: tabular-nums; }
        }
    }
        }
    }
</style>
