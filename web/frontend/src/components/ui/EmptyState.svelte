<script lang="ts">
    import type { Snippet } from 'svelte';

    let { title, hint, cta }: {
        title: string;
        hint?: string;
        cta?: Snippet;
    } = $props();
</script>

<div class="empty empty-state">
    <div class="empty-state__title">{title}</div>
    {#if hint}<div class="empty-state__hint">{hint}</div>{/if}
    {#if cta}<div class="empty-state__cta">{@render cta()}</div>{/if}
</div>
