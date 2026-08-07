<script lang="ts">
    import { type Field } from '../../lib/packetSchema.ts';
    import FieldRow from './FieldRow.svelte';
    import { libraryDrop } from '../../lib/libraryDrop.svelte.ts';

    type Props = {
        components: Field[];
        value: Record<string, unknown>;
        onChange: (v: Record<string, unknown>) => void;
    };

    let { components, value, onChange }: Props = $props();

    const drop = libraryDrop('records', (v) => onChange(v as Record<string, unknown>));
</script>

<div
    class="record-block {drop.over ? 'drop-over' : ''}"
    role="group"
    {...drop.handlers}
>
    {#each components as c (c.name)}
        <FieldRow
            field={c}
            value={value?.[c.name]}
            onChange={(v) => onChange({ ...(value ?? {}), [c.name]: v })}
        />
    {/each}
</div>
