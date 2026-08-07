<script lang="ts">
    import { api } from '../lib/api.ts';
    import { actionIcon } from '../lib/routineWire.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Pill from '../components/ui/Pill.svelte';
    import EmptyState from '../components/ui/EmptyState.svelte';
    import EntityCard from '../components/profile/EntityCard.svelte';
    import ActionEditor, { actionSummary } from '../components/editors/ActionEditor.svelte';
    import { toast } from '../state/toasts.svelte.ts';

    let list = $state([]);
    let editing = $state(null);
    let draftName = $state('');
    let draftAction = $state(null);
    let dlg;

    async function load() {
        try { list = await api('/actions'); } catch { list = []; }
    }
    $effect(() => { load(); });

    $effect(() => {
        if (!dlg) return;
        if (editing) dlg.showModal();
        else dlg.close();
    });

    function openNew() {
        draftName = '';
        draftAction = { type: 'chat', component: '' };
        editing = { id: null };
    }
    function openEdit(a) {
        draftName = a.name || '';
        draftAction = a.action;
        editing = a;
    }
    function close() { editing = null; }

    async function save() {
        try {
            await api('/actions', {
                method: 'POST',
                body: {
                    id: editing?.id || undefined,
                    name: draftName,
                    action: draftAction || { type: 'chat', component: '' },
                },
            });
            close(); await load(); toast('Action saved');
        } catch (e) { toast(e.message, 'error'); }
    }

    async function remove(id) {
        if (!confirm('Delete this action?')) return;
        try { await api('/actions/' + id, { method: 'DELETE' }); await load(); toast('Deleted'); }
        catch (e) { toast(e.message, 'error'); }
    }
</script>

{#snippet actionsCrumb()}Actions{/snippet}
{#snippet title()}<em>{list.length}</em> registered{/snippet}
{#snippet actions()}<button class="primary" onclick={openNew}>+ New action</button>{/snippet}

<ViewHead crumbs={[actionsCrumb]} {title} {actions} />

<div class="stack">
    {#if list.length === 0}
        <EmptyState
            title="No actions defined yet."
            hint="Actions are reusable side-effects (inject a packet, send chat, mutate state). Register one to reference it from routines and triggers."
        >
            {#snippet cta()}
                <button class="primary" onclick={openNew}>+ New action</button>
            {/snippet}
        </EmptyState>
    {:else}
        {#each list as a (a.id)}
            {@const kind = a.action?.type || 'unknown'}
            {@const usedCount = a.usedBy?.length ?? 0}
            <EntityCard title={a.name}>
                {#snippet icon()}<span title={kind}>{actionIcon(a.action)}</span>{/snippet}
                {#snippet badges()}
                    <Pill kind="on">{kind}</Pill>
                    <span class="small dim">{usedCount} routine{usedCount === 1 ? '' : 's'}</span>
                {/snippet}
                {#snippet detail()}
                    <span class="dim mono small truncate" style="max-width: 640px;">
                        {actionSummary(a.action)}
                    </span>
                {/snippet}
                {#snippet actions()}
                    <button class="sm ghost" onclick={() => openEdit(a)}>Edit</button>
                    <button class="sm danger" onclick={() => remove(a.id)}>Delete</button>
                {/snippet}
            </EntityCard>
        {/each}
    {/if}
</div>

<dialog bind:this={dlg} class="inspector wide" onclose={close}>
    <header>
        <h2>{editing?.id ? 'Edit action' : 'New action'}</h2>
        <div class="actions">
            <button class="ghost" onclick={close}>Cancel</button>
            <button class="primary" onclick={save}>Save</button>
        </div>
    </header>
    {#if editing}
        <div class="body">
            <label class="field">
                <span>Name</span>
                <input bind:value={draftName} placeholder="system announcement" />
            </label>
            <div class="field">
                <span class="dim small upper">Action</span>
                <ActionEditor value={draftAction} onChange={v => draftAction = v} />
            </div>
        </div>
    {/if}
</dialog>
