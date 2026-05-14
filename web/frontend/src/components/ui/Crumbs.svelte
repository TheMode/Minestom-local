<script lang="ts">
    /// Inline breadcrumbs. Render via the `children` snippet — each top-level snippet entry is
    /// joined with a `/` separator. Callers pass an array of snippets via `steps`.
    let { steps = [] } = $props();
</script>

<div class="crumbs">
    {#each steps as step, i}
        <span class="step">
            {@render step()}
            {#if i < steps.length - 1}<span class="sl">/</span>{/if}
        </span>
    {/each}
</div>
