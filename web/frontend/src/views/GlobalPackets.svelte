<script lang="ts">
    import { aggView, pktLabel, RATE_WINDOW } from '../lib/packetAgg.ts';
    import { navigate } from '../lib/nav.ts';
    import { players as playersStore } from '../state/players.svelte.ts';
    import { useGlobalPacketAggregate } from '../state/packetAggregate.svelte.ts';
    import { humanBytes, humanNumber, fmtAge } from '../lib/util.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import SwimlaneRow from '../components/packets/SwimlaneRow.svelte';
    import PacketAggregatePanels from '../components/packets/PacketAggregatePanels.svelte';

    let sortBy = $state('count');
    const players = $derived(playersStore.list);
    const feed = useGlobalPacketAggregate({ anomalies: true });
    const view = $derived.by(() => { feed.version; return aggView(feed.agg, players); });
</script>

{#snippet pkCrumb()}Packets{/snippet}
{#snippet globalCrumb()}Global{/snippet}
{#snippet title()}Global <em>packet analysis</em>{/snippet}
{#snippet subtitle()}
    <p class="dim small mt-xs">
        Aggregate across <em>{players.length}</em> sessions · {RATE_WINDOW}s rate window · click a swimlane for detail
    </p>
{/snippet}
{#snippet actions()}
    <button class="ghost" onclick={() => navigate(players[0] ? '/p/' + players[0].uuid + '/packets' : '/players')}>↗ Open per-player stream</button>
{/snippet}

<ViewHead crumbs={[pkCrumb, globalCrumb]} {title} {subtitle} {actions} />

<div class="gp-hero">
    <div class="gp-hero__cell lead">
        <div class="gp-hero__lbl">Throughput</div>
        <div class="gp-hero__val">{humanBytes(view.bps)}<span class="unit">/s</span></div>
        <div class="gp-hero__sub">{humanNumber(Math.round(view.pps))} pkt/s · {humanBytes(view.totalBytes)} in view</div>
        <div class="gp-hero__bar" title={`${view.cbPct.toFixed(0)}% server→client`}>
            <div class="gp-hero__bar-fill cb" style:width={view.cbPct + '%'}></div>
            <div class="gp-hero__bar-fill sb" style:left={view.cbPct + '%'} style:width={(100 - view.cbPct) + '%'}></div>
        </div>
    </div>
    <div class="gp-hero__cell">
        <div class="gp-hero__lbl">Sessions</div>
        <div class="gp-hero__val">{players.length}</div>
        <div class="gp-hero__sub">tracking {view.streamCount} streams</div>
    </div>
    <div class="gp-hero__cell">
        <div class="gp-hero__lbl">Classes seen</div>
        <div class="gp-hero__val">{view.classCount}</div>
        <div class="gp-hero__sub">
            {#if view.topClass}top · <span class="acc">{pktLabel(view.topClass.k)}</span>{:else}—{/if}
        </div>
    </div>
    <div class="gp-hero__cell">
        <div class="gp-hero__lbl">Total packets</div>
        <div class="gp-hero__val">{humanNumber(view.totalCount)}</div>
        <div class="gp-hero__sub">{humanBytes(view.cbBytes)} ⬇ · {humanBytes(view.sbBytes)} ⬆</div>
    </div>
</div>

<div class="grid-2-wide">
    <Panel title="Per-player swimlanes" meta={`${players.length} active`} flush>
        {#if !view.lanes.length}
            <div class="empty">No active sessions.</div>
        {:else}
            <div class="swimlanes">
                {#each view.lanes as { p, lane } (p.uuid)}
                    <SwimlaneRow
                        player={p}
                        {lane}
                        gmax={view.gmax}
                        onclick={() => navigate('/p/' + p.uuid + '/packets')}
                    />
                {/each}
            </div>
        {/if}
    </Panel>
    <Panel title="Anomalies" meta="1s sampling" flush>
        {#if !feed.anomalies.length}
            <div class="empty">No anomalies detected.</div>
        {:else}
            <div class="anomalies">
                {#each feed.anomalies as a, i (a.ts + ':' + i)}
                    <div class={'anomaly data-row data-row--panel data-row--interactive ' + a.kind}>
                        <span class="anomaly__indicator"></span>
                        <span class="anomaly__msg">{a.msg}</span>
                        <span class="anomaly__when">{fmtAge(feed.now - a.ts)} ago</span>
                    </div>
                {/each}
            </div>
        {/if}
    </Panel>
</div>

<div class="grid-2-equal mt">
    <PacketAggregatePanels agg={feed.agg} {sortBy} version={feed.version} topMeta="all players" heatmapMeta={sortBy} onSortBy={v => sortBy = v} />
</div>

<style>
    @layer pages {
        :global {
    /* ---- Global Packets page --------------------------------------- */
    .gp-hero {
        display: grid;
        grid-template-columns: 2fr 1fr 1fr 1fr;
        gap: 1px;
        background: var(--line);
        border: 1px solid var(--line);
        margin-bottom: var(--pad-3);
        .gp-hero__cell {
            padding: var(--pad-3) var(--pad-4);
            background: var(--bg-1);
            display: grid;
            gap: 4px;
            min-width: 0;
        }
        .gp-hero__lbl {
            font-size: var(--t-xs);
            color: var(--ink-3);
            text-transform: uppercase;
        }
        .gp-hero__val {
            font-size: var(--t-2xl);
            color: var(--ink);
            line-height: 1.05;
            .unit { font-size: var(--t-md); color: var(--ink-3); margin-left: 4px; }
        }
        .gp-hero__sub {
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
            .acc { color: var(--acc); }
        }
        .gp-hero__bar {
            position: relative;
            height: 8px;
            margin-top: 6px;
            background: var(--sunk);
            box-shadow: var(--bevel-sunk);
        }
        .gp-hero__bar-fill {
            position: absolute;
            top: 0;
            bottom: 0;
            &.cb { background: var(--dir-cb); left: 0; }
            &.sb { background: var(--dir-sb); }
        }
    }
    @media (max-width: 1100px) { .gp-hero { grid-template-columns: 1fr 1fr; } }

    .swimlanes { display: grid; gap: 1px; background: var(--line); }

    .anomalies {
        display: grid;
        gap: 1px;
        background: var(--line);
        max-height: 320px;
        overflow: auto;
    }

    .anomaly {
        display: grid;
        grid-template-columns: 4px 1fr auto;
        gap: var(--pad-2);
        align-items: center;

        .anomaly__indicator { height: 100%; background: var(--ink-4); }
        &.spike .anomaly__indicator { background: var(--warn); }
        &.note  .anomaly__indicator { background: var(--acc); }
        &.drop  .anomaly__indicator { background: var(--danger); }

        .anomaly__msg {
            color: var(--ink-2);
            .acc { color: var(--acc); }
        }
        .anomaly__when {
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
            white-space: nowrap;
        }
    }
        }
    }
</style>
