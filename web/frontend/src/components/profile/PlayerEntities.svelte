<script module lang="ts">
    import { showEntityTooltip, moveEntityTooltip, hideEntityTooltip } from '../../state/entityTooltip.svelte.ts';
    import { prettifyType } from '../../lib/assets.ts';

    const GROUPS = [
        { id: 'players',     label: 'Players',     glyph: '◆', color: 'oklch(78% 0.18 148)' },
        { id: 'hostile',     label: 'Hostile',     glyph: '▲', color: 'oklch(70% 0.20 25)'  },
        { id: 'passive',     label: 'Passive',     glyph: '■', color: 'oklch(78% 0.16 70)'  },
        { id: 'items',       label: 'Items',       glyph: '+', color: 'oklch(74% 0.16 200)' },
        { id: 'projectiles', label: 'Projectiles', glyph: '·', color: 'oklch(80% 0.18 80)'  },
        { id: 'vehicles',    label: 'Vehicles',    glyph: '◇', color: 'oklch(72% 0.17 270)' },
    ];
    const COLOR_OF = Object.fromEntries(GROUPS.map(g => [g.id, g.color]));
    const GLYPH_OF = Object.fromEntries(GROUPS.map(g => [g.id, g.glyph]));

    const RANGES = [32, 64, 128, 256];

    function distXZ(e, px, pz) {
        const dx = e.x - px, dz = e.z - pz;
        return Math.hypot(dx, dz);
    }

    function formatDelta(prev, next) {
        const a = Number(prev), b = Number(next);
        if (!Number.isFinite(a) || !Number.isFinite(b)) return null;
        const d = b - a;
        if (d === 0) return { text: '·', sign: 'zero' };
        const sign = d > 0 ? 'pos' : 'neg';
        const abs = Math.abs(d);
        const fmt = Number.isInteger(d) ? abs.toString() : abs.toFixed(2).replace(/\.?0+$/, '');
        return { text: (d > 0 ? '+' : '−') + fmt, sign };
    }
</script>

<script lang="ts">
    import { api } from '../../lib/api.ts';
    import { fmtAge, shortClass } from '../../lib/util.ts';
    import { busStatus } from '../../state/bus.svelte.ts';
    import Panel from '../ui/Panel.svelte';

    let { player, paused = false } = $props();

    const uuid = $derived(player?.uuid);

    const entities = $derived.by(() => {
        const self = String(uuid || '').toLowerCase();
        return (player?.visibleEntities || []).filter(e =>
            !e.uuid || String(e.uuid).toLowerCase() !== self);
    });
    const px = $derived(player?.posX ?? 0);
    const pz = $derived(player?.posZ ?? 0);

    let groupFilter = $state(null);
    let search = $state('');
    let sortBy = $state('distance');
    let selectedId = $state(null);
    let range = $state(128);
    let detail = $state(null);

    const counts = $derived.by(() => {
        const out = Object.fromEntries(GROUPS.map(g => [g.id, 0]));
        for (const e of entities) if (out[e.group] !== undefined) out[e.group]++;
        return out;
    });

    const visible = $derived.by(() => {
        const q = search.toLowerCase();
        const rows = entities.filter(e => {
            if (groupFilter && e.group !== groupFilter) return false;
            if (q && !(e.type || '').toLowerCase().includes(q) && !String(e.id).includes(q)) return false;
            return true;
        });
        if (sortBy === 'distance')      rows.sort((a, b) => distXZ(a, px, pz) - distXZ(b, px, pz));
        else if (sortBy === 'type')     rows.sort((a, b) => (a.type || '').localeCompare(b.type || ''));
        else if (sortBy === 'id')       rows.sort((a, b) => a.id - b.id);
        return rows;
    });

    $effect(() => {
        if (selectedId == null || !uuid) { detail = null; return; }
        let alive = true;
        let first = true;
        const fetchOnce = async () => {
            try {
                const d = await api(`/players/${uuid}/entities/${selectedId}`);
                if (!alive) return;
                detail = { data: d };
            } catch (e) {
                if (alive && first) detail = { error: String(e.message || e) };
            } finally {
                first = false;
            }
        };
        detail = { loading: true };
        fetchOnce();
        const id = paused ? null : setInterval(fetchOnce, 1000);
        return () => { alive = false; if (id) clearInterval(id); };
    });

    function project(e) {
        const dx = e.x - px, dz = e.z - pz;
        const d = Math.hypot(dx, dz);
        if (d > range) return null;
        return { x: (dx / range) * 95, y: (dz / range) * 95, d };
    }

    const yaw = $derived(player?.yaw || 0);
    const hasPlayer = $derived(player?.posX != null);
    const now = $derived(busStatus.now);
