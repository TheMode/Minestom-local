<script lang="ts">
    import { api } from '../lib/api.ts';
    import { subscribeTopic } from '../state/bus.svelte.ts';
    import { Topics } from '../lib/topics.ts';
    import { debounce, fmtTime, shortUuid } from '../lib/util.ts';
    import { mqlError } from '../lib/mql.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import CodeEditor from '../components/packets/CodeEditor.svelte';
    import ActionSelector from '../components/editors/ActionSelector.svelte';
    import { toast } from '../state/toasts.svelte.ts';
    import { TriggerType } from '../lib/routineWire.ts';
    import { isActionRef, actionRefId } from '../lib/routineWire.ts';

    let ql = $state('');
    let action = $state(null);
    let preview = $state([]);
    let history = $state([]);
    let status = $state(null);

    const refresh = debounce(async query => {
        try {
            if (!query.trim()) {
                const players = await api('/players');
                preview = players;
                status = { kind: 'dim', message: `Everyone · ${players.length} online` };
            } else {
                const r = await api('/query', { method: 'POST', body: { ql: query } });
                const matches = r.matches || [];
                const players = await api('/players');
                const idx = new Map(players.map(p => [p.uuid, p]));
                preview = matches.map(u => idx.get(u)).filter(Boolean);
                status = { kind: matches.length ? 'ok' : 'dim', message: `${matches.length} matched · live` };
            }
        } catch (e) {
            status = mqlError(e, 'invalid query');
            preview = [];
        }
    }, 220);

    $effect(() => { refresh(ql); });
    subscribeTopic(Topics.players, () => refresh(ql));

    async function fire() {
        if (!action) { toast('No action defined', 'error'); return; }
        try {
            const r = await api('/trigger', { method: 'POST', body: { query: ql.trim() || null, action } });
            history = [{
                ts: fmtTime(Date.now()).slice(0, 8),
                action: isActionRef(action) ? `(registered ${shortUuid(actionRefId(action))})` : (action.type || 'action'),
                matched: r.matched, fired: r.fired, errors: r.errors || [],
            }, ...history].slice(0, 12);
            toast(`Fired on ${r.fired}/${r.matched} players`);
        } catch (e) { toast('Failed: ' + e.message, 'error'); }
    }

    async function saveAsRoutine() {
        if (!action) { toast('Pick an action first', 'error'); return; }
        const name = prompt('Routine name?', 'Saved trigger ' + new Date().toLocaleTimeString());
        if (!name) return;
        try {
            await api('/routines', {
                method: 'POST',
                body: { name, ql: ql.trim(), trigger: { type: TriggerType.onMatch }, action, enabled: true },
            });
            toast('Saved as routine');
        } catch (e) { toast(e.message, 'error'); }
    }
</script>

{#snippet triggerCrumb()}Trigger{/snippet}
{#snippet title()}Ad-hoc <em>trigger</em>{/snippet}
{#snippet actions()}
    <button class="ghost" onclick={saveAsRoutine}>Save as routine</button>
    <button class="primary" onclick={fire}>▶ Run on <span>{preview.length}</span> matches</button>
{/snippet}

<ViewHead crumbs={[triggerCrumb]} {title} {actions} />

<div class="trigger-layout">
    <div class="col gap-lg">
        <Panel meta="who runs this">
            {#snippet title()}1 · Match <span class="acc">·</span> MQL{/snippet}
            <CodeEditor value={ql} onChange={v => ql = v} rows={3} big placeholder='gamemode = "SURVIVAL" and ping < 100' {status} onSubmit={fire} />
        </Panel>

        <Panel meta="run once per match">
            {#snippet title()}2 · Then <span class="acc">·</span> action{/snippet}
            <ActionSelector value={action} onChange={v => action = v} />
        </Panel>

        <Panel title="History" flush>
            {#snippet meta()}<span>{history.length}</span> runs this session{/snippet}
            {#if history.length === 0}
                <div class="empty">No runs yet. Hit <span class="acc">▶ Run</span> to fire against the live roster.</div>
            {:else}
                <table class="list">
                    <thead><tr><th>Time</th><th>Action</th><th>Matched</th><th>Fired</th><th>Errors</th></tr></thead>
                    <tbody>
                        {#each history as h, i (i)}
                            <tr>
                                <td class="dim mono small">{h.ts}</td>
                                <td class="name">{h.action}</td>
                                <td class="num acc">{h.matched}</td>
                                <td class="num">{h.fired}</td>
                                <td class={'num ' + (h.errors.length ? 'dim' : '')}>{h.errors.length}</td>
                            </tr>
                        {/each}
                    </tbody>
                </table>
            {/if}
        </Panel>
    </div>

    <div class="col gap-lg">
        <Panel title="Preview">
            {#snippet meta()}<span>{preview.length}</span> will fire{/snippet}
            {#if preview.length === 0}
                <div class="empty">No matches.</div>
            {:else}
                {#each preview as p (p.uuid)}
                    <a class="match-card" href={'/p/' + p.uuid}>
                        <span class="av"></span>
                        <div>
                            <div class="ink">{p.username || '—'}</div>
                            <div class="dim small mono">{(p.dimension || '—').replace('minecraft:', '')} · HP {(p.health ?? 0).toFixed(1)}</div>
                        </div>
                        <span class="acc small upper">→</span>
                    </a>
                {/each}
            {/if}
        </Panel>

        <Panel title="Tips">
            <div class="col" style="gap: var(--pad-2); font-size: var(--t-sm); color: var(--ink-3);">
                <div><span class="acc upper small">match all</span> · leave blank to target every player</div>
                <div><span class="acc upper small">dry run</span> · pick a chat action to preview what would fire</div>
                <div><span class="acc upper small">recurring</span> · click "Save as routine" to fire it automatically</div>
            </div>
        </Panel>
    </div>
</div>
