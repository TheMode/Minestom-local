<script module lang="ts">
    function fmtNumber(n) {
        if (Number.isInteger(n)) return String(n);
        return parseFloat(n.toFixed(6)).toString();
    }

    function numberType(n) {
        if (Number.isInteger(n)) {
            if (n >= -128 && n <= 127) return 'Byte';
            if (n >= -32768 && n <= 32767) return 'Short';
            if (n >= -2147483648 && n <= 2147483647) return 'Int';
            return 'Long';
        }
        return 'Float';
    }

    function compoundSummary(obj) {
        const keys = Object.keys(obj);
        if (keys.length === 0) return '{ }';
        const preview = keys.slice(0, 3).join(', ');
        return keys.length > 3 ? preview + ', …' : preview;
    }

    function listSummary(arr) {
        if (arr.length === 0) return '[ ]';
        const sample = arr.slice(0, 4).map(v => {
            if (v === null) return 'null';
            if (typeof v === 'string')  return `"${v.length > 12 ? v.slice(0, 12) + '…' : v}"`;
            if (typeof v === 'boolean') return String(v);
            if (typeof v === 'number')  return fmtNumber(v);
            if (Array.isArray(v))       return `[${v.length}]`;
            if (typeof v === 'object')  return `{${Object.keys(v).length}}`;
            return String(v);
        });
        return arr.length > 4 ? sample.join(', ') + ', …' : sample.join(', ');
    }

    function listHomogeneous(arr) {
        if (arr.length === 0) return null;
        let kind = null;
        for (const v of arr) {
            let k;
            if (v === null) k = 'Null';
            else if (typeof v === 'string')  k = 'String';
            else if (typeof v === 'boolean') k = 'Bool';
            else if (typeof v === 'number')  k = numberType(v);
            else return null;
            if (kind == null) kind = k;
            else if (kind !== k) return null;
        }
        return kind;
    }

    export { fmtNumber, numberType, compoundSummary, listSummary, listHomogeneous };
</script>

<script lang="ts">
    import { untrack } from 'svelte';
    import Self from './NbtTree.svelte';

    let { value, name = 'root', depth = 0, root = true, wrap = true } = $props();

    function leafKind(v) {
        if (v === null || v === undefined) return { kind: 'null', text: 'null', type: 'Null' };
        if (typeof v === 'string')          return { kind: 'string', text: `"${v}"`, type: 'String' };
        if (typeof v === 'boolean')         return { kind: 'bool', text: String(v), type: 'Bool' };
        if (typeof v === 'number')          return { kind: 'num', text: fmtNumber(v), type: numberType(v) };
        return null;
    }

    const leaf = $derived(leafKind(value));
    const isArray = $derived(!leaf && Array.isArray(value));
    const isObject = $derived(!leaf && !isArray && typeof value === 'object');
    // Initial expand state is decided once from the props snapshot — composites near the root
    // unfold, deeper nodes start collapsed.
    let open = $state(untrack(() => root || depth < (Array.isArray(value) ? 1 : 2)));

    function toggle() { open = !open; }
</script>

{#snippet body()}
    {#if leaf}
        <div class="nbt-row">
            <span class="nbt-row__key">{name}</span>
            <span class={'nbt-row__value nbt-v--' + leaf.kind}>{leaf.text}</span>
            <span class="nbt-row__type">{leaf.type}</span>
        </div>
    {:else if isObject}
        {@const entries = Object.entries(value)}
        <div class="nbt-node">
            <div
                class={'nbt-row nbt-row--toggle' + (open ? ' is-open' : '')}
                onclick={toggle}
                role="button"
                tabindex="0"
                onkeydown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } }}
            >
                <span class="nbt-row__key">
                    <span class="nbt-chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
                    {name}
                </span>
                <span class="nbt-row__value nbt-v--composite">
                    {#if entries.length === 0}
                        <span class="nbt-empty">{'{ }'}</span>
                    {:else}
                        <span class="nbt-summary">{compoundSummary(value)}</span>
                    {/if}
                </span>
                <span class="nbt-row__type">Object · {entries.length}</span>
            </div>
            {#if open && entries.length > 0}
                <div class="nbt-children">
                    {#each entries as [k, v] (k)}
                        <Self value={v} name={k} depth={depth + 1} root={false} wrap={false} />
                    {/each}
                </div>
            {/if}
        </div>
    {:else if isArray}
        {@const homogeneous = listHomogeneous(value)}
        <div class="nbt-node">
            <div
                class={'nbt-row nbt-row--toggle' + (open ? ' is-open' : '')}
                onclick={toggle}
                role="button"
                tabindex="0"
                onkeydown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); toggle(); } }}
            >
                <span class="nbt-row__key">
                    <span class="nbt-chevron" aria-hidden="true">{open ? '▾' : '▸'}</span>
                    {name}
                </span>
                <span class="nbt-row__value nbt-v--composite">
                    {#if value.length === 0}
                        <span class="nbt-empty">[ ]</span>
                    {:else}
                        <span class="nbt-summary">{listSummary(value)}</span>
                    {/if}
                </span>
                <span class="nbt-row__type">List{homogeneous ? '·' + homogeneous : ''} · {value.length}</span>
            </div>
            {#if open && value.length > 0}
                <div class="nbt-children">
                    {#each value as v, i (i)}
                        <Self value={v} name={`[${i}]`} depth={depth + 1} root={false} wrap={false} />
                    {/each}
                </div>
            {/if}
        </div>
    {:else}
        <div class="nbt-row">
            <span class="nbt-row__key">{name}</span>
            <span class="nbt-row__value nbt-v--raw">{String(value)}</span>
            <span class="nbt-row__type">Unknown</span>
        </div>
    {/if}
{/snippet}

{#if wrap}
    <div class="nbt-tree">{@render body()}</div>
{:else}
    {@render body()}
{/if}
