<script lang="ts">
    import { humanBytes, humanNumber } from '../../lib/util.ts';
    import { SUBJECTS } from '../../lib/packetAgg.ts';

    let { agg, sortBy, version = 0 } = $props();

    const grid = $derived.by(() => {
        version; sortBy;
        let max = 1;
        for (const v of agg.byHeatmap.values()) {
            const val = sortBy === 'bytes' ? v.bytes : v.count;
            if (val > max) max = val;
        }
        const row = (dir: string) => SUBJECTS.map(s => {
            const v = agg.byHeatmap.get(dir + '|' + s) || { count: 0, bytes: 0 };
            const val = sortBy === 'bytes' ? v.bytes : v.count;
            return { s, val, pct: Math.min(1, val / max) };
        });
        return { cb: row('cb'), sb: row('sb') };
    });

    const fmt = (v: number) => v ? (sortBy === 'bytes' ? humanBytes(v) : humanNumber(v)) : '·';
</script>

<div class="heatmap">
    <span></span>
    {#each SUBJECTS as s (s)}<span class="heatmap__hdr">{s}</span>{/each}
    <span class="heatmap__row-hdr">↓ Inbound</span>
    {#each grid.cb as c (c.s)}
        <span class="heatmap__cell">
            <span class="fill" style:--heat={c.pct}></span>
            <span class="v">{fmt(c.val)}</span>
        </span>
    {/each}
    <span class="heatmap__row-hdr">↑ Outbound</span>
    {#each grid.sb as c (c.s)}
        <span class="heatmap__cell sb">
            <span class="fill" style:--heat={c.pct}></span>
            <span class="v">{fmt(c.val)}</span>
        </span>
    {/each}
</div>

<style>
    @layer pages {
        :global {
    .heatmap {
        display: grid;
        grid-template-columns: minmax(80px, auto) repeat(7, minmax(48px, 1fr));
        gap: 1px;
        background: var(--line);
        padding: 1px;
        .heatmap__hdr, .heatmap__row-hdr {
            padding: 6px 8px;
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
            text-align: center;
            background: var(--bg-1);
        }
        .heatmap__row-hdr { text-align: left; }
        .heatmap__cell {
            position: relative;
            padding: 6px 8px;
            background: var(--bg-1);
            text-align: center;
            font-variant-numeric: tabular-nums;
            font-size: var(--t-xs);
            color: var(--ink-2);
            overflow: hidden;
            .fill {
                position: absolute;
                inset: 0;
                background: var(--dir-cb);
                opacity: calc(0.1 + var(--heat, 0) * 0.65);
                z-index: 0;
            }
            &.sb .fill { background: var(--dir-sb); }
            .v { position: relative; z-index: 1; }
        }
    }
        }
    }
</style>
