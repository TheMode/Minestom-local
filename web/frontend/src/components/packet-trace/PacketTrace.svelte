<script lang="ts">
    import { api, bus } from '../../lib/api.ts';
    import { replaceUrl } from '../../lib/nav.ts';
    import { route } from '../../state/route.svelte.ts';
    import { normalizeRow, type PacketRow, pktLabel, isClientBound } from '../../lib/packetAgg.ts';
    import { subscribeTopic } from '../../state/bus.svelte.ts';
    import { playerLifecycle, playerPackets } from '../../lib/topics.ts';
    import { toast } from '../../state/toasts.svelte.ts';
    import { PacketTape } from '../../lib/packetTape.ts';
    import { fmtBytesShort } from '../../lib/util.ts';
    import {
        rowSummary,
        LIFECYCLE_GLYPH,
        getCachedPacketDetail,
        setCachedPacketDetail,
        clearPacketDetailCache,
        ACCENTS,
        type AccentKey,
        prepareParsedRow,
        buildFacetRules,
        buildClassRules,
        matchFacets,
        buildStreamEntries,
        buildRelatedView,
        compileBreakpoints,
        computeBreakpointMatches,
    } from '../../lib/packetTrace.ts';
    import { parseQuery, classColor } from '../../lib/packetTraceDsl.ts';

    import PacketTraceTopBar from './PacketTraceTopBar.svelte';
    import PacketTraceMinimap from './PacketTraceMinimap.svelte';
    import PacketTraceFacets from './PacketTraceFacets.svelte';
    import PacketTraceStream from './PacketTraceStream.svelte';
    import PacketTraceInspector from './PacketTraceInspector.svelte';
    import PacketTraceHelp from './PacketTraceHelp.svelte';
    import PacketTraceTweaks from './PacketTraceTweaks.svelte';
    import type { FacetMode, Bookmark, Breakpoint, Saved, SideTab } from './types.ts';

    type LifecycleEvt = { seq: number; ts: number; packetSeq: number; kind: string };

    interface Props {
        player?: { uuid?: string; connectionId?: string } | null;
        paused?: boolean;
        streamLive?: boolean;
        onHistory?: (rows: PacketRow[]) => void;
        onPlayheadChange?: (row: PacketRow | null) => void;
        onResetFeed?: () => void;
    }

    let {
        player = null,
        paused: pagePaused = false,
        streamLive = true,
        onHistory = () => {},
        onPlayheadChange = () => {},
        onResetFeed = () => {},
    }: Props = $props();

    const uuid = $derived(player?.uuid ?? null);
    const connectionId = $derived(player?.connectionId ?? null);

    let tape = $state<PacketTape | null>(null);
    let tapeVersion = $state(0);
    let latestSeq = $state(0);

    let query = $state('');
    let filters = $state<Record<string, Record<string, FacetMode>>>({});
    let classFilter = $state<Record<string, FacetMode>>({});
    let classQuery = $state('');

    let selectedSeq = $state<number | null>(null);
    let multi = $state(new Set<number>());

    let paused = $state(false);
    let collapse = $state(true);
    let collapseExpanded = $state(false);

    let sideTab = $state<SideTab>('filters');
    let bookmarks = $state<Bookmark[]>([]);
    let breakpoints = $state<Breakpoint[]>([]);
    let saved = $state<Saved[]>([
        { name: 'Movement noise', q: 'class:Position' },
        { name: 'Inventory ops',  q: 'group:win' },
        { name: 'Keepalive pairs', q: 'class:KeepAlive' },
    ]);

    let jump = $state('');
    let helpOpen = $state(false);
    let tweaksOpen = $state(false);
    let accent = $state<AccentKey>('phosphor');
    let density = $state<'compact' | 'normal' | 'roomy'>('normal');
    let scrollToken = $state(0);
    let inspectorWidth = $state(300);
    let inspectorHeight = $state(360);

    let selectedRecord = $state<{ loading?: boolean; full?: Record<string, unknown>; error?: string } | null>(null);
    let prevRecord = $state<Record<string, unknown> | null>(null);

    let lifecycle = $state<LifecycleEvt[]>([]);

    let ratePps = $state(0);
    let rateCount = 0;
    let rateTs = Date.now();

    let searchRef = $state<HTMLInputElement | null>(null);
    let rootRef = $state<HTMLDivElement | undefined>();

    // ── tape lifecycle ──────────────────────────────────────────────
    function mountTape() {
        return new PacketTape(stats => {
            latestSeq = stats.maxSeq;
            rateCount += stats.added;
            const now = Date.now();
            if (now - rateTs >= 1000) {
                ratePps = rateCount;
                rateCount = 0;
                rateTs = now;
            }
            tapeVersion++;
        });
    }

    function resetTrace() {
        tape?.clear();
        selectedSeq = null;
        latestSeq = 0;
        bookmarks = [];
        collapseExpanded = false;
        ratePps = 0; rateCount = 0; rateTs = Date.now();
        selectedRecord = null;
        prevRecord = null;
        lifecycle = [];
        tapeVersion++;
        onResetFeed();
    }

    // ── derived: rows, classes, filtered, entries, related ──────────
    const parsed = $derived(parseQuery(query));

    const bookmarkMap = $derived.by(() => {
        const m = new Map<number, Bookmark>();
        for (const b of bookmarks) m.set(b.seq, b);
        return m;
    });

    const allRows = $derived.by((): readonly PacketRow[] => {
        tapeVersion;
        return tape?.snapshot() ?? [];
    });

    const prepareRow = $derived((p: PacketRow) => prepareParsedRow(p, bookmarkMap));

    const classCounts = $derived.by(() => {
        const m = new Map<string, number>();
        for (const p of allRows) m.set(p.className, (m.get(p.className) || 0) + 1);
        return m;
    });

    const classColors = $derived.by(() => {
        const m = new Map<string, string>();
        for (const k of classCounts.keys()) m.set(k, classColor(k));
        return m;
    });

    const facetRules = $derived(buildFacetRules(filters));
    const classRules = $derived(buildClassRules(classFilter));

    const filteredView = $derived.by(() => {
        const rows: PacketRow[] = [];
        let totalBytes = 0;
        let cbCount = 0;
        for (const p of allRows) {
            const pp = prepareRow(p);
            if (!parsed.match(pp)) continue;
            if (!matchFacets(p, facetRules, classRules)) continue;
            rows.push(p);
            totalBytes += p.sizeBytes;
            if (isClientBound(p.direction)) cbCount++;
        }
        return {
            rows,
            totalBytes,
            cbCount,
            range: rows.length ? [rows[0]!.seq, rows[rows.length - 1]!.seq] as [number, number] : [null, null] as [null, null],
        };
    });

    const filtered = $derived(filteredView.rows);
    const totalBytes = $derived(filteredView.totalBytes);
    const cbCount = $derived(filteredView.cbCount);
    const viewRange = $derived(filteredView.range);

    const compiledBreakpoints = $derived(compileBreakpoints(breakpoints));
    const breakpointMatches = $derived(computeBreakpointMatches(compiledBreakpoints, allRows, bookmarkMap));

    const lifecycleMarkers = $derived(
        lifecycle
            .filter(l => l.packetSeq > 0)
            .map(l => ({ seq: l.packetSeq, label: (LIFECYCLE_GLYPH[l.kind] ?? '·') + ' ' + l.kind })),
    );

    const entries = $derived(
        buildStreamEntries(filtered, lifecycleMarkers, collapse && !collapseExpanded, bookmarkMap),
    );

    function rowAt(s: number) {
        tapeVersion;
        return tape?.rowAtSeq(s) ?? null;
    }

    const selected = $derived(selectedSeq != null ? rowAt(selectedSeq) : null);

    const prevSameClass = $derived(
        selected && tape ? tape.findPrevSameClass(selected.seq, selected.className) : null,
    );

    const relatedView = $derived(buildRelatedView(selected, allRows, tape));

    const relatedSet = $derived(new Set(relatedView.map(r => r.row.seq)));

    const isBookmarked = $derived(selected ? bookmarkMap.has(selected.seq) : false);
    const live = $derived(selectedSeq == null && !paused);

    // ── action helpers ──────────────────────────────────────────────
    function syncUrlSeq(seq: number | null) {
        if (!uuid) return;
        const { segs } = route.current;
        if (segs[0] !== 'p' || segs[1] !== uuid || segs[2] !== 'packets') return;
        const base = `/p/${uuid}/packets`;
        replaceUrl(seq != null ? `${base}?seq=${seq}` : base);
    }

    function selectSeq(seq: number, opts: { keepMulti?: boolean; expand?: boolean; syncUrl?: boolean } = {}) {
        const { keepMulti = false, expand = false, syncUrl = true } = opts;
        if (expand) collapseExpanded = true;
        selectedSeq = seq;
        if (!keepMulti) multi = new Set();
        scrollToken++;
        if (syncUrl) syncUrlSeq(seq);
        onPlayheadChange(rowAt(seq));
    }

    function shiftSelect(seq: number) {
        const n = new Set(multi);
        if (n.has(seq)) n.delete(seq); else n.add(seq);
        if (n.size > 2) multi = new Set([...n].slice(-2));
        else multi = n;
        if (selectedSeq == null) selectedSeq = seq;
    }

    function setFilter(field: string | null, value: string | null, mode: FacetMode) {
        if (field == null) { filters = {}; return; }
        if (value == null) {
            const n = { ...filters };
            delete n[field];
            filters = n;
            return;
        }
        const f = { ...(filters[field] || {}) };
        if (mode == null) delete f[value]; else f[value] = mode;
        filters = { ...filters, [field]: f };
    }

    function step(delta: number) {
        const rows: PacketRow[] = [];
        for (const e of entries) if (e.kind === 'row') rows.push(e.p);
        if (rows.length === 0) return;
        const idx = rows.findIndex(p => p.seq === selectedSeq);
        const next = rows[Math.max(0, Math.min(rows.length - 1, (idx < 0 ? 0 : idx) + delta))];
        if (next) selectSeq(next.seq);
    }

    function onJumpSubmit() {
        const n = Number(jump);
        if (!Number.isFinite(n) || n <= 0) return;
        selectSeq(n);
    }

    function goLive() {
        paused = false;
        selectedSeq = null;
        selectedRecord = null;
        multi = new Set();
        syncUrlSeq(null);
        onPlayheadChange(null);
    }

    function toggleBookmark() {
        if (!selected) return;
        const i = bookmarks.findIndex(b => b.seq === selected.seq);
        if (i >= 0) bookmarks = bookmarks.filter((_, j) => j !== i);
        else bookmarks = [...bookmarks, { seq: selected.seq, label: pktLabel(selected.className) + ' · ' + rowSummary(selected).slice(0, 30) }];
    }

    function closeInspector() {
        selectedSeq = null;
        selectedRecord = null;
        prevRecord = null;
        multi = new Set();
        syncUrlSeq(null);
        onPlayheadChange(null);
    }

    function onContext(_ev: MouseEvent, row: PacketRow) {
        const cur = classFilter[row.className];
        const next: FacetMode = cur === 'include' ? 'exclude' : cur === 'exclude' ? null : 'include';
        const out = { ...classFilter };
        if (next == null) delete out[row.className]; else out[row.className] = next;
        classFilter = out;
    }

    function copyClass() {
        if (!selected) return;
        const cls = selected.className;
        navigator.clipboard?.writeText(cls)
            .then(() => toast(cls + ' copied', 'ok'))
            .catch(() => toast('Copy failed', 'error'));
    }

    function breakOnClass() {
        if (!selected) return;
        const label = 'pause on ' + pktLabel(selected.className);
        breakpoints = [...breakpoints, {
            id: 'b' + Date.now(),
            match: 'class:' + pktLabel(selected.className),
            label,
            enabled: true,
        }];
        sideTab = 'breaks';
    }


    function clampInspectorWidth(next: number): number {
        const rootWidth = rootRef?.clientWidth ?? 1100;
        const max = Math.max(260, Math.min(560, rootWidth - 520));
        return Math.max(240, Math.min(max, Math.round(next)));
    }

    function clampInspectorHeight(next: number): number {
        const rootHeight = rootRef?.clientHeight ?? 800;
        const max = Math.max(240, rootHeight - 320);
        return Math.max(180, Math.min(max, Math.round(next)));
    }

    function startInspectorResize(axis: 'x' | 'y', e: PointerEvent): void {
        if (!rootRef) return;
        e.preventDefault();
        const resize = (ev: PointerEvent) => {
            const rect = rootRef!.getBoundingClientRect();
            if (axis === 'x') inspectorWidth = clampInspectorWidth(rect.right - ev.clientX);
            else inspectorHeight = clampInspectorHeight(rect.bottom - ev.clientY);
        };
        const stop = () => {
            window.removeEventListener('pointermove', resize);
            window.removeEventListener('pointerup', stop);
            window.removeEventListener('pointercancel', stop);
        };
        resize(e);
        window.addEventListener('pointermove', resize);
        window.addEventListener('pointerup', stop, { once: true });
        window.addEventListener('pointercancel', stop, { once: true });
    }

    // ── live data wiring ────────────────────────────────────────────
    $effect(() => {
        uuid;
        tape = mountTape();
        selectedSeq = null;
        latestSeq = 0;
        lifecycle = [];
        selectedRecord = null;
        prevRecord = null;
        tapeVersion = 0;
        if (uuid) clearPacketDetailCache(uuid);
    });

    $effect(() => {
        const id = uuid;
        const conn = connectionId;
        const t = tape;
        if (!id || !conn || !t) return undefined;
        let alive = true;
        (async () => {
            const rows: PacketRow[] = [];
            let since = 0;
            while (alive) {
                const page = await api<Record<string, unknown>[]>(`/connections/${conn}/packets?since=${since}&limit=5000`)
                    .catch(() => [] as Record<string, unknown>[]);
                if (!alive || !page.length) break;
                for (const rec of page) rows.push(normalizeRow({ ...rec, uuid: id, connectionId: conn }));
                since = Number(page[page.length - 1]?.seq) || since;
                if (page.length < 5000) break;
            }
            if (alive && rows.length) {
                t.loadHistory(rows);
                onHistory(rows);
            }
        })();
        return () => { alive = false; };
    });

    $effect(() => {
        const id = uuid;
        const t = tape;
        if (!id || !t) return undefined;
        return bus.subscribe(playerPackets(id), m => {
            if (!streamLive || pagePaused || paused) return;
            t.push(normalizeRow(m));
        });
    });

    $effect(() => {
        const id = uuid;
        if (!id) return undefined;
        let alive = true;
        api(`/players/${id}/lifecycle`)
            .then(r => { if (alive) lifecycle = (r as LifecycleEvt[] | null) || []; })
            .catch(() => { if (alive) lifecycle = []; });
        return () => { alive = false; };
    });

    $effect(() => {
        const id = uuid;
        if (!id) return undefined;
        return subscribeTopic(() => playerLifecycle(id), (msg: { seq?: number }) => {
            if (!msg?.seq) return;
            lifecycle = lifecycle.some(e => e.seq === msg.seq) ? lifecycle : [...lifecycle, msg as LifecycleEvt];
        });
    });

    function absorbPacketDetail(data: Record<string, unknown>) {
        selectedRecord = { full: data };
        const row = normalizeRow(data);
        if (row.seq === selectedSeq) onPlayheadChange(row);
    }

    /// Fetch one packet detail record, serving the per-uuid cache when warm and populating
    /// it on miss. Cache hits return synchronously (cached value), misses hit the API.
    async function loadPacketDetail(id: string, conn: string, seq: number, signal: AbortSignal): Promise<Record<string, unknown>> {
        const cached = getCachedPacketDetail(id, seq);
        if (cached) return cached;
        const r = await api<Record<string, unknown>>(`/connections/${conn}/packets/${seq}`, { signal });
        setCachedPacketDetail(id, seq, r);
        return r;
    }

    // Fetch packet detail when a row is selected.
    $effect(() => {
        const seq = selectedSeq;
        const id = uuid;
        const conn = connectionId;
        if (seq == null || !id || !conn) return undefined;
        const cached = getCachedPacketDetail(id, seq);
        if (cached) { absorbPacketDetail(cached); return undefined; }

        let alive = true;
        selectedRecord = { loading: true };
        const ac = new AbortController();
        const timer = setTimeout(() => {
            loadPacketDetail(id, conn, seq, ac.signal)
                .then(r => { if (alive) absorbPacketDetail(r); })
                .catch(e => {
                    if (!alive || ac.signal.aborted) return;
                    const err = e as Error & { status?: number };
                    selectedRecord = { error: err.status === 404 ? `Packet #${seq} not in memory or archive` : err.message || String(e) };
                });
        }, 80);
        return () => { alive = false; ac.abort(); clearTimeout(timer); };
    });

    $effect(() => {
        prevRecord = null;
        const prev = prevSameClass;
        const id = uuid;
        const conn = connectionId;
        if (!prev || !id || !conn) return undefined;
        let alive = true;
        const ac = new AbortController();
        loadPacketDetail(id, conn, prev.seq, ac.signal)
            .then(r => { if (alive) prevRecord = (r.record ?? null) as Record<string, unknown> | null; })
            .catch(() => { /* prev is optional */ });
        return () => { alive = false; ac.abort(); };
    });

    $effect(() => {
        const n = Number(route.current.query?.seq);
        if (!Number.isFinite(n) || n <= 0 || n === selectedSeq) return;
        selectSeq(n, { expand: true, syncUrl: false });
    });

    // Breakpoint: pause when a newly arrived row matches.
    $effect(() => {
        if (paused || !latestSeq) return;
        const row = rowAt(latestSeq);
        if (!row) return;
        const parsedRow = prepareRow(row);
        for (const bp of compiledBreakpoints) {
            if (!bp.matcher?.(parsedRow)) continue;
            paused = true;
            toast('Breakpoint hit at #' + latestSeq, 'warn');
            break;
        }
    });

    // ── keyboard ─────────────────────────────────────────────────────
    function onKey(e: KeyboardEvent) {
        const t = e.target as Element | null;
        if (t instanceof HTMLInputElement || t instanceof HTMLTextAreaElement) return;
        switch (e.key) {
            case ' ': e.preventDefault(); paused = !paused; break;
            case 'ArrowDown': case 'j': e.preventDefault(); step(1); break;
            case 'ArrowUp':   case 'k': e.preventDefault(); step(-1); break;
            case 'ArrowRight': e.preventDefault(); step(e.shiftKey ? 10 : 1); break;
            case 'ArrowLeft':  e.preventDefault(); step(e.shiftKey ? -10 : -1); break;
            case 'f': case 'F': goLive(); break;
            case 'b': case 'B': toggleBookmark(); break;
            case 'c': case 'C': collapseExpanded = !collapseExpanded; break;
            case 'Escape': closeInspector(); helpOpen = false; break;
            case '?': helpOpen = !helpOpen; break;
            case '/': e.preventDefault(); searchRef?.focus(); break;
        }
    }

    // ── accent palette ───────────────────────────────────────────────
    $effect(() => {
        const opt = ACCENTS[accent];
        if (!rootRef) return;
        const s = rootRef.style;
        s.setProperty('--acc', opt.acc);
        s.setProperty('--acc-deep', opt.deep);
        s.setProperty('--acc-soft', `color-mix(in oklab, ${opt.acc} 14%, transparent)`);
        s.setProperty('--acc-line', `color-mix(in oklab, ${opt.acc} 35%, transparent)`);
        s.setProperty('--acc-glow', `color-mix(in oklab, ${opt.acc} 55%, transparent)`);
    });
