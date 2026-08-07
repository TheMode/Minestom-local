<script lang="ts">
    import { untrack } from 'svelte';
    import { fetchApi } from '../../state/api.svelte.ts';
    import ActionEditor, { actionSummary } from './ActionEditor.svelte';
    import { ActionType, isActionRef, actionRefId } from '../../lib/routineWire.ts';

    let { value, onChange } = $props();

    const groupId = 'asel-' + Math.random().toString(36).slice(2, 9);
    const registered = fetchApi('/actions');

    let inlineDraft = untrack(() => isActionRef(value) ? null : value);
    let mode = $state(untrack(() => isActionRef(value) ? 'registered' : 'inline'));

    $effect(() => {
        if (isActionRef(value) && mode !== 'registered') mode = 'registered';
    });

    function switchMode(next: string) {
        if (next === mode) return;
        mode = next;
        if (next === 'inline') {
            onChange?.(inlineDraft || null);
        } else {
            if (!isActionRef(value)) inlineDraft = value;
            onChange?.(null);
        }
    }

    function onInlineChange(v: Record<string, unknown>) {
        inlineDraft = v;
        onChange?.(v);
    }
</script>

<div class="action-selector">
    <div class="asel-mode">
        <label class="asel-mode-btn">
            <input
                type="radio"
                name={groupId}
                value="inline"
                checked={mode === 'inline'}
                onchange={() => switchMode('inline')}
            />
            <span>Inline</span>
        </label>
        <label class="asel-mode-btn">
            <input
                type="radio"
                name={groupId}
                value="registered"
                checked={mode === 'registered'}
                onchange={() => switchMode('registered')}
            />
            <span>Registered</span>
        </label>
    </div>
    <div class="asel-body">
        {#if mode === 'inline'}
            <ActionEditor value={isActionRef(value) ? null : value} onChange={onInlineChange} />
        {:else if (registered.data?.length ?? 0) === 0}
            <div class="empty empty--sunken">No registered actions. <a href="/actions">Create one</a>.</div>
        {:else}
            <select
                value={actionRefId(value)}
                onchange={e => {
                    const id = e.currentTarget.value;
                    onChange?.(id ? { type: ActionType.ref, id } : null);
                }}
            >
                <option value="">Select an action…</option>
                {#each registered.data as a (a.id)}
                    <option value={a.id}>{a.name} — {actionSummary(a.action)}</option>
                {/each}
            </select>
        {/if}
    </div>
</div>
