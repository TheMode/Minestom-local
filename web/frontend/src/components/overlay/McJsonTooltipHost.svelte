<script lang="ts">
    import { floatingTipStyle } from '../../lib/floatingPopover.svelte.ts';
    import { mcJsonTooltip } from '../../state/mcJsonTooltip.svelte.ts';

    const W = 520;
    const H = 280;

    let style = $derived(mcJsonTooltip.tip
        ? floatingTipStyle(mcJsonTooltip.tip.x, mcJsonTooltip.tip.y, W, H)
        : '');
</script>

{#if mcJsonTooltip.tip}
    <div class="mc-json-tip" style={style} role="tooltip">
        <pre>{mcJsonTooltip.tip.text}</pre>
    </div>
{/if}

<style>
    @layer pages {
        :global {
    /* Alt-hover JSON inspector for Minecraft text components. */
    .mc-json-tip {
        position: fixed;
        z-index: 200;
        pointer-events: none;
        padding: 8px 10px;
        background: var(--bg-0);
        border: 1px solid var(--line);
        box-shadow: var(--float-2);
        color: var(--ink-2);
        font-size: var(--t-xs);
        line-height: 1.4;
        max-width: min(520px, 92vw);
        max-height: min(280px, 50vh);
        overflow: auto;
        pre {
            margin: 0;
            font-family: inherit;
            font-size: inherit;
            color: var(--ink-2);
            white-space: pre-wrap;
            word-break: break-word;
        }
    }
        }
    }
</style>
