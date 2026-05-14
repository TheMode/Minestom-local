<script lang="ts">
    import { HEART_SPRITES, FOOD_SPRITES } from '../../lib/profile.ts';
    import Panel from '../ui/Panel.svelte';
    import Pill from '../ui/Pill.svelte';
    import ProgressBar from '../ui/ProgressBar.svelte';
    import ProvValue from './ProvValue.svelte';

    let { p }: { p: any } = $props();
</script>

{#snippet hearts(value, max, hardcore)}
    {#each Array(Math.max(1, Math.ceil(max / 2))) as _, i (i)}
        {@const remaining = value - i * 2}
        {@const sprites = hardcore
            ? { empty: HEART_SPRITES.hcEmpty, full: HEART_SPRITES.hcFull, half: HEART_SPRITES.hcHalf }
            : { empty: HEART_SPRITES.empty,   full: HEART_SPRITES.full,   half: HEART_SPRITES.half  }}
        {@const layer = remaining >= 2 ? sprites.full : remaining >= 1 ? sprites.half : null}
        {#if layer}
            <span class="icon-stack"><img src={sprites.empty} alt="" draggable={false} /><img src={layer} alt="" draggable={false} /></span>
        {:else}
            <img src={sprites.empty} alt="" draggable={false} />
        {/if}
    {/each}
{/snippet}

{#snippet foodIcons(value)}
    {#each Array(10) as _, i (i)}
        {@const remaining = value - i * 2}
        {@const layer = remaining >= 2 ? FOOD_SPRITES.full : remaining >= 1 ? FOOD_SPRITES.half : null}
        {#if layer}
            <span class="icon-stack"><img src={FOOD_SPRITES.empty} alt="" draggable={false} /><img src={layer} alt="" draggable={false} /></span>
        {:else}
            <img src={FOOD_SPRITES.empty} alt="" draggable={false} />
        {/if}
    {/each}
{/snippet}

<Panel title="Vitals">
    <div class="gauge">
        <span class="gauge-label">HP</span>
        <span class="value-num">
            <ProvValue {p} field="health" value={(p.health ?? 0).toFixed(1)} suffix={`/${(p.maxHealth ?? 20).toFixed(0)}`} />
        </span>
        <span class="gauge-icons">{@render hearts(p.health || 0, p.maxHealth || 20, p.hardcore)}</span>
    </div>
    <div class="gauge">
        <span class="gauge-label">Food</span>
        <span class="value-num">
            <ProvValue {p} field="food" value={String(p.food ?? 0)} suffix={`/20 · sat ${(p.saturation ?? 0).toFixed(1)}`} />
        </span>
        <span class="gauge-icons">{@render foodIcons(p.food || 0)}</span>
    </div>
    <div class="gauge">
        <span class="gauge-label">XP · Lvl <ProvValue {p} field="xpLevel" value={String(p.xpLevel ?? 0)} /></span>
        <span class="value-num"><ProvValue {p} field="xpBar" value={Math.round((p.xpBar || 0) * 100) + '%'} /></span>
        <ProgressBar value={p.xpBar ?? 0} class="progress-bar--gauge" />
    </div>
    <div class="row gap-sm mt-sm">
        {#if p.flying}<Pill kind="on">flying</Pill>{/if}
        {#if p.invulnerable}<Pill kind="on">invuln</Pill>{/if}
        {#if p.allowFlying}<Pill>may fly</Pill>{/if}
        {#if p.onGround}<Pill>grounded</Pill>{:else}<Pill>airborne</Pill>{/if}
    </div>
</Panel>
