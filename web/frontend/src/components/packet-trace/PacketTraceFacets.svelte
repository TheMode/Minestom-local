<script lang="ts">
    import type { PacketRow } from '../../lib/packetAgg.ts';
    import { pktLabel } from '../../lib/packetAgg.ts';
    import { classColor } from '../../lib/packetTraceDsl.ts';
    import type { FacetMode, Bookmark, Breakpoint, Saved, SideTab } from './types.ts';

    type FacetField = 'direction' | 'state' | 'subjectGroup';

    interface Props {
        tab?: SideTab;
        rows?: readonly PacketRow[];
        filters?: Record<string, Record<string, FacetMode>>;
        classCounts?: Map<string, number>;
        classFilter?: Record<string, FacetMode>;
        classQuery?: string;
        bookmarks?: Bookmark[];
        breakpoints?: Breakpoint[];
        saved?: Saved[];
        currentSeq?: number | null;
        currentQuery?: string;
        onSetTab?: (t: SideTab) => void;
        onSetFilter?: (field: string | null, value: string | null, mode: FacetMode) => void;
        onSetClassFilter?: (v: Record<string, FacetMode>) => void;
        onSetClassQuery?: (v: string) => void;
        onJumpBookmark?: (seq: number) => void;
        onAddBookmark?: (b: Bookmark) => void;
        onRemoveBookmark?: (i: number) => void;
        onToggleBreakpoint?: (i: number) => void;
        onAddBreakpoint?: (b: Omit<Breakpoint, 'id'>) => void;
        onRemoveBreakpoint?: (i: number) => void;
        onLoadSaved?: (q: string) => void;
        onAddSaved?: (s: Saved) => void;
        onRemoveSaved?: (i: number) => void;
    }

    let {
        tab = 'filters',
        rows = [],
        filters = {},
        classCounts = new Map<string, number>(),
        classFilter = {},
        classQuery = '',
        bookmarks = [],
        breakpoints = [],
        saved = [],
        currentSeq = null,
        currentQuery = '',
        onSetTab = () => {},
        onSetFilter = () => {},
        onSetClassFilter = () => {},
        onSetClassQuery = () => {},
        onJumpBookmark = () => {},
        onAddBookmark = () => {},
        onRemoveBookmark = () => {},
        onToggleBreakpoint = () => {},
        onAddBreakpoint = () => {},
        onRemoveBreakpoint = () => {},
        onLoadSaved = () => {},
        onAddSaved = () => {},
        onRemoveSaved = () => {},
    }: Props = $props();

    const FACET_GROUPS: { id: FacetField; title: string; tones?: Record<string, string>; fmt: (v: string) => string }[] = [
        {
            id: 'direction', title: 'Direction',
            tones: { CLIENTBOUND: 'var(--dir-cb)', SERVERBOUND: 'var(--dir-sb)' },
            fmt: v => v === 'CLIENTBOUND' ? '↓  CB' : '↑  SB',
        },
        { id: 'state', title: 'Phase', fmt: v => v },
        {
            id: 'subjectGroup', title: 'Subject group',
            tones: {
                self:  'var(--sub-self)',
                ent:   'var(--sub-ent)',
                world: 'var(--sub-world)',
                hud:   'var(--sub-hud)',
                win:   'var(--sub-win)',
                net:   'var(--sub-net)',
                chat:  'var(--sub-chat)',
            },
            fmt: v => v,
        },
    ];

    const facetCounts = $derived.by(() => {
        const counts: Record<FacetField, Map<string, number>> = {
            direction: new Map(), state: new Map(), subjectGroup: new Map(),
        };
        for (const p of rows) {
            for (const g of FACET_GROUPS) {
                const v = (p as Record<string, string>)[g.id] || '';
                if (!v) continue;
                counts[g.id].set(v, (counts[g.id].get(v) || 0) + 1);
            }
        }
        return counts;
    });

    const classList = $derived.by(() => {
        const q = classQuery.toLowerCase();
        return [...classCounts.entries()]
            .filter(([k]) => !q || k.toLowerCase().includes(q))
            .sort((a, b) => b[1] - a[1]);
    });

    function cycleFacet(field: FacetField, value: string) {
        const cur = filters[field]?.[value];
        onSetFilter(field, value, nextFacetMode(cur));
    }

    function cycleClass(cls: string) {
        const cur = classFilter[cls];
        const next = nextFacetMode(cur);
        const out = { ...classFilter };
        if (next == null) delete out[cls]; else out[cls] = next;
        onSetClassFilter(out);
    }

    function nextFacetMode(current: FacetMode): FacetMode {
        if (current === 'include') return 'exclude';
        if (current === 'exclude') return null;
        return 'include';
    }

    function facetSymbol(mode: FacetMode): string {
        if (mode === 'include') return '+';
        if (mode === 'exclude') return '−';
        return '';
    }

    function addBookmark(): void {
        if (!currentSeq || !bookmarkDraft) return;
        onAddBookmark({ seq: currentSeq, label: bookmarkDraft });
        bookmarkDraft = '';
    }

    function addBreakpoint(): void {
        if (!breakpointDraft) return;
        onAddBreakpoint({ match: breakpointDraft, label: breakpointDraft, enabled: true });
        breakpointDraft = '';
    }

    function addSaved(): void {
        if (!savedName || !currentQuery) return;
        onAddSaved({ name: savedName, q: currentQuery });
        savedName = '';
    }

    function submitOnEnter(e: KeyboardEvent, submit: () => void): void {
        if (e.key === 'Enter') submit();
    }

    let bookmarkDraft = $state('');
    let breakpointDraft = $state('');
    let savedName = $state('');
