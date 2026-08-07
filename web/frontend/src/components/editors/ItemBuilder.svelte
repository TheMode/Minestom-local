<script module lang="ts">
    import { api } from '../../lib/api.ts';

    export type ItemValue = Record<string, unknown> & { id?: string; count?: number; components?: Record<string, unknown> };

    let materialsPromise: Promise<string[]> | null = null;

    export function loadMaterials(): Promise<string[]> {
        if (!materialsPromise) {
            materialsPromise = api<string[]>('/materials')
                .then(keys => keys.map(k => 'minecraft:' + k).sort())
                .catch(e => { materialsPromise = null; throw e; });
        }
        return materialsPromise;
    }

    /// Data components surfaced inline. Restricted to keys whose JSON shape matches a
    /// straightforward widget — Codec.UNIT components like `unbreakable`, structured
    /// records like `enchantments`/`tooltip_display`, and integer-typed leaves like
    /// `damage` need their own typed editors and round-trip support before they can be
    /// offered here without silently breaking the wire format.
    type DCKind = 'enum' | 'component' | 'list-component';
    type DCSpec = { key: string; kind: DCKind; label: string; values?: string[] };

    export const ITEM_DATA_COMPONENTS: DCSpec[] = [
        { key: 'custom_name',  kind: 'component',      label: 'custom_name' },
        { key: 'item_name',    kind: 'component',      label: 'item_name' },
        { key: 'lore',         kind: 'list-component', label: 'lore' },
        { key: 'rarity',       kind: 'enum',           label: 'rarity', values: ['COMMON', 'UNCOMMON', 'RARE', 'EPIC'] },
    ];

    export function dcDefault(spec: DCSpec): unknown {
        switch (spec.kind) {
            case 'enum':           return spec.values?.[0] ?? '';
            case 'component':      return { text: '' };
            case 'list-component': return [];
        }
    }
</script>

<script lang="ts">
    import MaterialPickerPopover from './MaterialPickerPopover.svelte';
    import ComponentBuilder from './ComponentBuilder.svelte';
    import ListEditor from './ListEditor.svelte';
    import { libraryDrop } from '../../lib/libraryDrop.svelte.ts';
    import { stripNamespace } from '../../lib/util.ts';

    type Props = {
        value: ItemValue | null;
        onChange: (v: ItemValue) => void;
    };

    let { value, onChange }: Props = $props();

    const safe = $derived((value && typeof value === 'object' ? value : {}) as ItemValue);
    const id = $derived(typeof safe.id === 'string' ? safe.id : '');
    const cleanId = $derived(stripNamespace(id));
    const count = $derived(Number(safe.count ?? 1));
    const components = $derived((safe.components as Record<string, unknown>) ?? {});
    const usedKeys = $derived(Object.keys(components));
    const iconUrl = $derived(cleanId ? `/api/material-icon/${cleanId}` : '');

    let matBtn = $state<HTMLButtonElement | null>(null);
    let pickMat = $state(false);
    let addDCOpen = $state(false);

    function patch(next: Partial<ItemValue>) {
        const merged = { ...safe, ...next } as ItemValue;
        onChange(merged);
    }

    /// Unknown keys (not declared in [ITEM_DATA_COMPONENTS]) keep their raw value untouched —
    /// they're displayed as read-only so the user doesn't accidentally clobber a structured
    /// blob (`attribute_modifiers`, `potion_contents`, …) by typing in a string editor.
    function specFor(key: string): DCSpec | null {
        return ITEM_DATA_COMPONENTS.find(s => s.key === key) ?? null;
    }

    function setComp(key: string, val: unknown | null) {
        const c = { ...components };
        if (val == null) delete c[key];
        else c[key] = val;
        const next = { ...safe } as ItemValue;
        if (Object.keys(c).length === 0) delete next.components;
        else next.components = c;
        onChange(next);
    }

    function adjustCount(delta: number) {
        patch({ count: Math.max(1, Math.min(99, count + delta)) });
    }

    const drop = libraryDrop('items', (v) => onChange(v as ItemValue));
</script>

