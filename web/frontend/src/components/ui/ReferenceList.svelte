<script lang="ts">
    import Panel from './Panel.svelte';

    type Item = { name: string; kind: string; detail?: string };

    let { title, items }: { title: string; items: Item[] } = $props();
</script>

<Panel {title} meta={String(items.length || '—')}>
    <div class="reference-grid">
        {#if items.length === 0}
            <div class="empty">Loading…</div>
        {:else}
            {#each items as it (it.name)}
                <div class="reference-card">
                    <div class="ref-head">
                        <span class="ref-name">{it.name}</span>
                        <span class="ref-kind">{it.kind}</span>
                    </div>
                    <div class="ref-doc">{it.detail || ''}</div>
                </div>
            {/each}
        {/if}
    </div>
</Panel>
