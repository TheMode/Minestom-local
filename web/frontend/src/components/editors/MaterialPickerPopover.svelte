<script lang="ts">
    import { loadMaterials } from './ItemBuilder.svelte';
    import { anchoredPopover } from '../../lib/floatingPopover.svelte.ts';
    import { stripNamespace } from '../../lib/util.ts';

    type Props = {
        value: string;
        anchor: HTMLElement;
        onPick: (id: string) => void;
        onClose: () => void;
    };

    let { value, anchor, onPick, onClose }: Props = $props();

    let materials = $state<string[]>([]);
    let q = $state('');
    let pop = $state<HTMLDivElement | null>(null);

    $effect(() => { loadMaterials().then(m => materials = m).catch(() => {}); });

    const { pos } = anchoredPopover<HTMLDivElement>(
        () => anchor,
        () => pop,
        (r) => ({ left: r.left, top: r.bottom + 4 }),
        () => onClose(),
    );

    const ql = $derived(q.toLowerCase().trim());
    const filtered = $derived(ql ? materials.filter(m => m.includes(ql)) : materials);
    const clean = $derived(stripNamespace(value));
</script>

<div bind:this={pop} class="mat-picker" style:left="{pos.left}px" style:top="{pos.top}px">
    <div class="mat-picker__head">
        <span class="mat-picker__title">MATERIAL</span>
        <input
            type="text"
            placeholder="filter…"
            value={q}
            oninput={(e) => q = (e.currentTarget as HTMLInputElement).value}
        />
    </div>
    <div class="mat-grid">
        {#each filtered as id (id)}
            {@const sid = stripNamespace(id)}
            <button
                type="button"
                class="mat-grid__cell {sid === clean ? 'is-on' : ''}"
                title={sid}
                onclick={() => { onPick(id); onClose(); }}
            >
                <img src={`/api/material-icon/${sid}`} alt="" loading="lazy" draggable={false} />
            </button>
        {/each}
    </div>
    <div class="mat-picker__count">
        {filtered.length} of {materials.length}
    </div>
    <input
        type="text"
        class="mat-picker__id"
        placeholder="minecraft:stone"
        value={value}
        oninput={(e) => onPick((e.currentTarget as HTMLInputElement).value)}
        spellcheck="false"
    />
</div>
