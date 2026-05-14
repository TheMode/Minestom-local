<script lang="ts">
    import { type Field, defaultFor, kindLabel, isBlockKind } from '../../lib/packetSchema.ts';
    import ElementSlot from './ElementSlot.svelte';
    import LibraryRecallPopover from './LibraryRecallPopover.svelte';
    import { packetLibrary, kindToBucket } from '../../lib/packetLibrary.svelte.ts';
    import { toast } from '../../state/toasts.svelte.ts';

    type Props = {
        field: Field;
        value: unknown;
        onChange: (v: unknown) => void;
    };

    let { field, value, onChange }: Props = $props();

    const name = $derived(field.name);
    const kind = $derived(kindLabel(field));
    const col = $derived(isBlockKind(field.kind));
    const saveBucket = $derived(kindToBucket(field.kind) ?? undefined);

    let recallBtn = $state<HTMLButtonElement | null>(null);
    let showRecall = $state(false);

    function doSave() {
        if (!saveBucket) return;
        if (value == null || (typeof value === 'object' && Object.keys(value as object).length === 0)) {
            toast('Nothing to save — fill in the field first.', 'warn');
            return;
        }
        const label = prompt(`Save ${saveBucket.replace(/s$/, '')} as…`, name);
        if (!label) return;
        packetLibrary.save(saveBucket, { name: label, value: structuredClone(value) as Record<string, unknown> });
        toast(`Saved to ${saveBucket} library`, 'ok');
    }
</script>

<div class="pkt-field {col ? 'pkt-field--col' : ''}">
    <span class="editor-label">
        <span class="pkt-field__name">{name}</span>
        <span class="pkt-field__kind">{kind}</span>
    </span>
    <div class="pkt-field__slot">
        <ElementSlot element={field} value={value} onChange={onChange} />
    </div>
    <div class="pkt-field__tools">
        {#if saveBucket}
            <button type="button" class="pkt-field__tool" title="Save to {saveBucket} library" onclick={doSave}>☆</button>
            <button
                type="button"
                class="pkt-field__tool {showRecall ? 'is-on' : ''}"
                title="Recall {saveBucket} from library"
                bind:this={recallBtn}
                onclick={() => showRecall = !showRecall}
            >📁</button>
        {/if}
        <button type="button" class="pkt-field__tool" title="Reset to default" onclick={() => onChange(defaultFor(field))}>⌫</button>
    </div>
</div>

{#if showRecall && saveBucket && recallBtn}
    <LibraryRecallPopover
        bucket={saveBucket}
        anchor={recallBtn}
        onPick={(v) => onChange(v)}
        onClose={() => showRecall = false}
    />
{/if}
