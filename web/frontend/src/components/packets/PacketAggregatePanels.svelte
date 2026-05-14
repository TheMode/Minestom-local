<script lang="ts">
    import Leaderboard from './Leaderboard.svelte';
    import Heatmap from './Heatmap.svelte';
    import Panel from '../ui/Panel.svelte';

    let {
        agg,
        sortBy,
        onSortBy,
        version = 0,
        topMeta = '',
        heatmapMeta = sortBy,
        max = 14,
    } = $props();
</script>

{#snippet countBytesToggle()}
    <div class="seg-control">
        <button type="button" class:is-on={sortBy === 'count'} onclick={() => onSortBy('count')}>Count</button>
        <button type="button" class:is-on={sortBy === 'bytes'} onclick={() => onSortBy('bytes')}>Bytes</button>
    </div>
{/snippet}

<Panel title="Top packet classes" meta={topMeta} flush>
    {#snippet actions()}{@render countBytesToggle()}{/snippet}
    <Leaderboard {agg} {sortBy} {max} {version} />
</Panel>
<Panel title="Bandwidth · direction × subject" meta={heatmapMeta} flush>
    {#snippet actions()}{@render countBytesToggle()}{/snippet}
    <Heatmap {agg} {sortBy} {version} />
</Panel>