</script>

<div class="ent-shell">
    <Panel
        title="Entities in view"
        meta={`${entities.length} tracked`}
        flush
    >
        {#snippet actions()}
            <div class="btn-row">
                <button class={sortBy === 'distance' ? 'primary sm' : 'ghost sm'} onclick={() => sortBy = 'distance'}>Distance</button>
                <button class={sortBy === 'type'     ? 'primary sm' : 'ghost sm'} onclick={() => sortBy = 'type'}>Type</button>
                <button class={sortBy === 'id'       ? 'primary sm' : 'ghost sm'} onclick={() => sortBy = 'id'}>ID</button>
            </div>
        {/snippet}
        <div class="ent-filters">
            {#each GROUPS as g (g.id)}
                <button
                    type="button"
                    class={'ent-filter' + (groupFilter === g.id ? ' on' : '')}
                    style:--gc={g.color}
                    onclick={() => groupFilter = groupFilter === g.id ? null : g.id}
                >
                    <span class="ent-filter__glyph">{g.glyph}</span>
                    <span class="ent-filter__label">{g.label}</span>
                    <span class="ent-filter__count">{counts[g.id] || 0}</span>
                </button>
            {/each}
            <input
                class="ent-search"
                placeholder="Search type or id…"
                oninput={e => search = e.target.value}
            />
        </div>
    </Panel>

    <div class="ent-body">
        <Panel title="Radar" meta={`${range} blocks`} flush>
            {#snippet actions()}
                <div class="btn-row">
                    {#each RANGES as r (r)}
                        <button class={range === r ? 'primary sm' : 'ghost sm'} onclick={() => range = r}>{r}</button>
                    {/each}
                </div>
            {/snippet}
            <div class="ent-radar">
                <svg viewBox="-100 -100 200 200" preserveAspectRatio="xMidYMid meet">
                    {#each [25, 50, 75, 100] as r (r)}
                        <circle cx="0" cy="0" r={r} fill="none" stroke="var(--ink-4)" stroke-opacity="0.3" stroke-width="0.4" stroke-dasharray="2 3" />
                    {/each}
                    <line x1="-100" y1="0" x2="100" y2="0" stroke="var(--ink-4)" stroke-opacity="0.2" stroke-width="0.3" />
                    <line x1="0" y1="-100" x2="0" y2="100" stroke="var(--ink-4)" stroke-opacity="0.2" stroke-width="0.3" />
                    {#each [25, 50, 75, 100] as r (r)}
                        <text x="2" y={-r - 1.5} font-size="4" fill="var(--ink-4)">
                            {Math.round((r / 100) * range)}
                        </text>
                    {/each}

                    {#if hasPlayer}
                        {#each visible as e (e.id)}
                            {@const p = project(e)}
                            {#if p}
                                {@const c = COLOR_OF[e.group] || 'var(--ink-3)'}
                                {@const sel = selectedId === e.id}
                                <g
                                    onclick={() => selectedId = e.id}
                                    onkeydown={ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); selectedId = e.id; } }}
                                    onpointerenter={ev => showEntityTooltip({ ...e, distance: p.d }, ev)}
                                    onpointermove={moveEntityTooltip}
                                    onpointerleave={hideEntityTooltip}
                                    style="cursor: pointer"
                                    role="button"
                                    tabindex="0"
                                    aria-label={'Entity ' + (e.type || 'unknown') + (sel ? ' (selected)' : '')}
                                >
                                    {#if sel}
                                        <circle cx={p.x} cy={p.y} r="6.5" fill="none" stroke={c} stroke-opacity="0.7" stroke-width="0.6" />
                                        <circle cx={p.x} cy={p.y} r={3} fill={c} />
                                    {:else}
                                        <circle cx={p.x} cy={p.y} r={1.8} fill={c} opacity={0.85} />
                                    {/if}
                                </g>
                            {/if}
                        {/each}
                        <g transform={`rotate(${yaw + 180})`}>
                            <polygon points="0,-6 -4,5 0,2 4,5" fill="var(--acc)" stroke="#000" stroke-width="0.6" stroke-linejoin="miter" />
                        </g>
                    {/if}
                    <text x="0" y="-90" text-anchor="middle" font-size="6" fill="var(--ink-4)">N</text>
                </svg>
                <div class="ent-radar__legend">
                    {#each GROUPS as g (g.id)}
                        <span class="ent-radar__lg">
                            <span class="ent-radar__lg-sw" style:background={g.color}></span>
                            {g.label}
                        </span>
                    {/each}
                </div>
            </div>
        </Panel>

        <Panel title="Constellation" meta={`${visible.length} ${groupFilter ? '· ' + groupFilter : ''}`} flush>
            <div class="ent-list">
                {#each visible as e (e.id)}
                    {@const d = distXZ(e, px, pz)}
                    {@const closeness = Math.max(0.4, 1 - d / 256)}
                    {@const c = COLOR_OF[e.group] || 'var(--ink-3)'}
                    <div
                        class={'ent-card' + (selectedId === e.id ? ' on' : '')}
                        style:--gc={c}
                        style:opacity={closeness}
                        onclick={() => selectedId = selectedId === e.id ? null : e.id}
                        onkeydown={ev => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); selectedId = selectedId === e.id ? null : e.id; } }}
                        role="button"
                        tabindex="0"
                    >
                        <span class="ent-card__glyph">{GLYPH_OF[e.group] || '·'}</span>
                        <div class="ent-card__body">
                            <div class="ent-card__row1">
                                <span class="ent-card__type">{prettifyType(e.type)}</span>
                                <span class="ent-card__id">#{e.id}</span>
                            </div>
                            <div class="ent-card__row2">
                                <span class="ent-card__pos">{e.x.toFixed(0)} {e.y.toFixed(0)} {e.z.toFixed(0)}</span>
                                <span class="ent-card__dist">{d < 1 ? '·' : Math.round(d) + 'm'}</span>
                            </div>
                        </div>
                    </div>
                {/each}
                {#if visible.length === 0}<div class="empty">No entities match.</div>{/if}
            </div>
        </Panel>
    </div>

    {#if selectedId != null}
        <Panel title="Detail" meta="live" flush>
            {#snippet actions()}
                <button type="button" class="ghost sm" onclick={() => selectedId = null}>Close</button>
            {/snippet}
            {#if !detail}
                <div class="empty">Loading detail…</div>
            {:else if detail.loading}
                <div class="empty">Loading detail…</div>
            {:else if detail.error}
                <div class="empty">{detail.error}</div>
            {:else if !detail.data}
                <div class="empty">Entity is no longer in view.</div>
            {:else}
                {@const e = detail.data}
                {@const provenance = Object.entries(e.provenance || {})}
                {@const log = (e.changeLog || []).slice().reverse().slice(0, 20)}
                <div class="ent-detail">
                    <div class="ent-detail__meta">
                        <span class="ent-detail__type">{prettifyType(e.type)}</span>
                        <span class="ent-detail__id">#{e.id}</span>
                        {#if e.uuid}<span class="ent-detail__uuid">{String(e.uuid).slice(0, 8)}</span>{/if}
                        <span class="ent-detail__pos">{e.x?.toFixed(1)} · {e.y?.toFixed(1)} · {e.z?.toFixed(1)}</span>
                        <span class="ent-detail__spawn">spawn #{e.spawnSeq}</span>
                        <span class="ent-detail__count">{e.packetCount} packets</span>
                    </div>
                    <div class="ent-detail__cols">
                        <div>
                            <div class="ent-detail__h">Field state</div>
                            <div class="ent-detail__list">
                                {#each provenance as [field, src] (field)}
                                    <div class="ent-detail__row">
                                        <span class="lbl">{field}</span>
                                        <span class="src">
                                            <span class="acc">{shortClass(src.packetClass || '').replace(/Packet$/, '')}</span>
                                            #{src.seq} · {fmtAge(now - src.ts)} ago
                                        </span>
                                    </div>
                                {/each}
                                {#if provenance.length === 0}<div class="empty">No fields tracked yet.</div>{/if}
                            </div>
                        </div>
                        <div>
                            <div class="ent-detail__h">Recent changes · {log.length}</div>
                            <div class="ent-detail__list">
                                {#each log as c, i (i)}
                                    {@const delta = formatDelta(c.prev, c.value)}
                                    <div class="ent-detail__chg">
                                        <span class="t">{fmtAge(now - (c.source?.ts || 0))} ago</span>
                                        <span class="field">{c.field}</span>
                                        <span class="diff">
                                            <span class="from">{String(c.prev ?? '—')}</span>
                                            <span class="arrow">→</span>
                                            <span class="to">{String(c.value ?? '—')}</span>
                                        </span>
                                        <span class={'delta' + (delta?.sign ? ' ' + delta.sign : '')}>
                                            {delta?.text ?? ''}
                                        </span>
                                    </div>
                                {/each}
                                {#if log.length === 0}<div class="empty">No mutations yet.</div>{/if}
                            </div>
                        </div>
                    </div>
                </div>
            {/if}
        </Panel>
    {/if}
</div>

<style>
    @layer pages {
        :global {
    /* ---- Profile · Entities tab ------------------------------------ */
    .ent-shell { display: grid; gap: var(--pad-3); }

    .ent-body {
        display: grid;
        grid-template-columns: minmax(280px, 420px) minmax(0, 1fr);
        gap: var(--pad-3);
        align-items: start;
    }
    @media (max-width: 1100px) { .ent-body { grid-template-columns: 1fr; } }

    .ent-filters {
        display: flex;
        flex-wrap: wrap;
        align-items: center;
        gap: 6px;
        padding: var(--pad-3);
        border-bottom: 1px solid var(--line);
    }

    .ent-filter {
        display: inline-flex;
        align-items: center;
        gap: 6px;
        padding: 4px 8px;
        font-size: var(--t-xs);
        line-height: 1;
        color: var(--ink-3);
        background: var(--bg-2);
        border: 1px solid var(--line);
        text-transform: uppercase;
        cursor: pointer;
        user-select: none;
        transition: background var(--motion), color var(--motion), border-color var(--motion);
        &:hover { color: var(--ink); border-color: var(--line-2); }
        &.on {
            color: var(--gc);
            border-color: var(--gc);
            background: color-mix(in oklab, var(--gc) 14%, var(--bg-1));
            .ent-filter__count { background: var(--gc); color: var(--bg-0); }
        }

        .ent-filter__glyph { color: var(--gc); font-size: var(--t-sm); }
        .ent-filter__count {
            color: var(--ink-2);
            font-variant-numeric: tabular-nums;
            background: var(--bg-3);
            padding: 0 6px;
        }
    }

    .ent-search {
        margin-left: auto;
        min-width: 180px;
        padding: 4px 8px;
        font-size: var(--t-xs);
    }

    .ent-radar {
        padding: var(--pad-3);
        display: grid;
        grid-template-rows: 1fr auto;
        gap: var(--pad-2);
        svg {
            width: 100%;
            aspect-ratio: 1;
            display: block;
            background: var(--sunk);
            box-shadow: var(--bevel-sunk);
        }
        .ent-radar__legend {
            display: flex;
            flex-wrap: wrap;
            gap: 6px var(--pad-3);
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
        }
        .ent-radar__lg { display: inline-flex; align-items: center; gap: 4px; }
        .ent-radar__lg-sw { width: 8px; height: 8px; display: inline-block; }
    }

    .ent-list {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
        gap: 1px;
        padding: 1px;
        background: var(--line);
        max-height: 640px;
        overflow: auto;
    }

    .ent-card {
        display: grid;
        grid-template-columns: 26px minmax(0, 1fr);
        gap: var(--pad-2);
        padding: 6px var(--pad-2);
        background: var(--bg-1);
        border-left: 2px solid transparent;
        cursor: pointer;
        transition: background var(--motion), border-color var(--motion);
        min-width: 0;
        &:hover { background: var(--bg-2); }
        &.on {
            background: color-mix(in oklab, var(--gc) 12%, var(--bg-1));
            border-left-color: var(--gc);
            opacity: 1 !important;
        }

        .ent-card__glyph {
            display: grid;
            place-items: center;
            width: 26px;
            height: 26px;
            background: color-mix(in oklab, var(--gc) 18%, var(--bg-2));
            color: var(--gc);
            font-size: var(--t-md);
            line-height: 1;
            align-self: center;
        }
        .ent-card__body { display: grid; gap: 1px; min-width: 0; }
        .ent-card__row1, .ent-card__row2 {
            display: flex;
            align-items: baseline;
            justify-content: space-between;
            gap: 4px;
            min-width: 0;
        }
        .ent-card__row2 { font-size: var(--t-xs); color: var(--ink-4); font-variant-numeric: tabular-nums; }
        .ent-card__type {
            color: var(--ink);
            font-size: var(--t-sm);
            line-height: 1;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .ent-card__id {
            color: var(--ink-4);
            font-size: var(--t-xs);
            font-variant-numeric: tabular-nums;
            flex-shrink: 0;
        }
        .ent-card__pos  { color: var(--ink-3); }
        .ent-card__dist { color: var(--gc); font-variant-numeric: tabular-nums; }
    }

    .ent-detail {
        display: grid;
        gap: var(--pad-3);
        .ent-detail__meta {
            display: flex;
            flex-wrap: wrap;
            align-items: baseline;
            gap: var(--pad-3);
            padding: var(--pad-3) var(--pad-3) 0;
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
        }
        .ent-detail__type {
            color: var(--ink);
            font-size: var(--t-md);
            line-height: 1;
            text-transform: none;
        }
        .ent-detail__id { color: var(--acc); }
        .ent-detail__uuid, .ent-detail__pos { color: var(--ink-3); font-variant-numeric: tabular-nums; text-transform: none; }

        .ent-detail__cols {
            display: grid;
            grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr);
            gap: var(--pad-3);
            padding: 0 var(--pad-3) var(--pad-3);
        }
        .ent-detail__h {
            font-size: var(--t-xs);
            color: var(--ink-3);
            text-transform: uppercase;
            margin-bottom: 6px;
        }

        .ent-detail__list {
            display: grid;
            gap: 1px;
            background: var(--line);
            max-height: 320px;
            overflow: auto;
        }

        .ent-detail__row, .ent-detail__chg {
            display: grid;
            align-items: baseline;
            gap: var(--pad-2);
            padding: 6px var(--pad-3);
            background: var(--bg-1);
            font-size: var(--t-xs);
            line-height: 1;
        }
        .ent-detail__row {
            grid-template-columns: 80px minmax(0, 1fr);
            .lbl { color: var(--ink-4); text-transform: uppercase; }
            .src {
                color: var(--ink-2);
                .acc { color: var(--acc); }
            }
        }
        .ent-detail__chg {
            grid-template-columns: 60px 90px minmax(0, 1fr) 56px;
            .t { color: var(--ink-4); font-variant-numeric: tabular-nums; }
            .field {
                color: var(--ink);
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }
            .diff {
                display: inline-flex;
                align-items: baseline;
                gap: 4px;
                min-width: 0;
                overflow: hidden;
            }
            .from {
                color: var(--ink-4);
                text-decoration: line-through;
                text-decoration-color: color-mix(in oklab, var(--danger) 50%, transparent);
            }
            .arrow { color: var(--ink-4); flex-shrink: 0; }
            .to { color: var(--acc); font-variant-numeric: tabular-nums; }
            .delta {
                text-align: right;
                font-variant-numeric: tabular-nums;
                color: var(--ink-4);
                font-size: var(--t-xs);
                &.pos  { color: var(--acc); }
                &.neg  { color: var(--danger); }
                &.zero { color: var(--ink-4); }
            }
        }
    }
    @media (max-width: 1100px) {
        .ent-detail__cols { grid-template-columns: 1fr; }
    }
        }
    }
</style>
