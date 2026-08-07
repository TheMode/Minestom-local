<script lang="ts">
    import Panel from '../ui/Panel.svelte';
    import NbtTree from './NbtTree.svelte';

    let { p }: { p: any } = $props();
</script>

{#if Object.keys(p.serverData || {}).length === 0}
    <Panel title="Server data" meta="0 keys">
        <div class="empty">No server data pushed for this player.</div>
    </Panel>
{:else}
    <Panel title="Server data" meta={`${Object.keys(p.serverData).length} keys`}>
        <NbtTree value={p.serverData} name="root" />
        <p class="dim small mt">Available in MQL as <code>server.*</code>.</p>
    </Panel>
{/if}
