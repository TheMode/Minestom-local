<script lang="ts">
    import { type Field, defaultFor } from '../../lib/packetSchema.ts';
    import ElementSlot from './ElementSlot.svelte';

    type Props = {
        value: Record<string, unknown>;
        onChange: (v: Record<string, unknown>) => void;
        keyField: Field;
        valueField: Field;
    };

    let { value, onChange, keyField, valueField }: Props = $props();

    const entries = $derived(Object.entries(value ?? {}));
    let open = $state(true);

    function renameKey(i: number, newKey: string) {
        // Refuse renames that would silently clobber another entry's key; the parent stays
        // unchanged and the input visually stays on the old key for the next keystroke.
        if (entries.some(([k], j) => j !== i && k === newKey)) return;
        const out: Record<string, unknown> = {};
        entries.forEach(([k, v], j) => { out[j === i ? newKey : k] = v; });
        onChange(out);
    }
    function setValueAt(i: number, newValue: unknown) {
        const out: Record<string, unknown> = {};
        entries.forEach(([k, v], j) => { out[k] = j === i ? newValue : v; });
        onChange(out);
    }
    function removeAt(i: number) {
        const out: Record<string, unknown> = {};
        entries.forEach(([k, v], j) => { if (j !== i) out[k] = v; });
        onChange(out);
    }

    /// Synthesize a placeholder key that the backend's decodeMapKey can parse for the
    /// declared key kind — int/long/uuid wire-decoders refuse `"key0"`.
    function placeholderKey(): string {
        const k = keyField.kind;
        if (k === 'uuid') return crypto.randomUUID();
        if (k === 'int' || k === 'long' || k === 'byte' || k === 'short') {
            let n = entries.length;
            while (String(n) in (value ?? {})) n += 1;
            return String(n);
        }
        let n = entries.length;
        let key = `key${n}`;
        while (key in (value ?? {})) { n += 1; key = `key${n}`; }
        return key;
    }
    function append() {
        onChange({ ...(value ?? {}), [placeholderKey()]: defaultFor(valueField) });
    }
    function clear() { onChange({}); }
</script>

<div class="coll">
    <div class="coll__head">
        <button
            type="button"
            class="coll__head-toggle"
            onclick={() => open = !open}
            aria-expanded={open}
        >
            <span class="coll__title">
                <span class="coll__caret">{open ? '▾' : '▸'}</span>
                <span>map&lt;<b>{keyField.kind}</b>, <b>{valueField.kind}</b>&gt;</span>
                <span class="coll__count">{entries.length}</span>
            </span>
        </button>
        <div class="coll__tools" role="toolbar">
            <button type="button" class="coll__tool" title="Clear" onclick={clear}>⌫</button>
        </div>
    </div>
    {#if open}
        <div class="coll__body">
            {#if entries.length === 0}
                <div class="coll__empty">Empty map. Add an entry below.</div>
            {/if}
            {#each entries as [k, v], i (i)}
                <div class="coll__row coll__row--map">
                    <div class="coll__slot">
                        <ElementSlot
                            element={keyField}
                            value={k}
                            onChange={(nk) => renameKey(i, String(nk))}
                        />
                    </div>
                    <span class="coll__sep">→</span>
                    <div class="coll__slot">
                        <ElementSlot
                            element={valueField}
                            value={v}
                            onChange={(nv) => setValueAt(i, nv)}
                        />
                    </div>
                    <button type="button" class="coll__del" title="Remove" onclick={() => removeAt(i)}>×</button>
                </div>
            {/each}
            <button type="button" class="coll-add" onclick={append}>+ add entry</button>
        </div>
    {/if}
</div>
