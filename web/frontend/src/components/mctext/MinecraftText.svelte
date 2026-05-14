<script lang="ts">
    import Node from './MinecraftTextNode.svelte';
    import { altCopyClick, mcJsonTooltip } from '../../state/mcJsonTooltip.svelte.ts';

    let { value, className = '' } = $props();
    const cls = $derived(('mc-component ' + className).trim());
</script>

{#if value != null && value !== ''}
    <span
        class={cls}
        role="presentation"
        onpointerenter={(e) => mcJsonTooltip.track(value, e)}
        onpointermove={(e) => mcJsonTooltip.track(value, e)}
        onpointerleave={() => mcJsonTooltip.track(null, null)}
        onclickcapture={(e) => altCopyClick(e, value, 'Text JSON copied')}
    >
        <Node node={value} />
    </span>
{/if}
