<script lang="ts">
    import { tick } from 'svelte';
    import type { PacketRow } from '../../lib/packetAgg.ts';
    import { pktLabel } from '../../lib/packetAgg.ts';
    import { isClientBound, rowSummary } from '../../lib/packetTrace.ts';
    import { fmtBytesShort } from '../../lib/util.ts';
    import type { StreamEntry } from './types.ts';

    type DisplayRow = PacketRow & { summary?: string };

    function fmtDelta(d: number | null): string {
        if (d == null) return '—';
        if (d < 1) return '<1ms';
        if (d < 1000) return '+' + Math.round(d) + 'ms';
        return '+' + (d / 1000).toFixed(2) + 's';
    }

    interface Props {
        entries?: StreamEntry[];
        playhead?: number | null;
        multi?: Set<number>;
        related?: Set<number>;
        classColors?: Map<string, string>;
        scrollToken?: number;
        rowHeight?: number;
        onSelect?: (seq: number) => void;
        onShiftSelect?: (seq: number) => void;
        onContext?: (e: MouseEvent, p: PacketRow) => void;
        onExpandGroup?: (a: number, b: number) => void;
    }

    let {
        entries = [],
        playhead = null,
        multi = new Set<number>(),
        related = new Set<number>(),
        classColors = new Map<string, string>(),
        scrollToken = 0,
        rowHeight = 26,
        onSelect = () => {},
        onShiftSelect = () => {},
        onContext = () => {},
        onExpandGroup = () => {},
    }: Props = $props();

    let bodyRef = $state<HTMLDivElement | undefined>();
    let vh = $state(600);
    let scrollTop = $state(0);
    let scrollGuard = false;
    let anchored = { token: -1, idx: -1 };

    $effect(() => {
        if (!bodyRef) return undefined;
        const ro = new ResizeObserver(es => { for (const e of es) vh = e.contentRect.height; });
        ro.observe(bodyRef);
        return () => ro.disconnect();
    });

    const total = $derived(entries.length);
    const overscan = 8;
    const startIdx = $derived(Math.max(0, Math.floor(scrollTop / rowHeight) - overscan));
    const endIdx = $derived(Math.min(total, Math.ceil((scrollTop + vh) / rowHeight) + overscan));
    const visible = $derived(entries.slice(startIdx, endIdx));
    const padTop = $derived(startIdx * rowHeight);
    const padBot = $derived((total - endIdx) * rowHeight);

    const rowEntryIndex = $derived.by(() => {
        const index = new Map<number, number>();
        for (let i = 0; i < entries.length; i++) {
            const e = entries[i];
            if (e.kind === 'row') index.set(e.p.seq, i);
        }
        return index;
    });

    const rowIndex = (seq: number) => rowEntryIndex.get(seq) ?? -1;
    const summaryOf = (p: PacketRow) => (p as DisplayRow).summary ?? rowSummary(p);

    $effect(() => {
        const token = scrollToken;
        const seq = playhead;
        entries.length;
        rowHeight;
        vh;
        if (seq == null || !bodyRef) return;

        const idx = rowIndex(seq);
        if (idx < 0) return;
        if (token === anchored.token && idx === anchored.idx) return;

        anchored = { token, idx };
        const top = Math.max(0, idx * rowHeight - vh / 2 + rowHeight / 2);
        let alive = true;
        scrollGuard = true;
        scrollTop = top;
        if (bodyRef) bodyRef.scrollTop = top;
        tick().then(() => {
            if (!alive || !bodyRef) return;
            bodyRef.scrollTop = top;
            scrollGuard = false;
        });
        return () => { alive = false; scrollGuard = false; };
    });

    function onRowClick(ev: MouseEvent, p: PacketRow) {
        if (ev.shiftKey) onShiftSelect(p.seq);
        else onSelect(p.seq);
    }

</script>

<div class="pt-stream__head">
    <span></span>
    <span>#seq</span>
    <span>Δt</span>
    <span>dir</span>
    <span>class · summary</span>
    <span>subject</span>
    <span>size</span>
</div>

<div
    class="pt-stream__body scroll-thin"
    bind:this={bodyRef}
    onscroll={e => { if (!scrollGuard) scrollTop = e.currentTarget.scrollTop; }}
>
    <div style:height="{padTop}px"></div>

    {#each visible as e, i (e.kind === 'row' ? `r-${e.p.seq}` : e.kind === 'group' ? `g-${e.seqStart}` : `l-${e.seq}-${i}`)}
        {#if e.kind === 'lifecycle'}
            <div class="pt-lifecycle">
                <span class="glyph">◆</span>
                <span>{e.label}</span>
                <span class="seq">#{e.seq.toLocaleString()}</span>
            </div>
        {:else if e.kind === 'group'}
            <button
                type="button"
                class="pt-group"
                onclick={() => onExpandGroup(e.seqStart, e.seqEnd)}
            >
                <span class="bm"></span>
                <span class="seq">#{e.seqStart.toLocaleString()}</span>
                <span class="span">… expand</span>
                <span></span>
                <span style:color="var(--ink-3)">{pktLabel(e.first.className)}</span>
                <span class="count">×{e.count}</span>
            </button>
        {:else}
            {@const p = e.p}
            {@const isCb = isClientBound(p.direction)}
            {@const isPlay = playhead === p.seq}
            {@const isMulti = multi.has(p.seq)}
            {@const isRel = related.has(p.seq)}
            <div
                class="pt-row data-row data-row--interactive"
                class:is-cb={isCb}
                class:is-sb={!isCb}
                class:data-row--selected={isPlay}
                class:is-selected={isPlay}
                class:is-multi={isMulti && !isPlay}
                class:is-related={isRel && !isPlay}
                role="button"
                tabindex="0"
                onclick={ev => onRowClick(ev, p)}
                oncontextmenu={ev => { ev.preventDefault(); onContext(ev, p); }}
                onkeydown={ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); onSelect(p.seq); } }}
            >
                <span class="bm">
                    {#if e.bookmark}<span class="bm-glyph" title={e.bookmark.label}>★</span>{/if}
                </span>
                <span class="seq">#{p.seq}</span>
                <span class="delta">{fmtDelta(e.delta)}</span>
                <span class="dir">{isCb ? '↓' : '↑'}</span>
                <span class="class">
                    <i class="swatch" style:--class-c={classColors.get(p.className) ?? 'var(--ink-3)'}></i>
                    <span class="name">{pktLabel(p.className)}</span>
                    <span class="summary">{summaryOf(p)}</span>
                </span>
                <span class="subj">
                    <i class="pip" style:background={`var(--sub-${p.subjectGroup})`}></i>
                    <span class="lbl">{p.subjectLabel || p.subjectGroup}</span>
                </span>
                <span class="size">{fmtBytesShort(p.sizeBytes)}</span>
            </div>
        {/if}
    {/each}

    <div style:height="{padBot}px"></div>

    {#if entries.length === 0}
        <div class="empty empty--trace empty--spacious">No packets match the current filters.</div>
    {/if}
</div>