<div
    class="ib-builder builder {drop.over ? 'drop-over' : ''}"
    role="region"
    {...drop.handlers}
>
    <div class="builder__head">
        <span class="builder__title"><span class="builder__title-dot"></span>item stack</span>
        {#if usedKeys.length > 0}
            <span class="builder__tag">+{usedKeys.length} component{usedKeys.length === 1 ? '' : 's'}</span>
        {/if}
    </div>
    <div class="ib-builder__body">
        <div class="ib-id">
            <button
                type="button"
                class="ib-id__icon-btn"
                title="Pick material"
                bind:this={matBtn}
                onclick={() => pickMat = !pickMat}
            >
                {#if iconUrl}
                    <img class="ib-id__icon" src={iconUrl} alt="" draggable={false} />
                {:else}
                    <span class="ib-id__icon ib-id__icon--placeholder">?</span>
                {/if}
            </button>
            <input
                type="text"
                class="ib-id__input"
                value={id}
                spellcheck="false"
                placeholder="minecraft:stone"
                oninput={(e) => patch({ id: (e.currentTarget as HTMLInputElement).value })}
            />
            <span class="ib-count">
                <button type="button" class="ib-count__btn" onclick={() => adjustCount(-1)} aria-label="Decrease">−</button>
                <input
                    type="number"
                    min="1"
                    max="99"
                    value={count}
                    oninput={(e) => patch({ count: Math.max(1, Math.min(99, Number((e.currentTarget as HTMLInputElement).value) || 1)) })}
                />
                <button type="button" class="ib-count__btn" onclick={() => adjustCount(1)} aria-label="Increase">+</button>
            </span>
        </div>

        {#if usedKeys.length > 0}
            <div class="dc-list">
                {#each usedKeys as key (key)}
                    {@const spec = specFor(key)}
                    <div class="dc-row">
                        <span class="dc-row__key">{spec?.label ?? key}</span>
                        <div class="dc-row__slot">
                            {#if spec?.kind === 'enum' && spec.values}
                                <select
                                    value={String(components[key] ?? spec.values[0])}
                                    onchange={(e) => setComp(key, (e.currentTarget as HTMLSelectElement).value)}
                                >
                                    {#each spec.values as v (v)}<option value={v}>{v}</option>{/each}
                                </select>
                            {:else if spec?.kind === 'component'}
                                <ComponentBuilder
                                    value={(components[key] as Record<string, unknown>) ?? null}
                                    onChange={(v) => setComp(key, v)}
                                />
                            {:else if spec?.kind === 'list-component'}
                                <ListEditor
                                    value={(components[key] as unknown[]) ?? []}
                                    onChange={(v) => setComp(key, v)}
                                    element={{ name: 'line', kind: 'component' }}
                                />
                            {:else}
                                <span class="dc-row__opaque" title="No inline editor — value is preserved as-is.">
                                    {JSON.stringify(components[key])}
                                </span>
                            {/if}
                        </div>
                        <button type="button" class="dc-row__del" title="Remove" onclick={() => setComp(key, null)}>×</button>
                    </div>
                {/each}
            </div>
        {/if}

        <div class="dc-add">
            <button
                type="button"
                class="ghost sm"
                onclick={() => addDCOpen = !addDCOpen}
            >+ data component</button>
            {#if addDCOpen}
                <div class="dc-menu" role="menu">
                    {#each ITEM_DATA_COMPONENTS as spec (spec.key)}
                        {@const used = usedKeys.includes(spec.key)}
                        <button
                            type="button"
                            class="dc-menu__item"
                            disabled={used}
                            onclick={() => { setComp(spec.key, dcDefault(spec)); addDCOpen = false; }}
                        >
                            <span class="dc-menu__name">{spec.label}</span>
                            <span class="dc-menu__kind">{spec.kind}</span>
                        </button>
                    {/each}
                </div>
            {/if}
        </div>
    </div>
</div>

{#if pickMat && matBtn}
    <MaterialPickerPopover
        value={id}
        anchor={matBtn}
        onPick={(next) => patch({ id: next })}
        onClose={() => pickMat = false}
    />
{/if}
