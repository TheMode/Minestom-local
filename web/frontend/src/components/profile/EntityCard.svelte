<script lang="ts">
    import type { Snippet } from 'svelte';

    type Detail = string | number | Snippet;

    let {
        icon,
        title,
        badges,
        detail,
        actions,
        off = false,
    }: {
        icon?: Snippet;
        title: string;
        badges?: Snippet;
        detail?: Detail;
        actions?: Snippet;
        off?: boolean;
    } = $props();

    const detailIsSnippet = $derived(typeof detail === 'function');
</script>

<div class="entity-card" class:is-off={off}>
    {#if icon != null}<div class="entity-card__ico">{@render icon()}</div>{/if}
    <div class="entity-card__body">
        <div class="entity-card__head">
            <span class="entity-card__title">{title}</span>
            {#if badges}{@render badges()}{/if}
        </div>
        {#if detail != null}
            <div class="entity-card__detail">
                {#if detailIsSnippet}{@render detail()}{:else}{detail}{/if}
            </div>
        {/if}
    </div>
    {#if actions != null}<div class="entity-card__actions">{@render actions()}</div>{/if}
</div>
