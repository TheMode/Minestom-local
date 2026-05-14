<script lang="ts">
    import { tweaks, ACCENT_OPTIONS } from '../../state/tweaks.svelte.ts';
    import Toggle from './Toggle.svelte';

    let { onClose }: { onClose: () => void } = $props();

    $effect(() => {
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    });

    const t = $derived(tweaks.value);
</script>

<div class="tweaks" role="dialog" aria-label="Tweaks">
    <header>
        <h2>Tweaks</h2>
        <button class="ghost sm" onclick={onClose}>×</button>
    </header>
    <div class="body">
        <div>
            <span class="tlabel">Accent</span>
            <div class="swatches">
                {#each ACCENT_OPTIONS as o (o.key)}
                    <button
                        type="button"
                        class="sw"
                        class:active={o.key === t.accent}
                        style:background={o.acc}
                        title={o.key}
                        aria-label={o.key}
                        onclick={() => tweaks.set({ accent: o.key })}
                    ></button>
                {/each}
            </div>
        </div>
        <div>
            <span class="tlabel">Density</span>
            <div class="seg-control">
                {#each ['compact', 'comfortable', 'roomy'] as d (d)}
                    <button
                        type="button"
                        class:is-on={d === t.density}
                        onclick={() => tweaks.set({ density: d })}
                    >{d}</button>
                {/each}
            </div>
        </div>
        <div>
            <span class="tlabel">Pixel charm</span>
            <div class="row between mb-xs">
                <span class="small dim">Glint animation</span>
                <Toggle on={t.glint} onchange={glint => tweaks.set({ glint })} />
            </div>
            <div class="row between">
                <span class="small dim">HUD scanlines</span>
                <Toggle on={t.hudScan} onchange={hudScan => tweaks.set({ hudScan })} />
            </div>
        </div>
        <div>
            <span class="tlabel">Players list</span>
            <div class="row between">
                <span class="small dim">Show disconnected</span>
                <Toggle on={t.showDisconnected} onchange={showDisconnected => tweaks.set({ showDisconnected })} />
            </div>
        </div>
        <div class="small dim">Changes apply live. They affect every page.</div>
    </div>
</div>
