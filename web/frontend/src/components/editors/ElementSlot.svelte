<script lang="ts">
    import CodeEditor from '../packets/CodeEditor.svelte';
    import { type Field, isExpressionKind } from '../../lib/packetSchema.ts';
    import ItemBuilder from './ItemBuilder.svelte';
    import ComponentBuilder from './ComponentBuilder.svelte';
    import ListEditor from './ListEditor.svelte';
    import MapEditor from './MapEditor.svelte';
    import RecordEditor from './RecordEditor.svelte';

    type Props = {
        element: Field;
        value: unknown;
        onChange: (v: unknown) => void;
    };

    let { element, value, onChange }: Props = $props();

    function placeholderFor(): string {
        switch (element.kind) {
            case 'string': return '"hello"';
            case 'uuid':   return 'player.uuid';
            case 'char':   return '"x"';
            case 'float':
            case 'double': return '0.0';
            default:       return '0';
        }
    }
</script>

{#if element.kind === 'boolean'}
    <span class="el-bool">
        <input
            type="checkbox"
            checked={!!value}
            onchange={(e) => onChange((e.currentTarget as HTMLInputElement).checked)}
        />
    </span>
{:else if element.kind === 'enum'}
    <select
        value={String(value ?? (element.values?.[0] ?? ''))}
        onchange={(e) => onChange((e.currentTarget as HTMLSelectElement).value)}
    >
        {#each element.values ?? [] as v (v)}
            <option value={v}>{v}</option>
        {/each}
    </select>
{:else if isExpressionKind(element.kind)}
    <CodeEditor
        language="expression"
        value={String(value ?? '')}
        onChange={onChange}
        rows={1}
        placeholder={placeholderFor()}
    />
{:else if element.kind === 'item'}
    <ItemBuilder value={(value as Record<string, unknown>) ?? null} onChange={onChange} />
{:else if element.kind === 'component'}
    <ComponentBuilder value={(value as Record<string, unknown>) ?? null} onChange={onChange} />
{:else if element.kind === 'record' && element.components}
    <RecordEditor
        components={element.components}
        value={(value as Record<string, unknown>) ?? {}}
        onChange={onChange}
    />
{:else if element.kind === 'list' && element.element}
    <ListEditor
        value={(value as unknown[]) ?? []}
        onChange={onChange}
        element={element.element}
    />
{:else if element.kind === 'map' && element.key && element.value}
    <MapEditor
        value={(value as Record<string, unknown>) ?? {}}
        onChange={onChange}
        keyField={element.key}
        valueField={element.value}
    />
{/if}