</script>

<svelte:window onkeydown={onKey} />

<div class="pt" data-density={density} bind:this={rootRef}>
    <PacketTraceTopBar
        {query}
        {parsed}
        {live}
        {paused}
        rate={ratePps}
        totalPackets={allRows.length}
        {jump}
        breakOn={breakpoints.some(b => b.enabled)}
        bind:searchRef
        onQuery={v => { query = v; }}
        onPaused={v => { paused = v; }}
        onStep={step}
        onLive={goLive}
        onJump={onJumpSubmit}
        onJumpChange={v => { jump = v; }}
        onHelp={() => { helpOpen = true; }}
        onTweaks={() => { tweaksOpen = !tweaksOpen; }}
    />

    <PacketTraceMinimap
        {tape}
        {tapeVersion}
        {bookmarks}
        breakpoints={breakpointMatches}
        lifecycle={lifecycleMarkers}
        playhead={selectedSeq}
        viewStart={viewRange[0]}
        viewEnd={viewRange[1]}
        related={[...relatedSet]}
        onSeek={(seq) => { selectSeq(tape?.nearestSeq(seq) ?? seq); paused = true; }}
    />

    <div class="pt-main" style:--pt-inspector-w={inspectorWidth + 'px'} style:--pt-inspector-h={inspectorHeight + 'px'}>
        <PacketTraceFacets
            tab={sideTab}
            rows={allRows}
            {filters}
            {classCounts}
            {classFilter}
            {classQuery}
            {bookmarks}
            breakpoints={breakpointMatches}
            {saved}
            currentSeq={selected?.seq ?? null}
            currentQuery={query}
            onSetTab={t => { sideTab = t; }}
            onSetFilter={setFilter}
            onSetClassFilter={v => { classFilter = v; }}
            onSetClassQuery={v => { classQuery = v; }}
            onJumpBookmark={selectSeq}
            onAddBookmark={b => { bookmarks = [...bookmarks, b]; }}
            onRemoveBookmark={i => { bookmarks = bookmarks.filter((_, j) => j !== i); }}
            onToggleBreakpoint={i => { breakpoints = breakpoints.map((b, j) => j === i ? { ...b, enabled: !b.enabled } : b); }}
            onAddBreakpoint={b => { breakpoints = [...breakpoints, { ...b, id: 'b' + Date.now() }]; }}
            onRemoveBreakpoint={i => { breakpoints = breakpoints.filter((_, j) => j !== i); }}
            onLoadSaved={q => { query = q; }}
            onAddSaved={s => { saved = [...saved, s]; }}
            onRemoveSaved={i => { saved = saved.filter((_, j) => j !== i); }}
        />

        <section class="pt-stream">
            <PacketTraceStream
                {entries}
                playhead={selectedSeq}
                {multi}
                related={relatedSet}
                {classColors}
                {scrollToken}
                rowHeight={density === 'compact' ? 22 : density === 'roomy' ? 32 : 26}
                onSelect={selectSeq}
                onShiftSelect={shiftSelect}
                onContext={onContext}
                onExpandGroup={(a) => { collapseExpanded = true; selectSeq(a); }}
            />
        </section>

        <div
            class="pt-resize pt-resize--v"
            role="separator"
            aria-label="Resize packet inspector"
            aria-orientation="vertical"
            onpointerdown={e => startInspectorResize('x', e)}
        ></div>

        <div
            class="pt-resize pt-resize--h"
            role="separator"
            aria-label="Resize packet inspector height"
            aria-orientation="horizontal"
            onpointerdown={e => startInspectorResize('y', e)}
        ></div>

        <PacketTraceInspector
            row={selected}
            seq={selectedSeq ?? 0}
            record={selectedRecord}
            {prevSameClass}
            {prevRecord}
            related={relatedView}
            {multi}
            getRow={rowAt}
            {isBookmarked}
            onClose={closeInspector}
            onJumpSeq={selectSeq}
            onStep={step}
            onToggleBookmark={toggleBookmark}
            onCopyClass={copyClass}
            onBreakOnClass={breakOnClass}
        />
    </div>

    <footer class="pt-status">
        <span><span class="k">shown</span><span class="v acc">{filtered.length.toLocaleString()}</span> / {allRows.length.toLocaleString()}</span>
        <span class="sep">·</span>
        <span><span class="k">CB</span><span class="v">{cbCount.toLocaleString()}</span></span>
        <span><span class="k">SB</span><span class="v">{(filtered.length - cbCount).toLocaleString()}</span></span>
        <span class="sep">·</span>
        <span><span class="k">bw</span><span class="v">{fmtBytesShort(totalBytes)}</span></span>
        <span class="sep">·</span>
        <span>
            <span class="k">sel</span>
            <span class="v">{selected ? '#' + selected.seq : '—'}</span>
            {#if multi.size > 1}<span class="v acc">  (+{multi.size - 1} multi)</span>{/if}
        </span>
        <span class="sep">·</span>
        <span><span class="k">marks</span><span class="v warn">{bookmarks.length}</span></span>
        <span><span class="k">breaks</span><span class="v danger">{breakpoints.filter(b => b.enabled).length}</span></span>

        <span class="right">
            <span><kbd>?</kbd> help</span>
            <span><kbd>/</kbd> search</span>
            <span><kbd>space</kbd> {paused ? 'resume' : 'pause'}</span>
            <span><kbd>←</kbd><kbd>→</kbd> step</span>
            <span><kbd>B</kbd> bookmark</span>
        </span>
    </footer>

    {#if helpOpen}
        <PacketTraceHelp onClose={() => { helpOpen = false; }} />
    {/if}

    {#if tweaksOpen}
        <PacketTraceTweaks
            {accent}
            {density}
            {collapse}
            onAccent={a => { accent = a; }}
            onDensity={d => { density = d; }}
            onToggleCollapse={() => { collapse = !collapse; collapseExpanded = false; }}
            onReset={resetTrace}
            onClose={() => { tweaksOpen = false; }}
        />
    {/if}
</div>

<style>
    @layer components {
        :global {
    /* ---- Packet trace ---- */
    .pt {
        --row-h: 26px;
        --pt-control-h: 30px;
        --trace-panel: color-mix(in oklab, var(--bg-1) 88%, black);
        --trace-panel-2: color-mix(in oklab, var(--bg-2) 72%, black);
        --trace-line: color-mix(in oklab, var(--line) 72%, transparent);
        --trace-hover: color-mix(in oklab, var(--ink) 4%, transparent);
        display: grid;
        grid-template-rows: auto auto 1fr auto;
        min-height: 720px;
        height: 80vh;
        background: var(--bg-0);
        color: var(--ink-2);
        font-size: var(--t-md);
        line-height: 1;
        border: 1px solid var(--trace-line);
        overflow: hidden;
        position: relative;
        container: packet-trace / inline-size;
        box-shadow: inset 0 1px 0 color-mix(in oklab, white 4%, transparent);
    }
    .pt input { font: inherit; color: inherit; }
    .pt input:focus, .pt button:focus { outline: 1px solid var(--acc); outline-offset: 1px; }
    
    /* ───── top bar ───────────────────────────────────────────────────── */
    .pt-top {
        display: flex; align-items: center; gap: 8px;
        padding: 10px 12px; min-height: 52px;
        background: var(--trace-panel);
        border-bottom: 1px solid var(--trace-line);
        flex-wrap: wrap;
    }
    .pt-query-tokens { align-items: center; gap: 4px; }
    .pt-controls { display: flex; align-items: center; gap: 4px; margin-left: auto; }
    .pt-top .btn,
    .pt-top .pt-jump,
    .pt-top .gauge-inline,
    .pt-top .chip-filter--sm,
    .pt-top .search-inline--bar {
        height: var(--pt-control-h);
        min-height: var(--pt-control-h);
        box-sizing: border-box;
    }
    .pt-top .btn {
        display: inline-flex;
        align-items: center;
        justify-content: center;
        padding: 0 9px;
    }
    .pt-top .btn.icon {
        width: var(--pt-control-h);
        min-width: var(--pt-control-h);
        padding: 0;
    }
    .pt-top .search-inline--bar {
        flex: 1 1 340px;
        background: color-mix(in oklab, var(--sunk) 78%, black);
        border-color: var(--trace-line);
        box-shadow: inset 0 0 0 1px color-mix(in oklab, black 16%, transparent);
    }
    .pt-top .search-inline--bar input {
        font-size: var(--t-sm);
    }
    .pt-top .search-inline--bar input::placeholder {
        color: color-mix(in oklab, var(--ink-4) 78%, transparent);
        font-size: var(--t-xs);
    }
    .pt-top .gauge-inline {
        align-items: center;
        line-height: 1;
        background: color-mix(in oklab, var(--bg-0) 64%, transparent);
        border: 1px solid var(--trace-line);
        padding: 0 8px;
    }
    .pt-top .gauge-inline .num,
    .pt-top .gauge-inline .lbl { line-height: 1; }
    .pt-top .divider-v {
        height: var(--pt-control-h);
        background: var(--trace-line);
        margin: 0 3px;
    }
    
    .pt-jump {
        display: inline-flex; align-items: center;
        background: color-mix(in oklab, var(--sunk) 78%, black); border: 1px solid var(--trace-line);
    }
    .pt-jump:focus-within { border-color: var(--acc-line); }
    .pt-jump .label { padding: 0 8px; color: var(--ink-4); font-size: var(--t-xs); text-transform: uppercase; letter-spacing: 0.1em; }
    .pt-jump input { width: 64px; height: 100%; background: transparent; border: 0; color: var(--ink); font-size: var(--t-sm); padding: 0 6px; }
    
    /* ───── minimap strip ────────────────────────────────────────────── */
    .pt-strip {
        position: relative; height: 46px; overflow: hidden;
        background: color-mix(in oklab, var(--bg-0) 82%, black);
        border-bottom: 1px solid var(--trace-line);
        cursor: crosshair; user-select: none;
    }
    .pt-strip__axis { position: absolute; top: 50%; left: 0; right: 0; height: 1px; background: var(--trace-line); }
    .pt-strip__col { position: absolute; top: 0; bottom: 0; width: 2px; pointer-events: none; }
    .pt-strip__col i { position: absolute; left: 0; right: 0; background: var(--dir-cb); opacity: 0.5; }
    .pt-strip__col i.sb { background: var(--dir-sb); opacity: 0.45; }
    .pt-strip__marker {
        position: absolute; width: 2px; top: 2px; bottom: 2px;
        pointer-events: none; z-index: 2;
    }
    .pt-strip__marker.bm   { background: var(--warn); }
    .pt-strip__marker.brk  { background: var(--danger); }
    .pt-strip__marker.life { background: var(--acc); }
    .pt-strip__marker .glyph {
        position: absolute; top: -1px; left: 50%; transform: translateX(-50%);
        width: 12px; height: 12px; line-height: 10px;
        background: var(--bg-0); border: 1px solid currentColor;
        color: inherit; font-size: 9px; text-align: center;
        pointer-events: auto; cursor: pointer;
    }
    .pt-strip__marker.bm   .glyph { color: var(--warn); }
    .pt-strip__marker.brk  .glyph { color: var(--danger); }
    .pt-strip__marker.life .glyph { color: var(--acc); }
    
    .pt-strip__playhead {
        position: absolute; top: 0; bottom: 0; width: 1px;
        background: var(--ink);
        box-shadow: 0 0 0 1px color-mix(in oklab, var(--acc) 30%, transparent);
        pointer-events: none; z-index: 3;
    }
    .pt-strip__playhead::before, .pt-strip__playhead::after {
        content: ''; position: absolute; left: -3px; width: 7px; height: 7px;
        background: var(--ink);
    }
    .pt-strip__playhead::before { top: 0;    clip-path: polygon(0 0, 100% 0, 50% 100%); }
    .pt-strip__playhead::after  { bottom: 0; clip-path: polygon(50% 0, 0 100%, 100% 100%); }
    
    .pt-strip__window {
        position: absolute; top: 0; bottom: 0;
        background: color-mix(in oklab, var(--ink) 5%, transparent);
        border-left: 1px solid color-mix(in oklab, var(--ink-4) 70%, transparent);
        border-right: 1px solid color-mix(in oklab, var(--ink-4) 70%, transparent);
        pointer-events: none;
    }
    
    .pt-strip__legend {
        position: absolute;
        left: 8px;
        top: 50%;
        display: grid;
        gap: 2px;
        transform: translateY(-50%);
        pointer-events: none;
        z-index: 4;
    }
    .pt-strip__legend span {
        display: inline-grid;
        grid-template-columns: 3px 1fr;
        align-items: center;
        gap: 4px;
        width: 30px;
        padding: 1px 3px;
        background: color-mix(in oklab, var(--bg-0) 70%, transparent);
        color: var(--ink-4);
        font-size: 8px;
        letter-spacing: 0.08em;
        text-transform: uppercase;
        border-left: 1px solid color-mix(in oklab, currentColor 45%, transparent);
    }
    .pt-strip__legend i { width: 3px; height: 10px; background: currentColor; }
    .pt-strip__legend b { font-weight: 700; }
    .pt-strip__legend .cb { color: var(--dir-cb); }
    .pt-strip__legend .sb { color: var(--dir-sb); }
    .pt-strip__seq {
        position: absolute; right: 8px; bottom: 4px;
        color: var(--ink-4); font-size: var(--t-xs); letter-spacing: 0.05em;
        pointer-events: none; z-index: 4;
    }
    
    /* ───── main 3-column ────────────────────────────────────────────── */
    .pt-main {
        display: grid;
        grid-template-columns: minmax(160px, 190px) minmax(0, 1fr) 8px minmax(240px, var(--pt-inspector-w, 300px));
        min-height: 0;
        overflow: hidden;
    }

    .pt-resize {
        background: var(--trace-panel);
        position: relative;
        touch-action: none;
    }
    .pt-resize::before {
        content: '';
        position: absolute;
        background: color-mix(in oklab, var(--ink-4) 35%, transparent);
    }
    .pt-resize:hover::before,
    .pt-resize:active::before {
        background: var(--acc);
    }
    .pt-resize--v {
        width: 8px; min-width: 8px;
        cursor: col-resize;
        border-left: 1px solid var(--trace-line);
        border-right: 1px solid var(--trace-line);
    }
    .pt-resize--v::before { inset: 0 3px; }
    .pt-resize--h {
        height: 8px; min-height: 8px;
        cursor: row-resize;
        border-top: 1px solid var(--trace-line);
        border-bottom: 1px solid var(--trace-line);
        grid-column: 1 / -1;
        display: none;
    }
    .pt-resize--h::before { inset: 3px 0; }

    /* ── facets / left rail ──────────────────────────────────────────── */
    .pt-facets {
        display: flex; flex-direction: column;
        min-height: 0; overflow: hidden;
        background: var(--trace-panel); border-right: 1px solid var(--trace-line);
    }
    .pt-facets__body { flex: 1; padding-bottom: 16px; }
    
    .pt-facets__tabs {
        padding: 8px;
        background: color-mix(in oklab, var(--bg-0) 36%, transparent);
        border-bottom: 1px solid var(--trace-line);
    }
    .pt-facet-group { padding: 9px 0; border-bottom: 1px solid var(--trace-line); }
    .pt-facet-group__head {
        display: flex; align-items: center; justify-content: space-between;
        padding: 4px 12px;
        color: var(--ink-3); font-size: var(--t-xs);
        letter-spacing: 0.1em; text-transform: uppercase;
    }
    .pt-facet-group__head .count { color: var(--ink-4); }
    .pt-facet-group__head .reset {
        color: var(--ink-4); font-size: var(--t-xs);
        text-decoration: underline; text-underline-offset: 2px;
    }
    .pt-facet-group__head .reset:hover { color: var(--ink); }
    
    .pt-facet-row {
        display: grid; grid-template-columns: 14px 14px 1fr auto;
        align-items: center; gap: 6px; padding: 3px 12px;
        height: 22px; width: 100%; text-align: left;
        color: var(--ink-2); font-size: var(--t-sm);
    }
    .pt-facet-row:hover { background: var(--trace-hover); }
    .pt-facet-row.is-include { color: var(--acc); }
    .pt-facet-row.is-exclude { color: var(--danger); text-decoration: line-through; }
    .pt-facet-row .dot { width: 8px; height: 8px; }
    .pt-facet-row .sym { color: var(--ink-4); font-size: var(--t-xs); text-align: center; }
    .pt-facet-row.is-include .sym { color: var(--acc); }
    .pt-facet-row.is-exclude .sym { color: var(--danger); }
    .pt-facet-row .lbl { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .pt-facet-row .num { color: var(--ink-4); font-size: var(--t-xs); font-variant-numeric: tabular-nums; }
    
    .pt-list { padding: 6px 0; }
    .pt-list__item {
        display: grid; grid-template-columns: 14px 1fr auto;
        align-items: center; gap: 8px; padding: 6px 12px;
        width: 100%; text-align: left;
        color: var(--ink-2); font-size: var(--t-sm);
        border-bottom: 1px solid var(--trace-line);
    }
    .pt-list__item:hover { background: var(--trace-hover); color: var(--ink); }
    .pt-list__item .glyph { color: var(--warn); font-size: var(--t-sm); }
    .pt-list__item.brk .glyph { color: var(--danger); }
    .pt-list__item .note { color: var(--ink-3); font-size: var(--t-xs); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .pt-list__item .del { color: var(--ink-4); font-size: var(--t-xs); padding: 0 4px; }
    .pt-list__item .del:hover { color: var(--danger); }
    
    .pt-add-form { display: flex; gap: 4px; margin: 6px 8px; }
    .pt-add-form input {
        flex: 1; min-width: 0; height: 22px; padding: 0 6px;
        background: color-mix(in oklab, var(--sunk) 76%, black); border: 1px solid var(--trace-line);
        color: var(--ink); font-size: var(--t-xs);
    }
    .pt-add-form button {
        padding: 0 8px; height: 22px;
        background: var(--trace-panel-2); border: 1px solid var(--trace-line);
        color: var(--acc); font-size: var(--t-xs);
    }
    
    /* ── stream / center ─────────────────────────────────────────────── */
    .pt-stream {
        display: flex; flex-direction: column;
        min-width: 0; min-height: 0;
        background: var(--bg-0);
    }
    .pt-stream__head, .pt-row, .pt-group {
        display: grid;
        grid-template-columns: 16px 50px 46px 16px minmax(180px, 1fr) minmax(56px, 72px) 44px;
        align-items: center; gap: 8px; padding: 0 12px;
    }
    .pt-stream__head {
        height: 26px; flex-shrink: 0;
        background: var(--trace-panel); border-bottom: 1px solid var(--trace-line);
        color: var(--ink-4); font-size: var(--t-xs);
        letter-spacing: 0.08em; text-transform: uppercase;
    }
    .pt-stream__body { flex: 1; position: relative; overflow-x: hidden; }
    
    .pt-row {
        height: var(--row-h); position: relative; cursor: pointer;
        color: var(--ink-2); font-size: var(--t-sm);
        border-bottom: 1px solid color-mix(in oklab, var(--line) 50%, transparent);
        transition: background-color 120ms ease, color 120ms ease;
    }
    .pt-row::before {
        content: '';
        position: absolute;
        inset: 0 auto 0 0;
        width: 2px;
        background: var(--dir-cb);
        opacity: 0.45;
    }
    .pt-row.is-sb::before { background: var(--dir-sb); }
    .pt-row:hover { background: var(--trace-hover); color: var(--ink); }
    .pt-row:focus, .pt-row:focus-visible { outline: none; }
    .pt-row.is-selected,
    .pt-row.data-row--selected {
        background: color-mix(in oklab, var(--acc) 10%, transparent);
        box-shadow: inset 3px 0 0 var(--acc), inset 0 0 0 1px var(--acc-line);
        color: var(--ink);
    }
    .pt-row.is-selected::before,
    .pt-row.data-row--selected::before { opacity: 1; }
    .pt-row.is-multi    { background: color-mix(in oklab, var(--acc) 6%, transparent); box-shadow: inset 3px 0 0 var(--acc-deep); }
    .pt-row.is-related  { box-shadow: inset 3px 0 0 var(--warn); }
    .pt-row.is-cb .dir  { color: var(--dir-cb); }
    .pt-row.is-sb .dir  { color: var(--dir-sb); }
    
    .pt-row .bm { display: flex; align-items: center; justify-content: center; }
    .pt-row .bm-glyph { color: var(--warn); font-size: var(--t-sm); }
    
    .pt-row .seq, .pt-row .delta, .pt-row .size { font-variant-numeric: tabular-nums; font-size: var(--t-xs); }
    .pt-row .seq   { color: var(--ink-3); }
    .pt-row .delta { color: var(--ink-4); }
    .pt-row .size  { color: var(--ink-4); text-align: right; }
    .pt-row .dir   { font-size: var(--t-md); font-weight: 700; text-align: center; }
    
    .pt-row .class { display: flex; align-items: center; gap: 6px; min-width: 0; overflow: hidden; color: var(--ink); }
    .pt-row .class .swatch { width: 3px; height: 14px; flex-shrink: 0; background: var(--class-c, var(--ink-3)); opacity: 0.75; }
    .pt-row .class .name {
        flex: 0 0 auto; max-width: min(100%, 260px);
        white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .pt-row .class .summary { flex: 1 1 auto; min-width: 0; }
    .pt-row .summary {
        color: var(--ink-3); font-size: var(--t-xs);
        white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .pt-row.is-selected .summary { color: var(--ink-2); }
    .pt-row .subj {
        display: inline-flex; align-items: center; gap: 4px;
        overflow: hidden; color: var(--ink-3); font-size: var(--t-xs);
    }
    .pt-row .subj .pip { width: 6px; height: 6px; flex-shrink: 0; }
    .pt-row .subj .lbl { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    
    .pt-group {
        height: var(--row-h); cursor: pointer; font-style: italic;
        color: var(--ink-3); font-size: var(--t-sm);
        background: var(--bg-1);
        border: 0;
        border-bottom: 1px solid color-mix(in oklab, var(--line) 50%, transparent);
        box-shadow: none;
        text-transform: none;
        text-align: left;
        width: 100%;
        box-sizing: border-box;
    }
    .pt-group:hover { background: var(--trace-hover); color: var(--ink-2); }
    .pt-group .span { color: var(--ink-4); font-size: var(--t-xs); }
    .pt-group .count { grid-column: 7; color: var(--acc); font-size: var(--t-xs); text-align: right; }
    
    .pt-lifecycle {
        display: flex; align-items: center; gap: 12px;
        padding: 4px 12px; height: 24px;
        background: var(--acc-soft);
        border-top: 1px dashed var(--acc-line); border-bottom: 1px dashed var(--acc-line);
        color: var(--acc); font-size: var(--t-xs);
        letter-spacing: 0.08em; text-transform: uppercase;
    }
    .pt-lifecycle .glyph { font-size: var(--t-md); }
    .pt-lifecycle .seq { color: var(--ink-4); margin-left: auto; }
    
    /* ── inspector / right rail ──────────────────────────────────────── */
    .pt-insp {
        display: flex; flex-direction: column;
        min-width: 0; min-height: 0; overflow: hidden;
        background: var(--trace-panel);
    }
    .pt-insp__head {
        padding: 12px 14px;
        border-bottom: 1px solid var(--trace-line);
        background: var(--trace-panel);
    }
    .pt-insp__head-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .pt-insp__head .class-name { color: var(--ink); font-size: var(--t-lg); letter-spacing: 0.01em; }
    .pt-insp__head .meta {
        display: flex; gap: 12px; margin-top: 6px;
        color: var(--ink-3); font-size: var(--t-xs); letter-spacing: 0.05em;
    }
    .pt-insp__head .meta .k { color: var(--ink-4); text-transform: uppercase; }
    .pt-insp__head .meta .v { color: var(--ink-2); margin-left: 4px; font-variant-numeric: tabular-nums; }
    .pt-insp__eyebrow {
        color: var(--ink-3);
        letter-spacing: 0.12em;
        text-transform: uppercase;
        font-size: var(--t-xs);
    }
    .pt-insp__seq {
        margin-left: auto;
        color: var(--ink-4);
        font-size: var(--t-xs);
        font-variant-numeric: tabular-nums;
    }
    .pt-insp__empty-steps {
        text-align: left;
        max-width: 280px;
        margin: 0 auto;
        color: var(--ink-3);
    }
    
    .pt-tag {
        display: inline-flex; align-items: center; gap: 4px;
        height: 18px; padding: 0 6px;
        background: var(--trace-panel-2); border: 1px solid var(--trace-line);
        color: var(--ink-2); font-size: var(--t-xs); letter-spacing: 0.05em;
    }
    .pt-tag.cb { color: var(--dir-cb); border-color: color-mix(in oklab, var(--dir-cb) 35%, transparent); background: var(--dir-cb-soft); }
    .pt-tag.sb { color: var(--dir-sb); border-color: color-mix(in oklab, var(--dir-sb) 35%, transparent); background: var(--dir-sb-soft); }
    
    .pt-insp__tabs button .badge {
        display: inline-flex; align-items: center; justify-content: center;
        min-width: 14px; height: 14px; padding: 0 4px; margin-left: 4px;
        background: var(--bg-2); color: var(--ink-3); font-size: 9px;
    }
    .pt-insp__tabs button.is-on .badge { background: var(--acc-soft); color: var(--acc); }
    
    .pt-insp__body { flex: 1; padding: 12px 14px; min-height: 0; background: color-mix(in oklab, var(--bg-0) 42%, transparent); }
    
    .pt-insp__actions {
        display: flex; gap: 4px; flex-wrap: wrap;
        padding: 6px 8px;
        background: color-mix(in oklab, var(--bg-0) 44%, transparent); border-bottom: 1px solid var(--trace-line);
    }
    
    .pt-json {
        margin: 0;
        font-size: var(--t-sm);
        line-height: 1.55;
        white-space: normal;
        overflow-wrap: anywhere;
    }
    .pt-json .row {
        display: block;
        padding-left: calc(var(--depth, 0) * 1.4em);
        text-indent: 0;
        min-height: 1.55em;
    }
    .pt-json .k { color: var(--ink); }
    .pt-json .s { color: var(--sub-self); }
    .pt-json .n { color: var(--sub-win); }
    .pt-json .b { color: var(--sub-hud); }
    .pt-json .nul { color: var(--ink-4); font-style: italic; }
    .pt-json .brace, .pt-json .bracket, .pt-json .comma, .pt-json .colon { color: var(--ink-3); }
    .pt-json .row.changed {
        background: color-mix(in oklab, var(--warn) 14%, transparent);
        box-shadow: inset 2px 0 0 var(--warn);
    }
    
    .pt-muts { display: flex; flex-direction: column; gap: 4px; }
    .pt-mut {
        display: grid; grid-template-columns: 1fr auto; gap: 8px;
        padding: 6px 8px; font-size: var(--t-sm);
        background: var(--trace-panel-2); border: 1px solid var(--trace-line);
    }
    .pt-mut .field { color: var(--ink); }
    .pt-mut .vals { display: flex; gap: 6px; color: var(--ink-3); font-size: var(--t-xs); font-variant-numeric: tabular-nums; }
    .pt-mut .vals .from { color: var(--ink-4); text-decoration: line-through; }
    .pt-mut .vals .arrow { color: var(--ink-4); }
    .pt-mut .vals .to { color: var(--acc); }
    
    .pt-related { display: flex; flex-direction: column; gap: 2px; }
    .pt-related__group { margin-top: 8px; }
    .pt-related__group h4 {
        margin: 0 0 4px; padding: 0;
        color: var(--ink-3); font-size: var(--t-xs);
        letter-spacing: 0.08em; text-transform: uppercase;
    }
    .pt-related__row {
        display: grid; grid-template-columns: 50px 22px 1fr 50px;
        align-items: center; gap: 6px; padding: 3px 6px;
        width: 100%; text-align: left;
        color: var(--ink-2); font-size: var(--t-sm);
        border-left: 2px solid transparent;
    }
    .pt-related__row:hover { background: var(--trace-hover); border-left-color: var(--acc); }
    .pt-related__row .seq { color: var(--ink-3); font-size: var(--t-xs); font-variant-numeric: tabular-nums; }
    .pt-related__row .dir { font-weight: 700; }
    .pt-related__row.is-cb .dir { color: var(--dir-cb); }
    .pt-related__row.is-sb .dir { color: var(--dir-sb); }
    .pt-related__row .delta { color: var(--ink-4); font-size: var(--t-xs); text-align: right; font-variant-numeric: tabular-nums; }
    
    .pt-diff__head {
        display: flex; gap: 12px; align-items: baseline;
        margin-bottom: 8px; padding-bottom: 8px;
        color: var(--ink-3); font-size: var(--t-xs);
        letter-spacing: 0.05em; text-transform: uppercase;
        border-bottom: 1px dashed var(--line);
    }
    .pt-diff__head .v { color: var(--ink-2); }
    .pt-diff__row {
        display: grid; grid-template-columns: 1fr 12px 1fr;
        align-items: center; gap: 8px; padding: 4px 6px;
        font-size: var(--t-sm);
        border-bottom: 1px solid color-mix(in oklab, var(--line) 50%, transparent);
    }
    .pt-diff__row.changed { background: color-mix(in oklab, var(--warn) 7%, transparent); }
    .pt-diff__row .field {
        grid-column: 1 / -1; padding-top: 2px;
        color: var(--ink-4); font-size: var(--t-xs);
        letter-spacing: 0.08em; text-transform: uppercase;
    }
    .pt-diff__row .from { color: var(--ink-3); }
    .pt-diff__row .to   { color: var(--acc); }
    .pt-diff__row .arr  { color: var(--ink-4); text-align: center; }
    
    /* ───── status bar ───────────────────────────────────────────────── */
    .pt-status {
        display: flex; align-items: center; gap: 12px;
        padding: 0 14px; height: 28px;
        flex-shrink: 0; white-space: nowrap; overflow: hidden;
        background: var(--trace-panel); border-top: 1px solid var(--trace-line);
        color: var(--ink-3); font-size: var(--t-xs); letter-spacing: 0.04em;
    }
    .pt-status .k { color: var(--ink-4); text-transform: uppercase; letter-spacing: 0.1em; }
    .pt-status .v { color: var(--ink-2); margin-left: 4px; }
    .pt-status .v.acc { color: var(--acc); }
    .pt-status .v.warn { color: var(--warn); }
    .pt-status .v.danger { color: var(--danger); }
    .pt-status .sep { color: var(--ink-4); }
    .pt-status .right { margin-left: auto; display: flex; gap: 12px; }

    @container packet-trace (max-width: 1180px) {
        .pt-main {
            grid-template-columns: minmax(144px, 172px) minmax(0, 1fr) 8px minmax(240px, var(--pt-inspector-w, 280px));
        }
        .pt-stream__head,
        .pt-row,
        .pt-group {
            grid-template-columns: 14px 48px 42px 16px minmax(200px, 1fr) 44px;
            gap: 6px;
            padding-inline: 8px;
        }
        .pt-stream__head > :nth-child(6),
        .pt-row .subj { display: none; }
        .pt-row .summary { display: none; }
        .pt-row .class .name { max-width: none; }
    }

    @container packet-trace (max-width: 960px) {
        .pt-main {
            grid-template-columns: minmax(164px, 26%) minmax(0, 1fr);
            grid-template-rows: minmax(240px, 1fr) auto var(--pt-inspector-h, 38vh);
        }
        .pt-resize--v { display: none; }
        .pt-resize--h { display: block; }
        .pt-stream { border-right: 0; }
        .pt-insp {
            grid-column: 1 / -1;
            min-height: 0;
        }
    }

    @container packet-trace (max-width: 720px) {
        .pt-top { align-items: stretch; }
        .pt-controls {
            width: 100%;
            margin-left: 0;
            flex-wrap: wrap;
        }
        .pt-top .search-inline--bar { flex-basis: 100%; }
        .pt-main {
            grid-template-columns: 1fr;
            grid-template-rows: auto minmax(240px, 1fr) auto var(--pt-inspector-h, 40vh);
            overflow: auto;
        }
        .pt-facets {
            max-height: 220px;
            border-right: 0;
            border-bottom: 1px solid var(--trace-line);
        }
        .pt-stream {
            min-height: 240px;
            border-right: 0;
        }
        .pt-insp {
            grid-column: auto;
            min-height: 0;
        }
        .pt-status .right { display: none; }
    }
    
    .pt-help h2 { margin: 0 0 16px; color: var(--acc); font-size: var(--t-lg); letter-spacing: 0.04em; }
    .pt-help h3 { margin: 16px 0 8px; color: var(--ink); font-size: var(--t-sm); letter-spacing: 0.1em; text-transform: uppercase; }
    .pt-help table { width: 100%; border-collapse: collapse; font-size: var(--t-sm); }
    .pt-help td { padding: 4px 8px; vertical-align: top; }
    .pt-help td.k { color: var(--ink-3); width: 38%; }
    .pt-help td.v { color: var(--ink-2); }
    .pt-help kbd {
        display: inline-flex; align-items: center; justify-content: center;
        min-width: 22px; height: 20px; padding: 0 6px;
        background: var(--bg-2); border: 1px solid var(--line); border-bottom-width: 2px;
        color: var(--ink); font-size: var(--t-xs);
    }
    .pt-help kbd + kbd { margin-left: 2px; }
    .pt-help code {
        padding: 1px 6px;
        background: var(--bg-2); border: 1px solid var(--line);
        color: var(--acc); font-size: var(--t-xs);
    }
    .pt-help .close { position: absolute; top: 14px; right: 18px; color: var(--ink-3); font-size: 16px; }
    
    /* ───── tweaks panel ─────────────────────────────────────────────── */
    .pt-tweaks {
        position: absolute;
        right: 16px;
        bottom: 50px;
        z-index: 40;
        width: 240px;
        font-size: var(--t-sm);
    }
    .pt-tweaks header {
        display: flex; align-items: center; justify-content: space-between;
        padding: 8px 12px;
        background: var(--sunk); border-bottom: 1px solid var(--line);
        color: var(--acc); font-size: var(--t-xs);
        letter-spacing: 0.1em; text-transform: uppercase;
    }
    .pt-tweaks header button { color: var(--ink-3); }
    .pt-tweaks header button:hover { color: var(--ink); }
    .pt-tweaks .group { padding: 8px 12px; border-bottom: 1px dashed var(--line); }
    .pt-tweaks .group h4 { margin: 0 0 6px; color: var(--ink-4); font-size: var(--t-xs); letter-spacing: 0.1em; text-transform: uppercase; }
    .pt-tweaks .swatches { display: flex; gap: 6px; }
    .pt-tweaks .swatches button { width: 22px; height: 22px; border: 1px solid var(--line); }
    .pt-tweaks .swatches button.is-on { border-color: var(--ink); box-shadow: 0 0 0 1px var(--acc); }
    .pt-tweaks .seg-control { width: 100%; }
    .pt-tweaks .seg-control > button { flex: 1; }
    
    /* density variants */
    .pt[data-density="compact"] { --row-h: 22px; }
        }
    }
</style>
