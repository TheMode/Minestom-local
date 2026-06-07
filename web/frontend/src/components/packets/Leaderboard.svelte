<script lang="ts">
    import { pktLabel } from '../../lib/packetAgg.ts';
    import { humanBytes, humanNumber } from '../../lib/util.ts';
    import ProgressBar from '../ui/ProgressBar.svelte';

    function splitUnit(s: string): [string, string] {
        const m = String(s ?? '').match(/^([\d.,]+)\s*(\S*)$/);
        return m ? [m[1]!, m[2]!] : [String(s ?? ''), ''];
    }

    let { agg, sortBy, max = 14, version = 0 } = $props();

    const rows = $derived.by(() => {
        version; sortBy; max;
        const entries = [...agg.byClass.entries()];
        entries.sort((a, b) => sortBy === 'bytes' ? b[1].bytes - a[1].bytes : b[1].count - a[1].count);
        const top = entries.slice(0, max);
        const peak = sortBy === 'bytes' ? (top[0]?.[1]?.bytes || 1) : (top[0]?.[1]?.count || 1);
        return top.map(([cls, info]) => ({
            cls, info,
            pct: ((sortBy === 'bytes' ? info.bytes : info.count) / peak) * 100,
        }));
    });
</script>

{#if rows.length === 0}
    <div class="empty">No packets yet.</div>
{:else}
    <div class="leaderboard">
        {#each rows as r, i (r.cls)}
            {@const dirChip = r.info.cb > r.info.sb ? 'cb' : 'sb'}
            {@const dirGlyph = dirChip === 'cb' ? '↓' : '↑'}
            {@const [pN, pU] = splitUnit(sortBy === 'bytes' ? humanBytes(r.info.bytes) : humanNumber(r.info.count))}
            {@const [sN, sU] = splitUnit(sortBy === 'bytes' ? humanNumber(r.info.count) : humanBytes(r.info.bytes))}
            <div class="leaderboard__row data-row data-row--panel">
                <span class="leaderboard__rank">{i + 1}</span>
                <span class="leaderboard__cls data-row__truncate">{pktLabel(r.cls)}</span>
                <ProgressBar value={r.pct / 100} />
                <span class="num-unit leaderboard__num">
                    <span class="num-unit__n">{pN}</span><span class="num-unit__u">{pU}</span>
                </span>
                <span class={'leaderboard__chip ' + dirChip}>
                    <span class="leaderboard__chip-dir">{dirGlyph}</span>
                    <span class="num-unit">
                        <span class="num-unit__n">{sN}</span><span class="num-unit__u">{sU}</span>
                    </span>
                </span>
            </div>
        {/each}
    </div>
{/if}

<style>
    @layer pages {
        :global {
    .leaderboard {
        display: grid;
        gap: 1px;
        background: var(--line);
        max-height: 520px;
        overflow: auto;

        /* Fixed columns so the bar-wrap track starts at the same X across every row. */
        .leaderboard__row {
            display: grid;
            grid-template-columns: 24px minmax(90px, 1fr) minmax(60px, 1.6fr) 60px 72px;
            align-items: center;
            gap: var(--pad-2);
        }
        .leaderboard__rank {
            color: var(--ink-4);
            font-variant-numeric: tabular-nums;
            font-size: var(--t-xs);
            text-align: right;
        }
        .leaderboard__cls {
            color: var(--ink);
        }
        .leaderboard__num { color: var(--ink); font-size: var(--t-xs); }
        .leaderboard__chip {
            display: inline-grid;
            grid-template-columns: 10px 1fr;
            column-gap: 4px;
            align-items: baseline;
            font-size: var(--t-xs);
            padding: 1px 0;
            color: var(--ink-4);
            text-transform: uppercase;
            white-space: nowrap;
            &.cb { color: var(--dir-cb); }
            &.sb { color: var(--dir-sb); }
        }
        .leaderboard__chip-dir { text-align: center; }
    }

    /* Two-column "value · unit" cell — digits and units land in fixed sub-columns. */
    .num-unit {
        display: inline-grid;
        grid-template-columns: 1fr 22px;
        column-gap: 4px;
        align-items: baseline;
        text-align: right;
        .num-unit__n { text-align: right; font-variant-numeric: tabular-nums; color: inherit; }
        .num-unit__u {
            text-align: left;
            color: var(--ink-4);
            font-size: var(--t-xs);
        }
    }
        }
    }
</style>
