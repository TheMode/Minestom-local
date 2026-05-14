<script lang="ts">
    import SkinCanvas from './SkinCanvas.svelte';
    import MinecraftText from '../mctext/MinecraftText.svelte';
    import { prettifyId } from '../../lib/assets.ts';
    import { altCopyClick } from '../../state/mcJsonTooltip.svelte.ts';

    let {
        armor = [],
        main = [],
        hotbar = [],
        offHand,
        cursor,
        selectedHotbar = 0,
        openedWindow = null,
        recentClicks = [],
        profileProperties,
    } = $props();

    const bareId = id => String(id || '').replace(/^minecraft:/, '');

    const isEnchanted = item => {
        const c = item?.components;
        return !!(c && (c.enchantments || c['minecraft:enchantments'] || c.stored_enchantments));
    };

    function durability(item) {
        const c = item?.components;
        const dmg = c?.damage ?? c?.['minecraft:damage'];
        const max = c?.max_damage ?? c?.['minecraft:max_damage'];
        if (dmg == null || !max) return null;
        return Math.max(0, Math.min(1, 1 - dmg / max));
    }
    const durabilityColor = p => p > 0.66 ? 'var(--acc)' : p > 0.33 ? 'var(--warn)' : 'var(--danger)';

    function tipFor(item) {
        const c = item.components || {};
        const name = c.custom_name ?? c['minecraft:custom_name']
                  ?? c.item_name   ?? c['minecraft:item_name'];
        const lore = c.lore || c['minecraft:lore'];
        const ench = c.enchantments || c['minecraft:enchantments'] || c.stored_enchantments;
        return {
            id: item.id || '',
            title: name ?? prettifyId(item.id),
            count: item.count || 1,
            lore: Array.isArray(lore) ? lore : [],
            enchants: ench && typeof ench === 'object'
                ? Object.entries(ench).map(([k, v]) => `${bareId(k)} ${v}`)
                : [],
        };
    }

    let tip = $state(null);
    const onHover = (item, e) => {
        if (e.altKey) { tip = null; return; }
        tip = { data: tipFor(item), x: e.clientX + 12, y: e.clientY + 12 };
    };
    const onLeave = () => { tip = null; };

    // Latest click's content-derived key drives the `{#key}` remount that restarts the slot's
    // CSS animation — including when the same slot is clicked twice in a row (different key
    // each time because `seq` advances).
    let flashTarget = $state(null);

    $effect(() => {
        const last = recentClicks.at(-1);
        if (!last) return;
        const key = `${last.seq}:${last.ts}:${last.rawSlot}`;
        if (flashTarget?.key === key) return;
        flashTarget = { kind: last.kind, idx: last.localSlot, key };
    });

    const flashKeyFor = (kind, idx) =>
        flashTarget && flashTarget.kind === kind && flashTarget.idx === idx ? flashTarget.key : null;

    // Container windows snap to a 9-wide grid; furnace/hopper/crafting are narrower. The CSS
    // uses `--w` for the column count, so this is the only protocol-aware decision in layout.
    function gridWidthFor(slotCount) {
        if (slotCount <= 0) return 9;
        if (slotCount % 9 === 0) return 9;
        if (slotCount === 5) return 5;   // hopper
        if (slotCount === 3) return 3;   // furnace
        if (slotCount === 10) return 3;  // crafting (3x3 + result trailing)
        return Math.min(slotCount, 9);
    }
</script>

