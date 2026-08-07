<script module lang="ts">
    const MAX_LINES = 2000;
</script>

<script lang="ts">
    import { api } from '../lib/api.ts';
    import { subscribeTopic } from '../state/bus.svelte.ts';
    import { Topics, type ConsoleMessage, type GlobalMessage } from '../lib/topics.ts';
    import type { ControlMetrics } from '../lib/types.ts';
    import { fmtTime, humanBytes, humanDuration } from '../lib/util.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import NbtTree from '../components/profile/NbtTree.svelte';
    import { toast } from '../state/toasts.svelte.ts';

    let lines = $state([]);
    let metrics = $state(null);
    let global = $state(null);
    let input = $state('');
    let busy = $state(false);
    let tail = $state();
    let stuck = true;

    $effect(() => {
        let cancelled = false;
        (async () => {
            try {
                const [history, m, g] = await Promise.all([
                    api('/console/history'),
                    api('/metrics/latest').catch(() => null),
                    api('/global').catch(() => null),
                ]);
                if (cancelled) return;
                if (Array.isArray(history)) lines = history;
                if (m && typeof m === 'object') metrics = m;
                if (g !== null && typeof g === 'object') global = g;
            } catch {}
        })();
        return () => { cancelled = true; };
    });

    subscribeTopic<ConsoleMessage>(Topics.console, msg => {
        const next = lines.length >= MAX_LINES ? lines.slice(lines.length - MAX_LINES + 1) : lines;
        lines = [...next, { ts: msg.ts, level: msg.level, message: msg.message }];
    });
    subscribeTopic<ControlMetrics>(Topics.metrics, msg => { metrics = msg; });
    subscribeTopic<GlobalMessage>(Topics.global, msg => { global = msg.data ?? null; });

    $effect(() => {
        lines;
        if (!tail || !stuck) return;
        tail.scrollTop = tail.scrollHeight;
    });

    function onScroll(e) {
        const el = e.currentTarget;
        const slack = el.scrollHeight - el.clientHeight - el.scrollTop;
        stuck = slack < 24;
    }

    async function send(ev) {
        ev?.preventDefault();
        const command = input.trim();
        if (!command) return;
        busy = true;
        try {
            await api('/console/command', { method: 'POST', body: { command } });
            input = '';
        } catch (e) {
            toast('Command failed: ' + e.message, 'error');
        } finally {
            busy = false;
        }
    }
</script>

{#snippet terminalCrumb()}Terminal{/snippet}
{#snippet title()}Server <em>terminal</em>{/snippet}
{#snippet actions()}<span class="dim small mono">{lines.length} lines · live tail</span>{/snippet}

<ViewHead crumbs={[terminalCrumb]} {title} {actions} />

{#if metrics}
    {@const m = metrics}
    <div class="metrics-strip">
        <div class="metric-tile"><span class="dim small upper">CPU</span><span class="mono">{(m.processCpu * 100).toFixed(1)}%</span></div>
        <div class="metric-tile"><span class="dim small upper">Heap</span><span class="mono">{`${humanBytes(m.heapUsed)} / ${humanBytes(m.heapMax)}`}</span></div>
        <div class="metric-tile"><span class="dim small upper">TPS</span><span class="mono">{m.tps.toFixed(1)}</span></div>
        <div class="metric-tile"><span class="dim small upper">MSPT</span><span class="mono">{m.mspt.toFixed(2)} ms</span></div>
        <div class="metric-tile"><span class="dim small upper">Threads</span><span class="mono">{m.threadCount}</span></div>
        <div class="metric-tile"><span class="dim small upper">Uptime</span><span class="mono">{humanDuration(m.uptimeMs)}</span></div>
        <div class="metric-tile"><span class="dim small upper">Players</span><span class="mono">{m.playerCount}</span></div>
    </div>
{/if}
<div class="terminal-layout">
    <div class="col gap-md">
        <Panel title="Output" flush>
            {#snippet meta()}<span>{lines.length}</span> lines{/snippet}
            <div bind:this={tail} class="console-tail" onscroll={onScroll}>
                {#if lines.length === 0}
                    <div class="empty">Waiting for output…</div>
                {:else}
                    {#each lines as l, i (i)}
                        <div class={'console-line lvl-' + (l.level || 'info').toLowerCase()}>
                            <span class="dim mono small">{fmtTime(l.ts).slice(0, 8)}</span>
                            <span class={'console-level lvl-' + (l.level || 'info').toLowerCase()}>{l.level}</span>
                            <span class="console-msg">{l.message}</span>
                        </div>
                    {/each}
                {/if}
            </div>
        </Panel>

        <form class="console-input" onsubmit={send}>
            <span class="prompt">{'>'}</span>
            <input
                type="text"
                spellcheck="false"
                autocomplete="off"
                placeholder="say hi · gamemode creative @s · /tp …"
                bind:value={input}
                disabled={busy}
            />
            <button type="submit" class="primary" disabled={busy || !input.trim()}>
                Send
            </button>
        </form>
    </div>

    <Panel title="Global NBT" meta={global ? `${Object.keys(global).length} keys` : 'empty'} flush>
        <div class="terminal-nbt">
            {#if global && Object.keys(global).length > 0}
                <NbtTree value={global} name="global" />
            {:else}
                <div class="empty">No data pushed yet.<br /><span class="dim small">Queryable via <span class="mono">global.&lt;path&gt;</span>.</span></div>
            {/if}
        </div>
    </Panel>
</div>
