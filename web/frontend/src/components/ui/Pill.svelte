<script lang="ts">
    import type { Snippet } from 'svelte';

    let {
        kind = 'ghost',
        dot = false,
        children,
    }: {
        kind?: string;
        dot?: boolean;
        children?: Snippet;
    } = $props();
</script>

<span class="pill {kind}">
    {#if dot}<span class="dot"></span>{/if}
    {@render children?.()}
</span>
