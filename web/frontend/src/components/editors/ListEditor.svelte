<script lang="ts">
    import { type Field, defaultFor } from '../../lib/packetSchema.ts';
    import ElementSlot from './ElementSlot.svelte';
    import { libraryDrop } from '../../lib/libraryDrop.svelte.ts';
    import { kindToBucket } from '../../lib/packetLibrary.svelte.ts';

    type Props = {
        value: unknown[];
        onChange: (v: unknown[]) => void;
        element: Field;
    };

    let { value, onChange, element }: Props = $props();

    const items = $derived(Array.isArray(value) ? value : []);
    let open = $state(true);
    let userTableMode = $state<boolean | null>(null);
    const tableMode = $derived(userTableMode ?? (element.kind === 'record'));

    const drop = libraryDrop(() => kindToBucket(element.kind), (v) => onChange([...items, v]));

    function append() { onChange([...items, defaultFor(element)]); }
    function clear() { onChange([]); }
    function duplicateLast() {
        if (items.length === 0) return;
        onChange([...items, structuredClone(items[items.length - 1])]);
    }
    function replaceAt(i: number, v: unknown) {
        onChange(items.map((x, j) => j === i ? v : x));
    }
    function removeAt(i: number) {
        onChange(items.filter((_, j) => j !== i));
    }

    const recordCols = $derived(element.kind === 'record' && element.components ? element.components : []);
</script>

<div
    class="coll {tableMode && element.kind === 'record' ? 'coll--table' : ''} {drop.over ? 'drop-over' : ''}"
    role="group"
    {...drop.handlers}
>
    <div class="coll__head">
        <button
            type="button"
            class="coll__head-toggle"
            onclick={() => open = !open}
            aria-expanded={open}
        >
            <span class="coll__title">
                <span class="coll__caret">{open ? '▾' : '▸'}</span>
                <span>list&lt;<b>{element.kind}</b>&gt;</span>
                <span class="coll__count">{items.length}</span>
            </span>
        </button>
        <div class="coll__tools" role="toolbar">
            {#if element.kind === 'record'}
                <div class="seg-control seg-control--mini" role="radiogroup">
                    <button type="button" class={!tableMode ? 'is-on' : ''} onclick={() => userTableMode = false}>cards</button>
                    <button type="button" class={tableMode ? 'is-on' : ''} onclick={() => userTableMode = true}>table</button>
                </div>
            {/if}
            <button type="button" class="coll__tool" title="Duplicate last" onclick={duplicateLast}>⎘</button>
            <button type="button" class="coll__tool" title="Clear" onclick={clear}>⌫</button>
        </div>
    </div>
    {#if open}
        {#if tableMode && element.kind === 'record'}
            <div class="coll__tbl-head" style:--cols={recordCols.length}>
                <div class="coll__tbl-cols">
                    {#each recordCols as c (c.name)}<span>{c.name}</span>{/each}
                </div>
                <span></span>
            </div>
        {/if}
        <div class="coll__body">
            {#if items.length === 0}
                <div class="coll__empty">Empty list. Add an entry below.</div>
            {/if}
            {#each items as item, i (i)}
                <div class="coll__row">
                    {#if tableMode && element.kind === 'record'}
                        <div class="coll__tbl-cells" style:--cols={recordCols.length}>
                            {#each recordCols as c (c.name)}
                                <ElementSlot
                                    element={c}
                                    value={(item as Record<string, unknown>)?.[c.name]}
                                    onChange={(v) => replaceAt(i, { ...(item as Record<string, unknown> ?? {}), [c.name]: v })}
                                />
                            {/each}
                        </div>
                    {:else}
                        <div class="coll__slot">
                            <ElementSlot
                                element={element}
                                value={item}
                                onChange={(v) => replaceAt(i, v)}
                            />
                        </div>
                    {/if}
                    <button type="button" class="coll__del" title="Remove" onclick={() => removeAt(i)}>×</button>
                </div>
            {/each}
            <button type="button" class="coll-add" onclick={append}>+ add {element.kind}</button>
        </div>
    {/if}
</div>
