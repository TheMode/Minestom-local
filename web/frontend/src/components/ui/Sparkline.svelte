<script lang="ts">
    import { SparklineCore } from '../../lib/charts.ts';

    let { data, color = 'var(--acc)', fill, max = 60, className = '', style = '' } = $props();

    let canvas;
    let core = null;

    $effect(() => {
        core = new SparklineCore(canvas, { color, fill, max });
        return () => core?.destroy();
    });

    $effect(() => {
        core?.set(data || []);
    });
</script>

<canvas bind:this={canvas} class={className} {style}></canvas>
