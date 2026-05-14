<script lang="ts">
    import Self from './MinecraftTextNode.svelte';
    import {
        flattenMc, runNeedsSpan, asMcObject, classesFor, styleFor, clickTitle, hoverBody,
        type McRun,
    } from '../../lib/minecraftText.ts';

    let { node } = $props();
    const runs = $derived(flattenMc(node));
</script>

{#snippet runEl(run: McRun)}
    {#if run.kind === 'icon'}
        {#if run.head}
            <span class="mc-inline-icon mc-inline-icon--head" title={run.title} aria-hidden="true"></span>
        {:else}
            <span class="mc-inline-icon" title={run.title}>
                <img src={`/api/material-icon/${run.id}`} alt="" loading="lazy" draggable={false} />
            </span>
        {/if}
    {:else if runNeedsSpan(run)}
        <span
            class={classesFor(run.style, run.hover, run.click)}
            style={styleFor(run.style)}
            title={run.click ? clickTitle(run.click) : undefined}
        >{run.text}{#if run.hover}{@render hoverTip(run.hover)}{/if}</span>
    {:else}
        {run.text}
    {/if}
{/snippet}

{#snippet hoverTip(hover: Record<string, unknown>)}
    {@const body = hoverBody(hover)}
    <span class="mc-hover-tip" role="tooltip">
        {#if hover.action === 'show_text' || typeof body === 'string'
            || asMcObject(body)?.text != null || asMcObject(body)?.translate != null
            || Array.isArray(body)}
            <Self node={body} />
        {:else if hover.action === 'show_item' || asMcObject(body)?.id != null}
            {@const item = asMcObject(body)!}
            <div class="hov-title">{String(item.id ?? '?')}</div>
            {#if (item.count ?? 1) > 1}<div class="hov-sub">×{item.count}</div>{/if}
        {:else if hover.action === 'show_entity' || asMcObject(body)?.type != null}
            {@const ent = asMcObject(body)!}
            <div class="hov-title">{String(ent.type ?? 'entity')}</div>
            {#if ent.name}<div class="hov-sub"><Self node={ent.name} /></div>{/if}
        {:else}
            <pre class="hov-raw">{JSON.stringify(body, null, 2)}</pre>
        {/if}
    </span>
{/snippet}

{#each runs as run, i (i)}{@render runEl(run)}{/each}
