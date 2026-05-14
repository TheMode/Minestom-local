<script module lang="ts">
    const pingOf = p => p.traffic.pingMs;

    const SORT = {
        connectedAt: (a, b) => (a.connectedAt ?? 0) - (b.connectedAt ?? 0),
        ping:        (a, b) => pingOf(a) - pingOf(b),
        name:        (a, b) => (a.username || '').localeCompare(b.username || ''),
        health:      (a, b) => (a.health ?? 0) - (b.health ?? 0),
    };
</script>

<script lang="ts">
    import { api } from '../lib/api.ts';
    import { busStatus } from '../state/bus.svelte.ts';
    import { players as playersStore } from '../state/players.svelte.ts';
    import { debounce, humanBytes, humanDuration, shortUuid } from '../lib/util.ts';
    import { pillKindFor, sessionDuration } from '../lib/playerDomain.ts';
    import { mqlError } from '../lib/mql.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import Pill from '../components/ui/Pill.svelte';
    import Sparkline from '../components/ui/Sparkline.svelte';
    import CodeEditor from '../components/packets/CodeEditor.svelte';

    const players = $derived(playersStore.visible);
    let matches = $state(null);
    let sortKey = $state('connectedAt');
    let query = $state('');
    let status = $state(null);
    const now = $derived(busStatus.now);

    const evalDebounced = debounce(async ql => {
        if (!ql.trim()) { matches = null; status = null; return; }
        try {
            const r = await api('/query', { method: 'POST', body: { ql } });
            matches = r.matches || [];
            status = { kind: (r.matches?.length ?? 0) ? 'ok' : 'dim', message: `${r.matches?.length ?? 0} matched · live` };
        } catch (e) {
            status = mqlError(e, 'invalid query');
        }
    }, 220);

    $effect(() => {
        query; players;
        evalDebounced(query);
    });

    const visible = $derived.by(() => {
        let rs = players;
        if (matches) {
            const s = new Set(matches);
            rs = rs.filter(p => s.has(p.uuid));
        }
        const cmp = SORT[sortKey] || SORT.connectedAt;
        return [...rs].sort(cmp);
    });

    const statusToShow = $derived(status ?? { kind: 'dim', message: `${players.length} / ${players.length} matched` });
</script>

{#snippet playersCrumb()}Players{/snippet}
{#snippet title()}<em>{visible.length}</em> connected{/snippet}
{#snippet actions()}
    <select value={sortKey} onchange={e => sortKey = e.target.value}>
        <option value="connectedAt">Sort: session length</option>
        <option value="ping">Sort: ping</option>
        <option value="name">Sort: name</option>
        <option value="health">Sort: health</option>
    </select>
{/snippet}

<ViewHead crumbs={[playersCrumb]} {title} {actions} />

<div class="mb">
    <CodeEditor
        value={query}
        onChange={v => query = v}
        rows={1}
        compact
        placeholder='filter — e.g.  ping > 100  or  gamemode = "SURVIVAL"'
        status={statusToShow}
    />
</div>

<Panel headless flush className="table-scroll">
    <table class="list">
        <thead><tr>
            <th>Player</th><th>UUID</th><th>Backend</th><th>State</th><th>Dimension</th>
            <th>Mode</th><th>Pos</th><th>Health</th><th>Food</th>
            <th>XP</th><th>Ping</th><th>Latency 60s</th><th>In · Out</th>
            <th>Session</th><th></th>
        </tr></thead>
        <tbody>
            {#each visible as p (p.uuid)}
                {@const pos = [p.posX ?? 0, p.posY ?? 0, p.posZ ?? 0]}
                {@const offline = !!p.disconnectedAt}
                <tr class={offline ? 'row-offline' : ''}>
                    <td class="name">{p.username || '—'}</td>
                    <td class="dim mono small">{shortUuid(p.uuid)}</td>
                    <td class="dim mono small">{p.backendAddress || '—'}</td>
                    <td><Pill kind={pillKindFor(p)} dot>{offline ? 'OFFLINE' : (p.serverConnectionState || '—')}</Pill></td>
                    <td class="dim">{(p.dimension || '—').replace('minecraft:', '')}</td>
                    <td><Pill>{p.gamemode || '—'}</Pill></td>
                    <td class="dim mono small">{pos.map(v => Number(v).toFixed(0)).join(', ')}</td>
                    <td class="num">{(p.health ?? 0).toFixed(1)}<span class="dim">/{(p.maxHealth ?? 20).toFixed(0)}</span></td>
                    <td class="num">{p.food ?? 0}<span class="dim">/20</span></td>
                    <td class="num">{p.xpLevel ?? 0}</td>
                    <td class="num">{pingOf(p)}<span class="dim"> ms</span></td>
                    <td>
                        <span class="spark-wrap" style="width: 60px; height: 18px; display: inline-block;">
                            <Sparkline data={p.traffic.pingHistory} color="var(--acc)" fill="transparent" />
                        </span>
                    </td>
                    <td class="dim mono small">{humanBytes(p.traffic.bytesIn)}·{humanBytes(p.traffic.bytesOut)}</td>
                    <td class="dim num">{humanDuration(sessionDuration(p, now))}</td>
                    <td>
                        <div class="player-actions">
                            <a class="btn sm" href={'/p/' + p.uuid}>Open</a>
                            <a class="btn sm ghost" href={'/p/' + p.uuid + '/packets'}>Packets</a>
                        </div>
                    </td>
                </tr>
            {/each}
        </tbody>
    </table>
    {#if visible.length === 0}<div class="empty">No connected players.</div>{/if}
</Panel>
