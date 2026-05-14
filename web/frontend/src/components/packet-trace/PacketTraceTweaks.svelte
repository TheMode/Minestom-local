<script lang="ts">
    import { ACCENTS, type AccentKey } from '../../lib/packetTrace.ts';

    type Density = 'compact' | 'normal' | 'roomy';

    interface Props {
        accent: AccentKey;
        density: Density;
        collapse: boolean;
        onAccent: (a: AccentKey) => void;
        onDensity: (d: Density) => void;
        onToggleCollapse: () => void;
        onReset: () => void;
        onClose: () => void;
    }
    let { accent, density, collapse, onAccent, onDensity, onToggleCollapse, onReset, onClose }: Props = $props();
</script>

<div class="float-panel pt-tweaks" role="dialog" aria-label="Tweaks">
    <header>
        <span>tweaks</span>
        <button type="button" onclick={onClose}>✕</button>
    </header>
    <div class="group">
        <h4>Accent</h4>
        <div class="swatches">
            {#each Object.keys(ACCENTS) as id (id)}
                <button
                    type="button"
                    class={accent === id ? 'is-on' : ''}
                    style:background={ACCENTS[id as AccentKey].acc}
                    onclick={() => onAccent(id as AccentKey)}
                    title={id}
                    aria-label={id}
                ></button>
            {/each}
        </div>
    </div>
    <div class="group">
        <h4>Row density</h4>
        <div class="seg-control">
            {#each ['compact', 'normal', 'roomy'] as const as d (d)}
                <button type="button" class={density === d ? 'is-on' : ''} onclick={() => onDensity(d)}>{d}</button>
            {/each}
        </div>
    </div>
    <div class="group">
        <h4>Stream</h4>
        <div class="seg-control">
            <button type="button" class={collapse ? 'is-on' : ''} onclick={onToggleCollapse}>collapse runs</button>
        </div>
    </div>
    <div class="group">
        <button type="button" class="btn sm w-full" onclick={() => { onReset(); onClose(); }}>↺ reset trace</button>
    </div>
</div>
