<script lang="ts">
    import type { Snippet } from 'svelte';

    type PanelValue = string | number | Snippet;

    let {
        title = undefined,
        meta = undefined,
        actions = undefined,
        flush = false,
        headless = false,
        className = '',
        children = undefined,
    }: {
        title?: PanelValue;
        meta?: PanelValue;
        actions?: Snippet;
        flush?: boolean;
        headless?: boolean;
        className?: string;
        children?: Snippet;
    } = $props();

    const titleIsSnippet = $derived(typeof title === 'function');
    const metaIsSnippet = $derived(typeof meta === 'function');
</script>

<section class="panel {className}">
    {#if !headless}
        <header>
            {#if title != null}
                <h2>
                    {#if titleIsSnippet}{@render title()}{:else}{title}{/if}
                </h2>
            {/if}
            <div class="row gap-sm">
                {#if meta != null}
                    <span class="meta">
                        {#if metaIsSnippet}{@render meta()}{:else}{meta}{/if}
                    </span>
                {/if}
                {#if actions}{@render actions()}{/if}
            </div>
        </header>
    {/if}
    <div class="panel-body" class:flush={flush}>
        {@render children?.()}
    </div>
</section>
