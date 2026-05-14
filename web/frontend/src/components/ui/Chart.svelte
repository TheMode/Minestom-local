<script lang="ts">
    import { ChartCore } from '../../lib/charts.ts';

    let {
        series,
        data,
        xValues = null,
        yLabel,
        yFormat,
        xFormat,
        padding,
        gridX,
        gridY,
        showLegend,
        showAxes,
        className = '',
        style = '',
    } = $props();

    let host;
    let core = null;

    $effect(() => {
        core = new ChartCore(host, { series, yLabel, yFormat, xFormat, padding, gridX, gridY, showLegend, showAxes });
        return () => core?.destroy();
    });

    $effect(() => {
        core?.set(data, xValues);
    });
</script>

<div bind:this={host} class={className} {style}></div>