</script>

<aside class="pt-facets">
    <nav class="seg-control seg-control--tabs pt-facets__tabs">
        <button class:is-on={tab === 'filters'} type="button" onclick={() => onSetTab('filters')}>Filters</button>
        <button class:is-on={tab === 'bookmarks'} type="button" onclick={() => onSetTab('bookmarks')}>Marks ({bookmarks.length})</button>
        <button class:is-on={tab === 'breaks'} type="button" onclick={() => onSetTab('breaks')}>Breaks ({breakpoints.length})</button>
        <button class:is-on={tab === 'saved'} type="button" onclick={() => onSetTab('saved')}>Saved</button>
    </nav>

    {#if tab === 'filters'}
        <div class="pt-facets__body scroll-thin">
            {#each FACET_GROUPS as g (g.id)}
                {@const entries = [...facetCounts[g.id].entries()].sort((a, b) => b[1] - a[1])}
                {@const hasFilter = !!filters[g.id] && Object.keys(filters[g.id]).length > 0}
                <div class="pt-facet-group">
                    <div class="pt-facet-group__head">
                        <span>{g.title}</span>
                        {#if hasFilter}
                            <button class="reset" type="button" onclick={() => onSetFilter(g.id, null, null)}>reset</button>
                        {:else}
                            <span class="count">{entries.length}</span>
                        {/if}
                    </div>
                    {#each entries as [v, c] (v)}
                        {@const mode = filters[g.id]?.[v]}
                        <button
                            class="pt-facet-row"
                            class:is-include={mode === 'include'}
                            class:is-exclude={mode === 'exclude'}
                            type="button"
                            onclick={() => cycleFacet(g.id, v)}
                            title={mode ? `${mode} · click to cycle` : 'click to include, again to exclude'}
                        >
                            <span class="dot" style:background={g.tones?.[v] || 'transparent'}></span>
                            <span class="sym">{facetSymbol(mode)}</span>
                            <span class="lbl">{g.fmt(v)}</span>
                            <span class="num">{c.toLocaleString()}</span>
                        </button>
                    {/each}
                </div>
            {/each}

            <div class="pt-facet-group">
                <div class="pt-facet-group__head">
                    <span>Class</span>
                    {#if Object.keys(classFilter).length > 0}
                        <button class="reset" type="button" onclick={() => onSetClassFilter({})}>reset</button>
                    {:else}
                        <span class="count">{classCounts.size}</span>
                    {/if}
                </div>
                <div class="search-inline search-inline--facet">
                    <input value={classQuery} oninput={e => onSetClassQuery(e.currentTarget.value)} placeholder="search classes…" />
                </div>
                {#each classList as [cls, count] (cls)}
                    {@const mode = classFilter[cls]}
                    <button
                        class="pt-facet-row"
                        class:is-include={mode === 'include'}
                        class:is-exclude={mode === 'exclude'}
                        type="button"
                        onclick={() => cycleClass(cls)}
                    >
                        <span class="dot" style:background={classColor(cls)}></span>
                        <span class="sym">{facetSymbol(mode)}</span>
                        <span class="lbl">{pktLabel(cls)}</span>
                        <span class="num">{count.toLocaleString()}</span>
                    </button>
                {/each}
            </div>
        </div>
    {:else if tab === 'bookmarks'}
        <div class="pt-facets__body scroll-thin">
            <div class="pt-add-form">
                <input
                    value={bookmarkDraft}
                    oninput={e => { bookmarkDraft = e.currentTarget.value; }}
                    placeholder={`bookmark #${currentSeq ?? '—'}`}
                    onkeydown={e => submitOnEnter(e, addBookmark)}
                />
                <button type="button" onclick={addBookmark}>add ★</button>
            </div>
            {#if bookmarks.length === 0}
                <div class="empty empty--trace">No bookmarks yet.<br />Press <kbd>B</kbd> on any packet to add.</div>
            {/if}
            <div class="pt-list">
                {#each bookmarks as b, i (`${b.seq}-${i}`)}
                    <button class="pt-list__item" type="button" onclick={() => onJumpBookmark(b.seq)}>
                        <span class="glyph">★</span>
                        <span>
                            <div>#{b.seq.toLocaleString()}</div>
                            <div class="note">{b.label}</div>
                        </span>
                        <span
                            class="del" role="button" tabindex="0"
                            onclick={e => { e.stopPropagation(); onRemoveBookmark(i); }}
                            onkeydown={e => { if (e.key === 'Enter') { e.stopPropagation(); onRemoveBookmark(i); } }}
                            title="remove"
                        >✕</span>
                    </button>
                {/each}
            </div>
        </div>
    {:else if tab === 'breaks'}
        <div class="pt-facets__body scroll-thin">
            <div class="pt-add-form">
                <input
                    value={breakpointDraft}
                    oninput={e => { breakpointDraft = e.currentTarget.value; }}
                    placeholder="DSL — e.g. class:Disconnect"
                    onkeydown={e => submitOnEnter(e, addBreakpoint)}
                />
                <button type="button" onclick={addBreakpoint}>+ break</button>
            </div>
            {#if breakpoints.length === 0}
                <div class="empty empty--trace">Pause when a packet matches a DSL filter.<br /><br />e.g. <code>class:Disconnect</code></div>
            {/if}
            <div class="pt-list">
                {#each breakpoints as b, i (b.id)}
                    <div class="pt-list__item brk" class:disabled={!b.enabled} style:opacity={b.enabled ? 1 : 0.4}>
                        <span
                            class="glyph" role="button" tabindex="0"
                            onclick={() => onToggleBreakpoint(i)}
                            onkeydown={e => { if (e.key === 'Enter') onToggleBreakpoint(i); }}
                            title="toggle"
                            style:cursor="pointer"
                        >{b.enabled ? '⏻' : '◌'}</span>
                        <span>
                            <div style:color="var(--ink)">{b.label}</div>
                            <div class="note">matched {b.hitCount ?? 0} ×</div>
                        </span>
                        <span
                            class="del" role="button" tabindex="0"
                            onclick={() => onRemoveBreakpoint(i)}
                            onkeydown={e => { if (e.key === 'Enter') onRemoveBreakpoint(i); }}
                        >✕</span>
                    </div>
                {/each}
            </div>
        </div>
    {:else}
        <div class="pt-facets__body scroll-thin">
            <div class="pt-add-form">
                <input
                    value={savedName}
                    oninput={e => { savedName = e.currentTarget.value; }}
                    placeholder="save current query as…"
                    onkeydown={e => submitOnEnter(e, addSaved)}
                />
                <button type="button" onclick={addSaved}>save</button>
            </div>
            {#if saved.length === 0}
                <div class="empty empty--trace">Save common queries here.<br />They appear as one-click filters.</div>
            {/if}
            <div class="pt-list">
                {#each saved as s, i (`${s.name}-${i}`)}
                    <button class="pt-list__item" type="button" onclick={() => onLoadSaved(s.q)}>
                        <span class="glyph" style:color="var(--acc)">◇</span>
                        <span>
                            <div>{s.name}</div>
                            <div class="note">{s.q}</div>
                        </span>
                        <span
                            class="del" role="button" tabindex="0"
                            onclick={e => { e.stopPropagation(); onRemoveSaved(i); }}
                            onkeydown={e => { if (e.key === 'Enter') { e.stopPropagation(); onRemoveSaved(i); } }}
                        >✕</span>
                    </button>
                {/each}
            </div>
        </div>
    {/if}
</aside>
