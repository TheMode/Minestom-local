<script lang="ts">
    import type { PacketRow } from '../../lib/packetAgg.ts';
    import { pktLabel } from '../../lib/packetAgg.ts';
    import { fieldsForPacket, isClientBound } from '../../lib/packetTrace.ts';
    import { escapeHtml, fmtTime, humanBytes } from '../../lib/util.ts';
    import type { Related } from './types.ts';

    type JsonLine = { html: string; depth: number; changed?: boolean };

    interface Props {
        row?: PacketRow | null;
        seq?: number;
        record?: { loading?: boolean; full?: Record<string, unknown>; error?: string } | null;
        prevSameClass?: PacketRow | null;
        prevRecord?: Record<string, unknown> | null;
        related?: Related[];
        multi?: Set<number>;
        getRow?: (seq: number) => PacketRow | null;
        isBookmarked?: boolean;
        onClose?: () => void;
        onJumpSeq?: (seq: number) => void;
        onStep?: (d: number) => void;
        onToggleBookmark?: () => void;
        onCopyClass?: () => void;
        onBreakOnClass?: () => void;
    }

    let {
        row = null,
        seq = 0,
        record = null,
        prevSameClass = null,
        prevRecord = null,
        related = [],
        multi = new Set<number>(),
        getRow = () => null,
        isBookmarked = false,
        onClose = () => {},
        onJumpSeq = () => {},
        onStep = () => {},
        onToggleBookmark = () => {},
        onCopyClass = () => {},
        onBreakOnClass = () => {},
    }: Props = $props();

    let tab = $state<'decoded' | 'mutates' | 'related' | 'diff'>('decoded');

    const full = $derived(record?.full as { record?: Record<string, unknown> } | undefined);
    const rec = $derived(full?.record ?? null);

    // Multi-selection forces diff against the *other* selected packet, else compare to prev same-class.
    const diffPrev = $derived.by(() => {
        if (!row) return null;
        if (multi.size === 2) {
            const arr = [...multi].sort((a, b) => a - b);
            const a = getRow(arr[0]);
            const b = getRow(arr[1]);
            if (a && b) return a.seq === row.seq ? b : a;
        }
        return prevSameClass;
    });

    function diffRecords(prev: unknown, cur: unknown): { k: string; a: unknown; b: unknown; changed: boolean }[] {
        const p = (prev || {}) as Record<string, unknown>;
        const c = (cur || {}) as Record<string, unknown>;
        const keys = new Set([...Object.keys(p), ...Object.keys(c)]);
        const out: { k: string; a: unknown; b: unknown; changed: boolean }[] = [];
        for (const k of keys) {
            const sa = JSON.stringify(p[k]), sb = JSON.stringify(c[k]);
            out.push({ k, a: p[k], b: c[k], changed: sa !== sb });
        }
        return out;
    }

    const highlightKeys = $derived.by(() => {
        if (!diffPrev || !rec) return null;
        const prev = (diffPrev === prevSameClass ? prevRecord : null) || null;
        if (!prev) return null;
        return new Set(diffRecords(prev, rec).filter(r => r.changed).map(r => r.k));
    });

    const tabs = $derived([
        { id: 'decoded' as const, label: 'Decoded', badge: undefined as number | undefined },
        { id: 'mutates' as const, label: 'Mutates', badge: row ? fieldsForPacket(row.className).length || undefined : undefined },
        { id: 'related' as const, label: 'Related', badge: related.length },
        { id: 'diff' as const, label: 'Diff', badge: undefined },
    ]);

    const jsonLines = $derived.by(() => {
        if (!rec) return [] as JsonLine[];
        const lines: JsonLine[] = [];
        renderJsonLines(rec, 0, lines, highlightKeys);
        return lines;
    });

    function renderJsonScalar(value: unknown): string {
        if (value === null) return '<span class="nul">null</span>';
        if (typeof value === 'boolean') return `<span class="b">${value}</span>`;
        if (typeof value === 'number') return `<span class="n">${Number.isInteger(value) ? value : value.toFixed(3)}</span>`;
        if (typeof value === 'string') return `<span class="s">"${escapeHtml(value)}"</span>`;
        return `<span>${escapeHtml(String(value))}</span>`;
    }

    function renderJsonLines(value: unknown, depth: number, lines: JsonLine[], hi: Set<string> | null, key?: string, comma = false): void {
        const prefix = key == null ? '' : `<span class="k">${escapeHtml(key)}</span><span class="colon">: </span>`;
        const suffix = comma ? '<span class="comma">,</span>' : '';
        const changed = key != null && hi?.has(key);
        if (Array.isArray(value)) {
            if (value.length === 0) {
                lines.push({ depth, html: `${prefix}<span class="bracket">[]</span>${suffix}`, changed });
                return;
            }
            lines.push({ depth, html: `${prefix}<span class="bracket">[</span>`, changed });
            value.forEach((item, i) => renderJsonLines(item, depth + 1, lines, hi, undefined, i < value.length - 1));
            lines.push({ depth, html: `<span class="bracket">]</span>${suffix}` });
            return;
        }
        if (value === null || typeof value !== 'object') {
            lines.push({ depth, html: `${prefix}${renderJsonScalar(value)}${suffix}`, changed });
            return;
        }
        const obj = value as Record<string, unknown>;
        const keys = Object.keys(obj);
        if (keys.length === 0) {
            lines.push({ depth, html: `${prefix}<span class="brace">{}</span>${suffix}`, changed });
            return;
        }
        lines.push({ depth, html: `${prefix}<span class="brace">{</span>`, changed });
        keys.forEach((k, i) => renderJsonLines(obj[k], depth + 1, lines, hi, k, i < keys.length - 1));
        lines.push({ depth, html: `<span class="brace">}</span>${suffix}` });
    }

    function groupRelated(list: Related[]): Record<'Same subject' | 'Same class', Related[]> {
        const out: Record<'Same subject' | 'Same class', Related[]> = {
            'Same subject': [], 'Same class': [],
        };
        for (const r of list) out[r.reason].push(r);
        return out;
    }

    function fmtMut(v: unknown): string {
        if (v == null) return '—';
        if (typeof v === 'number') return Number.isInteger(v) ? String(v) : v.toFixed(2);
        return String(v);
    }