{#snippet itemIcon(id)}
    {@const bare = bareId(id)}
    {#if bare}
        <img
            class="mc-icon"
            src={`/api/material-icon/${bare}`}
            alt=""
            loading="lazy"
            draggable={false}
            onerror={e => { e.target.replaceWith(Object.assign(document.createElement('span'), { className: 'mc-icon-fallback', textContent: bare.slice(0, 3) })); }}
        />
    {/if}
{/snippet}

{#snippet flashOverlay(flashKey)}
    {#if flashKey}
        {#key flashKey}
            <span class="mc-slot-flash" aria-hidden="true"></span>
        {/key}
    {/if}
{/snippet}

{#snippet slot(item, kind, idx, extraClass = '')}
    {@const flashKey = flashKeyFor(kind, idx)}
    {#if !item || !item.id}
        <div class="mc-slot {extraClass}" data-kind={kind} data-idx={idx}>
            {@render flashOverlay(flashKey)}
        </div>
    {:else}
        {@const dur = durability(item)}
        <div
            class="mc-slot has-item {extraClass}"
            class:enchanted={isEnchanted(item)}
            data-kind={kind}
            data-idx={idx}
            onmouseenter={e => onHover(item, e)}
            onmousemove={e => onHover(item, e)}
            onmouseleave={onLeave}
            onclickcapture={(e) => altCopyClick(e, item, 'Item JSON copied')}
            role="img"
        >
            {@render itemIcon(item.id)}
            {#if item.count > 1}<span class="mc-count">{item.count}</span>{/if}
            {#if dur != null}
                <span
                    class="mc-durability"
                    style:--dur={`${(dur * 100).toFixed(0)}%`}
                    style:--dur-color={durabilityColor(dur)}
                ></span>
            {/if}
            {@render flashOverlay(flashKey)}
        </div>
    {/if}
{/snippet}

<div class="mc-inv-wrap">
    <div class="mc-inv-column">
        {#if openedWindow}
            {@const slots = openedWindow.slots || []}
            {@const w = gridWidthFor(slots.length)}
            {@const typeLabel = prettifyId(openedWindow.type) || 'window'}
            <section class="mc-open-window" aria-label="Open container">
                <header class="mc-open-window__head">
                    <span class="mc-open-window__dot" aria-hidden="true"></span>
                    <span class="mc-open-window__label">Open container</span>
                    <span class="mc-open-window__title"><MinecraftText value={openedWindow.title} /></span>
                    <span class="mc-open-window__meta">
                        <span class="mc-open-window__type">{typeLabel}</span>
                        <span class="mc-open-window__size">{slots.length} slot{slots.length === 1 ? '' : 's'}</span>
                        <span class="mc-open-window__id">id {openedWindow.id}</span>
                    </span>
                </header>
                {#if slots.length === 0}
                    <div class="mc-open-window__empty">awaiting first Window-Items packet…</div>
                {:else}
                    <div class="mc-open-window__grid" style:--w={w}>
                        {#each slots as it, i (i)}
                            {@render slot(it, 'container', i)}
                        {/each}
                    </div>
                {/if}
            </section>
            <div class="mc-inv-stage__hint">
                Player inventory · live mirror while {typeLabel} is open
            </div>
        {/if}
        <div class="mc-inv-stage" class:mc-inv-stage--ghosted={!!openedWindow}>
            <div class="mc-inv-grid mc-inv-armor">
                {#each Array(4) as _, i (i)}
                    {@render slot(armor[i], 'armor', i)}
                {/each}
            </div>
            <div class="mc-inv-skin">
                <SkinCanvas {profileProperties} />
            </div>
            <div class="mc-inv-grid mc-inv-offhand">
                {@render slot(offHand, 'offhand', 0)}
            </div>
            <div class="mc-inv-grid mc-inv-main">
                {#each Array(27) as _, i (i)}
                    {@render slot(main[i], 'main', i)}
                {/each}
            </div>
            <div class="mc-inv-grid mc-inv-hot">
                {#each Array(9) as _, i (i)}
                    {@render slot(hotbar[i], 'hotbar', i, i === selectedHotbar ? 'selected' : '')}
                {/each}
            </div>
        </div>
    </div>
    <aside class="mc-cursor" class:mc-cursor--empty={!cursor?.id} aria-hidden={!cursor?.id}>
        <div class="mc-cursor-label">Cursor</div>
        <div class="mc-cursor-slot">
            {#if cursor?.id}
                {@render slot(cursor, 'cursor', 0)}
            {/if}
        </div>
    </aside>
    {#if tip?.data}
        <div class="mc-tip on" style:left={tip.x + 'px'} style:top={tip.y + 'px'}>
            <div class="mc-tip-name"><MinecraftText value={tip.data.title} /></div>
            {#each tip.data.lore as l, i (i)}
                <div class="mc-tip-lore"><MinecraftText value={l} /></div>
            {/each}
            {#each tip.data.enchants as e, i (i)}
                <div class="mc-tip-ench">{e}</div>
            {/each}
            <div class="mc-tip-id">{tip.data.id}</div>
        </div>
    {/if}
</div>
