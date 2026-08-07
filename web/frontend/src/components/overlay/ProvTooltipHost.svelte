<script lang="ts">
    import { fmtAge, shortClass } from '../../lib/util.ts';
    import { isClientBound } from '../../lib/packetAgg.ts';
    import { busStatus } from '../../state/bus.svelte.ts';
    import { provTooltip } from '../../state/provTooltip.svelte.ts';

    let pop = $state<HTMLElement | null>(null);
    /// Resolved placement. `ready` gates visibility until the first measure lands, so the box is
    /// never painted at its pre-positioned origin.
    let box = $state({ left: 0, top: 0, caret: 16, below: false, ready: false });

    const tip = $derived(provTooltip.state);
    const src = $derived(tip?.source ?? null);
    const ageMs = $derived(src?.ts ? busStatus.now - src.ts : null);
    const dir = $derived((src?.direction || '').toUpperCase());
    const dirGlyph = $derived(isClientBound(dir) ? '◀' : dir.startsWith('SERVER') ? '▶' : '');
    const dirKind = $derived(isClientBound(dir) ? 'cb' : dir ? 'sb' : '');

    // Measure the rendered box and place it above the anchor, flipping below near the top edge
    // and clamping horizontally. Reads only `provTooltip.state`/`pop` — never `box` — so the
    // write below can't feed back into this effect.
    $effect(() => {
        const t = provTooltip.state;
        const el = pop;
        if (!t || !el) { box.ready = false; return; }
        const w = el.offsetWidth;
        const h = el.offsetHeight;
        const gap = 8;
        let below = false;
        let top = t.anchor.top - gap - h;
        if (top < 8) { below = true; top = t.anchor.bottom + gap; }
        const left = Math.min(Math.max(8, t.anchor.cx - w / 2), window.innerWidth - w - 8);
        const caret = Math.min(Math.max(12, t.anchor.cx - left), w - 12);
        box = { left, top, caret, below, ready: true };
    });

    // The tooltip is anchored to a fixed box, so any scroll/resize invalidates its placement —
    // cheapest correct response is to dismiss it.
    $effect(() => {
        if (!provTooltip.state) return;
        const hide = () => provTooltip.hide();
        window.addEventListener('scroll', hide, true);
        window.addEventListener('resize', hide);
        return () => {
            window.removeEventListener('scroll', hide, true);
            window.removeEventListener('resize', hide);
        };
    });
</script>

{#if tip}
    <div
        class="prov-tip"
        class:prov-tip--below={box.below}
        class:prov-tip--ready={box.ready}
        bind:this={pop}
        style:left="{box.left}px"
        style:top="{box.top}px"
        role="tooltip"
    >
        {#if src}
            <div class="prov-tip__head">
                {#if dirGlyph}<span class="prov-tip__dir" data-dir={dirKind}>{dirGlyph}</span>{/if}
                <span class="prov-tip__pkt">{shortClass(src.packetClass).replace(/Packet$/, '') || 'unknown'}</span>
                {#if src.seq != null}<span class="prov-tip__seq">#{src.seq}</span>{/if}
            </div>
            <div class="prov-tip__sub">
                <span class="prov-tip__age">{fmtAge(ageMs)} ago</span>
                <span class="prov-tip__field">{tip.field}</span>
            </div>
        {:else}
            <div class="prov-tip__head"><span class="prov-tip__pkt prov-tip__pkt--none">no source recorded</span></div>
            <div class="prov-tip__sub"><span class="prov-tip__field">{tip.field}</span></div>
        {/if}
        <div class="prov-tip__hint">Click to pin history</div>
        <span class="prov-tip__caret" style:left="{box.caret}px"></span>
    </div>
{/if}

<style>
    @layer pages {
        :global {
    /* Floating provenance readout — anchored to a value badge, never steals the pointer so it
     * can't flicker against the badge's own hover. */
    .prov-tip {
        position: fixed;
        z-index: 210;
        pointer-events: none;
        visibility: hidden;
        min-width: 132px;
        max-width: 280px;
        padding: 6px 9px 7px;
        background: var(--bg-1);
        border: 1px solid var(--line-2);
        border-radius: 4px;
        box-shadow: var(--bevel), var(--float-2);
        color: var(--ink-2);
        line-height: 1;
    }
    .prov-tip--ready { visibility: visible; }

    .prov-tip__head {
        display: flex;
        align-items: baseline;
        gap: 6px;
        font-size: var(--t-sm);
    }
    .prov-tip__dir {
        font-size: var(--t-xs);
        line-height: 1;
        &[data-dir="cb"] { color: var(--dir-cb); }
        &[data-dir="sb"] { color: var(--dir-sb); }
    }
    .prov-tip__pkt {
        color: var(--acc);
        word-break: break-word;
    }
    .prov-tip__pkt--none { color: var(--ink-4); }
    .prov-tip__seq {
        margin-left: auto;
        padding-left: 8px;
        color: var(--ink-3);
        font-size: var(--t-xs);
        font-variant-numeric: tabular-nums;
    }
    .prov-tip__sub {
        display: flex;
        align-items: baseline;
        flex-wrap: wrap;
        gap: 2px 8px;
        margin-top: 5px;
        font-size: var(--t-xs);
        color: var(--ink-4);
    }
    .prov-tip__age { color: var(--ink-3); font-variant-numeric: tabular-nums; }
    .prov-tip__field {
        margin-left: auto;
        word-break: break-all;
    }
    .prov-tip__hint {
        margin-top: 6px;
        padding-top: 5px;
        border-top: 1px solid var(--line);
        font-size: var(--t-2xs);
        text-transform: uppercase;
        letter-spacing: .05em;
        color: var(--ink-4);
    }

    /* Caret — a rotated square tucked under the box, two borders matching the box edge so it
     * reads as a continuous tip. Pinned at the anchor centre via inline `left`. */
    .prov-tip__caret {
        position: absolute;
        width: 8px;
        height: 8px;
        margin-left: -4px;
        background: var(--bg-1);
        transform: rotate(45deg);
    }
    .prov-tip:not(.prov-tip--below) .prov-tip__caret {
        bottom: -5px;
        border-right: 1px solid var(--line-2);
        border-bottom: 1px solid var(--line-2);
    }
    .prov-tip--below .prov-tip__caret {
        top: -5px;
        border-left: 1px solid var(--line-2);
        border-top: 1px solid var(--line-2);
    }
        }
    }
</style>
