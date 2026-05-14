<script lang="ts">
    import { triggerIcon, triggerLabel, isActionRef, actionRefId } from '../lib/routineWire.ts';
    import { api } from '../lib/api.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Pill from '../components/ui/Pill.svelte';
    import Toggle from '../components/ui/Toggle.svelte';
    import CodeEditor from '../components/packets/CodeEditor.svelte';
    import MqlSnippet from '../components/packets/MqlSnippet.svelte';
    import TriggerEditor from '../components/editors/TriggerEditor.svelte';
    import ActionSelector from '../components/editors/ActionSelector.svelte';
    import { actionSummary } from '../components/editors/ActionEditor.svelte';
    import EmptyState from '../components/ui/EmptyState.svelte';
    import EntityCard from '../components/profile/EntityCard.svelte';
    import { toast } from '../state/toasts.svelte.ts';

    let routines = $state([]);
    let editing = $state(null);
    let draft = $state(null);
    let dlg;

    async function load() {
        try { routines = await api('/routines'); } catch { routines = []; }
    }
    $effect(() => { load(); });

    $effect(() => {
        if (!dlg) return;
        if (editing) dlg.showModal();
        else dlg.close();
    });

    function openNew() {
        draft = { name: '', ql: '', trigger: { type: 'onMatch' }, action: null, enabled: true };
        editing = { id: null };
    }
    function openEdit(r) {
        draft = {
            name: r.name || '', ql: r.ql || '',
            trigger: r.trigger || { type: 'onMatch' },
            action: r.action || null, enabled: r.enabled ?? true,
        };
        editing = r;
    }
    function close() { editing = null; }

    async function save() {
        try {
            const saved = await api('/routines', {
                method: 'POST',
                body: {
                    id: editing?.id || undefined,
                    name: draft.name,
                    ql: (draft.ql || '').trim(),
                    trigger: draft.trigger,
                    action: draft.action || { type: 'chat', component: '' },
                },
            });
            await setEnabled(saved.id, draft.enabled);
            close(); await load(); toast('Routine saved');
        } catch (e) { toast(e.message, 'error'); }
    }

    async function remove(id) {
        if (!confirm('Delete this routine?')) return;
        try { await api('/routines/' + id, { method: 'DELETE' }); await load(); toast('Deleted'); }
        catch (e) { toast(e.message, 'error'); }
    }

    async function toggleEnabled(r) {
        try { await setEnabled(r.id, !r.enabled); await load(); }
        catch (e) { toast(e.message, 'error'); }
    }

    function setEnabled(id, enabled) {
        return api('/routines/' + id + '/enabled', { method: 'PUT', body: { enabled } });
    }

    const activeCount = $derived(routines.filter(r => r.enabled).length);
</script>

{#snippet routinesCrumb()}Routines{/snippet}
{#snippet title()}<em>{activeCount}</em> / {routines.length} active{/snippet}
{#snippet actions()}<button class="primary" onclick={openNew}>+ New routine</button>{/snippet}

<ViewHead crumbs={[routinesCrumb]} {title} {actions} />

<div class="stack">
    {#if routines.length === 0}
        <EmptyState
            title="No routines defined yet."
            hint="Routines fire actions automatically when a query matches, on packet decode, or on a timer."
        >
            {#snippet cta()}
                <button class="primary" onclick={openNew}>+ New routine</button>
            {/snippet}
        </EmptyState>
    {:else}
        {#each routines as r (r.id)}
            {@const ax = r.action}
            {@const kind = isActionRef(ax) ? 'ref' : (ax?.type || 'inline')}
            {@const summary = isActionRef(ax) ? `(registered ${actionRefId(ax)})` : actionSummary(ax)}
            <EntityCard off={!r.enabled} title={r.name}>
                {#snippet icon()}<span title={triggerLabel(r.trigger)}>{triggerIcon(r.trigger)}</span>{/snippet}
                {#snippet badges()}
                    <Pill>{triggerLabel(r.trigger)}</Pill>
                    <span class="small dim">{r.enabled ? 'enabled' : 'disabled'}</span>
                {/snippet}
                {#snippet detail()}
                    <span class="mql-snippet" style="overflow: hidden; text-overflow: ellipsis; max-width: 480px; white-space: nowrap;">
                        {#if r.ql}<MqlSnippet src={r.ql} />{:else}<span class="dim small">(empty)</span>{/if}
                    </span>
                    <span class="small dim">→</span>
                    <span class="action-chip"><span class="action-chip-kind">{kind}</span><span class="action-chip-name">{summary}</span></span>
                {/snippet}
                {#snippet actions()}
                    <Toggle on={r.enabled} onchange={() => toggleEnabled(r)} />
                    <button class="sm ghost" onclick={() => openEdit(r)}>Edit</button>
                    <button class="sm danger" onclick={() => remove(r.id)}>Delete</button>
                {/snippet}
            </EntityCard>
        {/each}
    {/if}
</div>

<dialog bind:this={dlg} class="inspector wide" onclose={close}>
    <header>
        <h2>{editing?.id ? 'Edit routine' : 'New routine'}</h2>
        <div class="actions">
            <button class="ghost" onclick={close}>Cancel</button>
            <button class="primary" onclick={save}>Save</button>
        </div>
    </header>
    {#if draft}
        <div class="body">
            <label class="field">
                <span>Name</span>
                <input bind:value={draft.name} placeholder="low health alert" />
            </label>
            <div class="field">
                <span class="dim small upper">Match (MQL)</span>
                <CodeEditor value={draft.ql} onChange={ql => draft.ql = ql} rows={2} placeholder='health < 6 and gamemode = "SURVIVAL"' onSubmit={save} />
            </div>
            <div class="field">
                <span class="dim small upper">Trigger</span>
                <TriggerEditor value={draft.trigger} onChange={trigger => draft.trigger = trigger} />
            </div>
            <div class="field">
                <span class="dim small upper">Action</span>
                <ActionSelector value={draft.action} onChange={action => draft.action = action} />
            </div>
            <label class="row gap-sm" style="align-items: center;">
                <input type="checkbox" bind:checked={draft.enabled} style="width: auto;" />
                <span class="small dim">Enabled</span>
            </label>
        </div>
    {/if}
</dialog>
