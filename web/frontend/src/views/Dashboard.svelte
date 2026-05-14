<script module lang="ts">
    const TRAFFIC_SERIES = [
        { key: 'in',  label: 'Ingress', color: 'var(--ink-2)' },
        { key: 'out', label: 'Egress',  color: 'var(--acc)',   area: true },
    ];
    const PACKETS_SERIES = [
        { key: 'in',  label: 'Inbound',  color: 'var(--ink-2)' },
        { key: 'out', label: 'Outbound', color: 'var(--acc)',   area: true },
    ];
</script>

<script lang="ts">
    import { api } from '../lib/api.ts';
    import { busStatus } from '../state/bus.svelte.ts';
    import { mode, REPLAY_TERMINAL } from '../state/mode.svelte.ts';
    import { players as playersStore } from '../state/players.svelte.ts';
    import { throughput } from '../state/throughput.svelte.ts';
    import { fmtTime, humanBytes, humanDuration, humanNumber } from '../lib/util.ts';
    import { pillKindFor, sessionDuration } from '../lib/playerDomain.ts';
    import Crumbs from '../components/ui/Crumbs.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import Pill from '../components/ui/Pill.svelte';
    import DashboardStats from '../components/profile/DashboardStats.svelte';
    import Chart from '../components/ui/Chart.svelte';

    const series = $derived(throughput.series);
    const players = $derived(playersStore.visible);
    const now = $derived(busStatus.now);

    let persistence = $state<{ enabled: boolean; protocolVersion?: number } | null>(null);
    let exporting = $state(false);
    let exportErr = $state<string | null>(null);

    $effect(() => {
        api('/persistence').then(r => persistence = r as any).catch(() => persistence = { enabled: false });
    });

    /// Hit `/api/export.sqlite` directly — `api()` decodes JSON, but the response is the raw
    /// VACUUM INTO snapshot. Stream it into an anchor download so the file lands in the user's
    /// downloads folder with a timestamped name.
    async function exportDb() {
        if (exporting) return;
        exporting = true;
        exportErr = null;
        try {
            const headers: Record<string, string> = {};
            const tok = sessionStorage.getItem('mw-token');
            if (tok) headers['X-Auth-Token'] = tok;
            const r = await fetch('/api/export.sqlite', { headers });
            if (!r.ok) throw new Error(`HTTP ${r.status}`);
            const blob = await r.blob();
            const a = document.createElement('a');
            a.href = URL.createObjectURL(blob);
            a.download = `sessions-${new Date().toISOString().replace(/[:.]/g, '-')}.sqlite`;
            document.body.appendChild(a);
            a.click();
            a.remove();
            URL.revokeObjectURL(a.href);
        } catch (e: any) {
            exportErr = String(e.message ?? e);
        } finally {
            exporting = false;
        }
    }

    const trafficData = $derived({ in: series.bytesIn, out: series.bytesOut });
    const packetsData = $derived({ in: series.packetsIn, out: series.packetsOut });

    const replayStatus = $derived(mode.scope?.status);
    const replayEnded = $derived(!!replayStatus && REPLAY_TERMINAL.has(replayStatus));
    const replayEndedAt = $derived(mode.scope?.endedAt);
    const replayError = $derived(mode.scope?.error);
</script>

