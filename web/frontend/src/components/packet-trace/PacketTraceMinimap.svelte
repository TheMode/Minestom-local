<script lang="ts">
    import type { PacketTape } from '../../lib/packetTape.ts';
    import { isClientBound } from '../../lib/packetTrace.ts';
    import type { Bookmark, Breakpoint } from './types.ts';
    type Lifecycle = { seq: number; label: string };

    interface Props {
        tape?: PacketTape | null;
        tapeVersion?: number;
        bookmarks?: Bookmark[];
        breakpoints?: Breakpoint[];
        lifecycle?: Lifecycle[];
        playhead?: number | null;
        viewStart?: number | null;
        viewEnd?: number | null;
        related?: number[];
        onSeek?: (seq: number) => void;
    }

    let {
        tape = null,
        tapeVersion = 0,
        bookmarks = [],
        breakpoints = [],
        lifecycle = [],
        playhead = null,
        viewStart = null,
        viewEnd = null,
        related = [],
        onSeek = () => {},
    }: Props = $props();

    const COLS = 180;
    const HALF = 20;
    const SAMPLE_LIMIT = 6000;

    let wrap = $state<HTMLDivElement | undefined>();
    let width = $state(800);

    $effect(() => {
        if (!wrap) return undefined;
        width = wrap.clientWidth || width;
        const ro = new ResizeObserver(es => { for (const e of es) width = e.contentRect.width; });
        ro.observe(wrap);
        return () => ro.disconnect();
    });

    /// Bins + min/max are computed together so they share a single dependency on tapeVersion.
    /// Reading `tape.minSeq` / `tape.maxSeq` outside this block would not re-trigger when the
    /// tape mutates in place — PacketTape is a plain class, not `$state`.
    const view = $derived.by(() => {
        tapeVersion;
        const a: { cb: number; sb: number }[] = Array.from({ length: COLS }, () => ({ cb: 0, sb: 0 }));
        if (!tape || tape.length === 0) return { a, max: 1, minSeq: 1, maxSeq: 1, span: 1 };
        const minSeq = tape.minSeq;
        const maxSeq = tape.maxSeq;
        const span = Math.max(1, maxSeq - minSeq);
        let max = 0;
        tape.forEachSampled(SAMPLE_LIMIT, p => {
            const i = Math.min(COLS - 1, Math.floor(((p.seq - minSeq) / span) * COLS));
            if (isClientBound(p.direction)) a[i].cb++; else a[i].sb++;
            const t = a[i].cb + a[i].sb;
            if (t > max) max = t;
        });
        return { a, max: Math.max(1, max), minSeq, maxSeq, span };
    });

    const seqToX = (seq: number) => ((seq - view.minSeq) / view.span) * width;
    const xToSeq = (x: number) => Math.round((x / Math.max(1, width)) * view.span + view.minSeq);

    function onMouseDown(e: MouseEvent) {
        if (!wrap) return;
        const rect = wrap.getBoundingClientRect();
        const handle = (ev: MouseEvent) => onSeek(xToSeq(ev.clientX - rect.left));
        handle(e);
        const up = () => {
            window.removeEventListener('mousemove', handle);
            window.removeEventListener('mouseup', up);
        };
        window.addEventListener('mousemove', handle);
        window.addEventListener('mouseup', up);
    }

    const colWidth = $derived(width / COLS);
</script>

<div
    class="pt-strip"
    bind:this={wrap}
    onmousedown={onMouseDown}
    role="slider"
    aria-label="Packet timeline"
    tabindex="0"
    aria-valuemin={view.minSeq}
    aria-valuemax={view.maxSeq}
    aria-valuenow={playhead ?? view.maxSeq}
>
    <div class="pt-strip__legend" aria-hidden="true">
        <span class="cb"><i></i><b>CB</b></span>
        <span class="sb"><i></i><b>SB</b></span>
    </div>
    <div class="pt-strip__axis"></div>

    {#each view.a as b, i (i)}
        {@const cbH = (b.cb / view.max) * HALF}
        {@const sbH = (b.sb / view.max) * HALF}
        <div class="pt-strip__col" style:left="{i * colWidth}px" style:width="{Math.max(1, colWidth - 0.5)}px">
            <i style:bottom="50%" style:height="{cbH}px"></i>
            <i class="sb" style:top="50%" style:height="{sbH}px"></i>
        </div>
    {/each}

    {#if viewStart != null && viewEnd != null}
        <div
            class="pt-strip__window"
            style:left="{seqToX(viewStart)}px"
            style:width="{Math.max(2, seqToX(viewEnd) - seqToX(viewStart))}px"
        ></div>
    {/if}

    {#each lifecycle as l, i (i)}
        <div class="pt-strip__marker life" style:left="{seqToX(l.seq)}px" title={l.label}>
            <span
                class="glyph" role="button" tabindex="0"
                onclick={e => { e.stopPropagation(); onSeek(l.seq); }}
                onkeydown={e => { if (e.key === 'Enter') { e.stopPropagation(); onSeek(l.seq); } }}
            >◆</span>
        </div>
    {/each}

    {#each bookmarks as b, i (i)}
        <div class="pt-strip__marker bm" style:left="{seqToX(b.seq)}px" title={b.label}>
            <span
                class="glyph" role="button" tabindex="0"
                onclick={e => { e.stopPropagation(); onSeek(b.seq); }}
                onkeydown={e => { if (e.key === 'Enter') { e.stopPropagation(); onSeek(b.seq); } }}
            >★</span>
        </div>
    {/each}

    {#each breakpoints as bp (bp.id)}
        {#each bp.matchedSeqs ?? [] as s, j (j)}
            <div class="pt-strip__marker brk" style:left="{seqToX(s)}px" title={bp.label}>
                <span class="glyph">⏻</span>
            </div>
        {/each}
    {/each}

    {#each related as s, i (i)}
        <div class="pt-strip__marker" style:left="{seqToX(s)}px" style:background="var(--ink-3)"></div>
    {/each}

    {#if playhead != null}
        <div class="pt-strip__playhead" style:left="{seqToX(playhead)}px"></div>
    {/if}

    <div class="pt-strip__seq">
        #{view.minSeq.toLocaleString()} — #{view.maxSeq.toLocaleString()}
    </div>
</div>
