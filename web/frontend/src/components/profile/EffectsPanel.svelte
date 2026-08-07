<script lang="ts">
    import { toRoman, formatEffectDuration } from '../../lib/util.ts';
    import { effectUrl, prettifyId } from '../../lib/assets.ts';
    import Panel from '../ui/Panel.svelte';

    let { p }: { p: any } = $props();
</script>

{#if Object.values(p.activeEffects || {}).length === 0}
    <Panel title="Effects" meta="none"><div class="empty">No active effects.</div></Panel>
{:else}
    <Panel title="Effects" meta={`${Object.values(p.activeEffects).length} active`}>
        <div class="effects">
            {#each Object.values(p.activeEffects) as e, i (i)}
                {@const url = effectUrl(e.id)}
                {@const secs = Math.round((e.durationTicks || 0) / 20)}
                {@const dur = secs > 9999 ? '∞' : formatEffectDuration(secs)}
                {@const amp = e.amplifier ? toRoman(e.amplifier + 1) : ''}
                <div class="effect-tile" title={`${prettifyId(e.id)}${amp ? ' ' + amp : ''} · ${dur}`}>
                    {#if url}
                        <img src={url} alt={e.id} loading="lazy" draggable={false} />
                    {:else}
                        <div class="mc-icon-fallback">{(e.id || '').replace(/^minecraft:/, '').slice(0, 3)}</div>
                    {/if}
                    {#if amp}<span class="effect-amp">{amp}</span>{/if}
                    <span class="effect-dur">{dur}</span>
                </div>
            {/each}
        </div>
    </Panel>
{/if}