{#snippet overview()}Overview{/snippet}

<div class="view-head">
    <div>
        <Crumbs steps={[overview]} />
        <h1>Live <em>Operations</em></h1>
    </div>
    {#if replayEnded}
        <div class="replay-ended" role="status">
            <span class="replay-ended__dot" class:replay-ended__dot--err={replayStatus === 'error'}></span>
            <div class="replay-ended__text">
                <strong>{replayStatus === 'error' ? 'Replay failed.' : 'Replay finished.'}</strong>
                <span class="dim">
                    {#if replayEndedAt}Frozen at {fmtTime(replayEndedAt)}.{/if}
                    Live counters have stopped updating.
                    {#if replayStatus === 'error' && replayError}<br />{replayError}{/if}
                </span>
            </div>
        </div>
    {/if}
    {#if persistence?.enabled}
        <div class="view-actions col" style="align-items: flex-end; gap: 4px;">
            <button class="primary sm" disabled={exporting} onclick={exportDb}>
                {exporting ? 'Exporting…' : 'Export history ⇣'}
            </button>
            {#if exportErr}
                <span class="small danger">{exportErr}</span>
            {:else}
                <span class="small dim">protocol v{persistence.protocolVersion}</span>
            {/if}
        </div>
    {/if}
</div>

<DashboardStats />

<div class="grid-2 mt">
    <Panel title="Network Throughput" meta="bytes / second">
        <Chart series={TRAFFIC_SERIES} data={trafficData}
               xValues={series.ts} yLabel="B/s" yFormat={humanBytes} className="chart-md" />
    </Panel>
    <Panel title="Packet Rate" meta="packets / second">
        <Chart series={PACKETS_SERIES} data={packetsData}
               xValues={series.ts} yLabel="/s" yFormat={v => humanNumber(Math.round(v))} className="chart-md" />
    </Panel>
</div>

<div class="mt">
    <Panel meta={`${players.length} online`} flush className="table-scroll">
        {#snippet title()}Active <em>Sessions</em>{/snippet}
        {#snippet actions()}<a href="/players" class="btn sm ghost">View all →</a>{/snippet}
        <table class="list">
            <thead><tr>
                <th>Player</th><th>State</th><th>Dimension</th><th>Mode</th>
                <th>Health</th><th>Ping</th><th>Session</th><th></th>
            </tr></thead>
            <tbody>
                {#each players as p (p.uuid)}
                    {@const offline = !!p.disconnectedAt}
                    {@const state = offline ? 'OFFLINE' : (p.serverConnectionState || '—')}
                    <tr class={offline ? 'row-offline' : ''}>
                        <td class="name">{p.username || '—'}</td>
                        <td><Pill kind={pillKindFor(p)} dot>{#snippet children()}{state}{/snippet}</Pill></td>
                        <td class="dim">{(p.dimension || '—').replace('minecraft:', '')}</td>
                        <td><Pill>{#snippet children()}{p.gamemode || '—'}{/snippet}</Pill></td>
                        <td class="num">{(p.health ?? 0).toFixed(1)} <span class="dim">/ {(p.maxHealth ?? 20).toFixed(0)}</span></td>
                        <td class="num">{offline ? '—' : p.traffic.pingMs}<span class="dim"> {offline ? '' : 'ms'}</span></td>
                        <td class="dim num">{humanDuration(sessionDuration(p, now))}</td>
                        <td>
                            <div class="player-actions">
                                <a href={'/p/' + p.uuid} class="btn sm">Open</a>
                                <a href={'/p/' + p.uuid + '/packets'} class="btn sm ghost">Packets</a>
                            </div>
                        </td>
                    </tr>
                {/each}
            </tbody>
        </table>
        {#if players.length === 0}<div class="empty">No sessions. The proxy is listening; clients have yet to arrive.</div>{/if}
    </Panel>
</div>

<style>
    @layer pages {
        :global {
    /* ---- Replay-ended banner --------------------------------------- */
    .replay-ended {
        display: flex;
        align-items: center;
        gap: var(--pad-3);
        padding: var(--pad-3) var(--pad-4);
        background: var(--bg-2);
        border: 1px solid var(--line);
        border-left: 3px solid var(--ink-3);
        font-size: var(--t-sm);
        max-width: 480px;
    }
    .replay-ended__dot {
        width: 10px;
        height: 10px;
        border-radius: 50%;
        background: var(--ink-3);
        flex: 0 0 auto;
    }
    .replay-ended__dot--err { background: var(--danger); }
    .replay-ended__text { display: flex; flex-direction: column; gap: 2px; }
    .replay-ended__text strong { color: var(--ink); font-weight: 600; }
        }
    }
</style>
