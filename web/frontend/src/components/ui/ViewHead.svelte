<script lang="ts">
    import type { Snippet } from 'svelte';
    import Crumbs from './Crumbs.svelte';

    /// Standard view masthead: a breadcrumb trail + `<h1>` title on the left, optional actions on
    /// the right. `crumbs` is the snippet array passed straight to [Crumbs]; `title` renders the
    /// `<h1>` body (so callers keep their `<em>` accents); `subtitle` renders any descriptive line
    /// below the title; `actions` fills the right-hand slot.
    let { crumbs = [], title, subtitle, actions }: {
        crumbs?: Snippet[];
        title: Snippet;
        subtitle?: Snippet;
        actions?: Snippet;
    } = $props();
</script>

<div class="view-head">
    <div>
        <Crumbs steps={crumbs} />
        <h1>{@render title()}</h1>
        {#if subtitle}{@render subtitle()}{/if}
    </div>
    {#if actions}
        <div class="view-actions">{@render actions()}</div>
    {/if}
</div>
