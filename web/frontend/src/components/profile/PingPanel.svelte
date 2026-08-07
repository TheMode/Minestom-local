<script lang="ts">
    import { PING_SERIES } from '../../lib/profile.ts';
    import Panel from '../ui/Panel.svelte';
    import Chart from '../ui/Chart.svelte';
    import ProvValue from './ProvValue.svelte';

    let { p }: { p: any } = $props();
</script>

<Panel title="Latency">
    {#snippet meta()}<ProvValue {p} field="traffic.pingMs" value={String(p.traffic.pingMs)} suffix="ms" />{/snippet}
    <Chart
        series={PING_SERIES}
        data={{ ping: p.traffic.pingHistory }}
        yLabel="ms"
        yFormat={v => Math.round(v) + ''}
        gridX={5}
        gridY={3}
        showAxes
        showLegend={false}
        className="chart-sm"
    />
</Panel>