</script>

<aside class="pt-insp">
    {#if !row}
        <header class="pt-insp__head">
            <div class="pt-insp__head-row">
                <span class="pt-insp__eyebrow">Inspector</span>
            </div>
        </header>
        {#if record?.loading}
            <div class="empty empty--trace empty--spacious">Loading seq #{seq}…</div>
        {:else if record?.error}
            <div class="empty empty--trace empty--spacious">Error · {record.error}</div>
        {:else}
            <div class="empty empty--trace empty--spacious">
                Select a packet to inspect.<br /><br />
                <div class="pt-insp__empty-steps">
                    <div>· click any row to open</div>
                    <div>· shift-click to multi-select / diff</div>
                    <div>· right-click for context actions</div>
                    <div>· press <kbd>B</kbd> to bookmark playhead</div>
                </div>
            </div>
        {/if}
    {:else}
        {@const isCb = isClientBound(row.direction)}
        <header class="pt-insp__head">
            <div class="pt-insp__head-row">
                <span class="pt-tag" class:cb={isCb} class:sb={!isCb}>{isCb ? '↓ CB' : '↑ SB'}</span>
                <span class="pt-tag">{row.state}</span>
                <span class="pt-tag">
                    <i style:width="6px" style:height="6px" style:background={`var(--sub-${row.subjectGroup})`}></i>
                    {row.subjectGroup}
                </span>
                <span class="pt-insp__seq">#{row.seq.toLocaleString()}</span>
                <button class="btn sm icon" type="button" onclick={onClose} title="close (Esc)">✕</button>
            </div>
            <div style:margin-top="8px">
                <span class="class-name">{pktLabel(row.className)}</span>
            </div>
            <div class="meta">
                <span><span class="k">subj</span><span class="v">{row.subjectLabel || '—'}</span></span>
                <span><span class="k">size</span><span class="v">{humanBytes(row.sizeBytes)}</span></span>
                <span><span class="k">ts</span><span class="v">{fmtTime(row.ts)}</span></span>
            </div>
        </header>

        <div class="pt-insp__actions">
            <button class="btn sm" type="button" onclick={() => onStep(-1)} title="prev (←)">◂ prev</button>
            <button class="btn sm" type="button" onclick={() => onStep(1)} title="next (→)">next ▸</button>
            <div class="divider-v"></div>
            <button class="btn sm" class:is-on={isBookmarked} type="button" onclick={onToggleBookmark} title="bookmark (B)">★ bookmark</button>
            <button class="btn sm" type="button" onclick={onCopyClass} title="copy class">⎘ copy</button>
            <button class="btn sm" type="button" onclick={onBreakOnClass} title="add breakpoint">⏻ break-on</button>
        </div>

        <div class="seg-control seg-control--tabs pt-insp__tabs">
            {#each tabs as t (t.id)}
                <button class:is-on={tab === t.id} type="button" onclick={() => { tab = t.id; }}>
                    {t.label}{#if t.badge != null}<span class="badge">{t.badge}</span>{/if}
                </button>
            {/each}
        </div>

        <div class="pt-insp__body scroll-thin">
            {#if tab === 'decoded'}
                {#if record?.loading}
                    <div class="empty empty--trace">Loading…</div>
                {:else if record?.error}
                    <div class="empty empty--trace">Error · {record.error}</div>
                {:else if rec == null}
                    <div class="empty empty--trace">No decoded record in buffer.</div>
                {:else}
                    <div class="pt-json">
                        {#each jsonLines as line, i (i)}
                            <div class="row" class:changed={line.changed} style:--depth={line.depth}>
                                {@html line.html}
                            </div>
                        {/each}
                    </div>
                {/if}

            {:else if tab === 'mutates'}
                {@const fields = fieldsForPacket(row.className)}
                {#if fields.length === 0}
                    <div class="empty empty--trace">This class does not mutate player state directly.</div>
                {:else}
                    <div class="pt-muts">
                        {#each fields as f (f)}
                            {@const recAsObj = (rec ?? {}) as Record<string, unknown>}
                            {@const prevAsObj = (prevRecord ?? {}) as Record<string, unknown>}
                            {@const cur = recAsObj[f.split('.').pop() ?? f] ?? recAsObj[f]}
                            {@const old = prevAsObj[f.split('.').pop() ?? f] ?? prevAsObj[f]}
                            <div class="pt-mut">
                                <span class="field">{f}</span>
                                <span class="vals">
                                    {#if old != null}
                                        <span class="from">{fmtMut(old)}</span>
                                        <span class="arrow">→</span>
                                    {/if}
                                    <span class="to">{fmtMut(cur)}</span>
                                </span>
                            </div>
                        {/each}
                    </div>
                {/if}

            {:else if tab === 'related'}
                {#if related.length === 0}
                    <div class="empty empty--trace">No related packets in the current view.</div>
                {:else}
                    {@const groups = groupRelated(related)}
                    <div class="pt-related">
                        {#each Object.entries(groups) as [name, list] (name)}
                            {#if list.length}
                                <div class="pt-related__group">
                                    <h4>{name} · {list.length}</h4>
                                    {#each list as r (r.row.seq)}
                                        {@const cb = isClientBound(r.row.direction)}
                                        <button
                                            class="pt-related__row"
                                            class:is-cb={cb}
                                            class:is-sb={!cb}
                                            type="button"
                                            onclick={() => onJumpSeq(r.row.seq)}
                                        >
                                            <span class="seq">#{r.row.seq}</span>
                                            <span class="dir">{cb ? '↓' : '↑'}</span>
                                            <span style:white-space="nowrap" style:overflow="hidden" style:text-overflow="ellipsis">
                                                <span style:color="var(--ink)">{pktLabel(r.row.className)}</span>
                                                <span style:color="var(--ink-3)" style:margin-left="6px" style:font-size="var(--t-xs)">{r.row.subjectLabel || ''}</span>
                                            </span>
                                            <span class="delta">{r.dt < 0 ? '' : '+'}{Math.round(r.dt)}ms</span>
                                        </button>
                                    {/each}
                                </div>
                            {/if}
                        {/each}
                    </div>
                {/if}

            {:else if tab === 'diff'}
                {#if !diffPrev}
                    <div class="empty empty--trace">No prior {pktLabel(row.className)} packet in the buffer.</div>
                {:else}
                    {@const rows = diffRecords(prevRecord, rec)}
                    {@const changed = rows.filter(r => r.changed)}
                    <div>
                        <div class="pt-diff__head">
                            <span><span class="v">#{diffPrev.seq}</span> {fmtTime(diffPrev.ts).slice(0, 12)}</span>
                            <span style:color="var(--ink-4)">→</span>
                            <span><span class="v">#{row.seq}</span> {fmtTime(row.ts).slice(0, 12)} · <span style:color="var(--acc)">{changed.length} of {rows.length} changed</span></span>
                        </div>
                        {#each rows as r (r.k)}
                            <div class="pt-diff__row" class:changed={r.changed}>
                                <span class="field">{r.k}</span>
                                <span class="from">{JSON.stringify(r.a)}</span>
                                <span class="arr">→</span>
                                <span class="to">{JSON.stringify(r.b)}</span>
                            </div>
                        {/each}
                    </div>
                {/if}
            {/if}
        </div>
    {/if}
</aside>
