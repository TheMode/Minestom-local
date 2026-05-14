<script module lang="ts">
    type RegistryEntry = { id: string; vanilla: boolean };
    type RegistryGroup = {
        id: string;
        entries: RegistryEntry[];
    };

    function namespaceOf(id: string): string {
        const colon = id.indexOf(':');
        return colon < 0 ? '' : id.slice(0, colon);
    }
    function pathOf(id: string): string {
        const colon = id.indexOf(':');
        return colon < 0 ? id : id.slice(colon + 1);
    }
</script>

<script lang="ts">
    import { api } from '../../lib/api.ts';
    import Panel from '../ui/Panel.svelte';
    import Pill from '../ui/Pill.svelte';
    import ProgressBar from '../ui/ProgressBar.svelte';

    let { player } = $props();

    const uuid = $derived(player?.uuid);

    let registries = $state<RegistryGroup[] | null>(null);
    let loadErr = $state<string | null>(null);

    let selectedId = $state<string | null>(null);
    let customOnly = $state(true);
    let collapseAmbient = $state(true);
    let query = $state('');

    $effect(() => {
        uuid;
        if (!uuid) return;
        let alive = true;
        registries = null;
        loadErr = null;
        (async () => {
            try {
                const r = await api<{ registries: RegistryGroup[] }>('/players/' + uuid + '/registries');
                if (!alive) return;
                registries = r.registries;
            } catch (e) {
                if (!alive) return;
                loadErr = (e as Error).message || 'failed to load registries';
            }
        })();
        return () => { alive = false; };
    });

    /// Pre-compute aggregates for each registry once per data change — the index, filter UI,
    /// and detail pane all consume these.
    type Aggregate = RegistryGroup & {
        total: number;
        customCount: number;
        vanillaCount: number;
        customRatio: number;
    };

    const aggregates = $derived.by<Aggregate[]>(() => {
        if (!registries) return [];
        return registries.map(g => {
            let customCount = 0;
            for (const e of g.entries) if (!e.vanilla) customCount++;
            const total = g.entries.length;
            return {
                ...g,
                total,
                customCount,
                vanillaCount: total - customCount,
                customRatio: total === 0 ? 0 : customCount / total,
            };
        });
    });

    const customized = $derived(aggregates.filter(a => a.customCount > 0)
        .sort((a, b) => b.customCount - a.customCount || a.id.localeCompare(b.id)));
    const ambient = $derived(aggregates.filter(a => a.customCount === 0)
        .sort((a, b) => b.total - a.total || a.id.localeCompare(b.id)));

    const totals = $derived.by(() => {
        let regs = aggregates.length;
        let entries = 0, custom = 0, vanilla = 0;
        let customRegs = 0;
        for (const a of aggregates) {
            entries += a.total;
            custom += a.customCount;
            vanilla += a.vanillaCount;
            if (a.customCount > 0) customRegs++;
        }
        const ratio = entries === 0 ? 0 : custom / entries;
        return { regs, entries, custom, vanilla, customRegs, ratio };
    });

    /// Default-select the registry with the most custom entries — that's almost certainly the
    /// one the user came here for. Falls back to the first registry otherwise.
    $effect(() => {
        if (selectedId !== null) return;
        if (customized.length > 0) selectedId = customized[0].id;
        else if (aggregates.length > 0) selectedId = aggregates[0].id;
    });

    const selected = $derived(aggregates.find(a => a.id === selectedId) || null);

    const filteredEntries = $derived.by(() => {
        if (!selected) return [];
        const q = query.trim().toLowerCase();
        let rows = selected.entries.slice();
        if (customOnly) rows = rows.filter(e => !e.vanilla);
        if (q) rows = rows.filter(e => e.id.toLowerCase().includes(q));
        rows.sort((a, b) => {
            if (a.vanilla !== b.vanilla) return a.vanilla ? 1 : -1;
            return a.id.localeCompare(b.id);
        });
        return rows;
    });

    const visibleCount = $derived(filteredEntries.length);
    const hiddenByFilter = $derived(selected ? selected.total - visibleCount : 0);
</script>

{#snippet meter(ratio)}
    {@const pct = ratio * 100}
    <span class="reg-meter" title={`${pct.toFixed(1)}% custom`}>
        <span class="reg-meter__fill" style:--pct={pct + '%'}></span>
    </span>
{/snippet}

{#snippet registryRow(a)}
    {@const active = a.id === selectedId}
    {@const path = pathOf(a.id)}
    {@const ns = namespaceOf(a.id)}
    <button
        type="button"
        class="reg-rail__row"
        class:on={active}
        class:dim={a.customCount === 0}
        onclick={() => { selectedId = a.id; }}
        aria-current={active ? 'true' : undefined}
    >
        <span class="reg-rail__mark" aria-hidden="true">
            {#if a.customCount > 0}■{:else}▢{/if}
        </span>
        <span class="reg-rail__name">
            {#if ns && ns !== 'minecraft'}<span class="reg-ns reg-ns--custom">{ns}</span><span class="reg-rail__colon">:</span>{/if}
            <span class="reg-rail__path">{path}</span>
        </span>
        <span class="reg-rail__counts">
            {#if a.customCount > 0}
                <span class="reg-rail__custom">{a.customCount}</span>
                <span class="reg-rail__slash">/</span>
            {/if}
            <span class="reg-rail__total">{a.total}</span>
        </span>
        {@render meter(a.customRatio)}
    </button>
{/snippet}

{#snippet entryRow(e, idx)}
    {@const ns = namespaceOf(e.id) || 'minecraft'}
    {@const path = pathOf(e.id)}
    <li class="reg-entry" class:reg-entry--custom={!e.vanilla} class:reg-entry--vanilla={e.vanilla}>
        <span class="reg-entry__rail" aria-hidden="true"></span>
        <span class="reg-entry__idx">{String(idx + 1).padStart(3, '0')}</span>
        <span class="reg-entry__id">
            <span class={e.vanilla ? 'reg-ns reg-ns--vanilla' : 'reg-ns reg-ns--custom'}>{ns}</span><span class="reg-entry__colon">:</span><span class="reg-entry__path">{path}</span>
        </span>
        <span class="reg-entry__badge">{e.vanilla ? 'vanilla' : 'custom'}</span>
    </li>
{/snippet}

<div class="reg-shell">
    {#if loadErr}
        <Panel title="Registries" meta="error">
            <div class="reg-error">
                <pre class="code">{loadErr}</pre>
            </div>
        </Panel>
    {:else if registries === null}
        <Panel title="Registries" meta="loading">
            <div class="empty">Reading per-connection registry tables…</div>
        </Panel>
    {:else}
        <!-- ===== Horizon strip — instrument-panel overview ===== -->
        <section class="reg-horizon" aria-label="Registry overview">
            <div class="reg-horizon__cell reg-horizon__cell--big">
                <span class="reg-horizon__num">{totals.regs}</span>
                <span class="reg-horizon__lbl">Registries</span>
            </div>
            <div class="reg-horizon__cell">
                <span class="reg-horizon__num">{totals.entries.toLocaleString()}</span>
                <span class="reg-horizon__lbl">Entries</span>
            </div>
            <div class="reg-horizon__cell reg-horizon__cell--accent">
                <span class="reg-horizon__num"><span class="reg-horizon__dot" aria-hidden="true"></span>{totals.custom.toLocaleString()}</span>
                <span class="reg-horizon__lbl">Custom</span>
            </div>
            <div class="reg-horizon__cell">
                <span class="reg-horizon__num reg-horizon__num--dim">{totals.vanilla.toLocaleString()}</span>
                <span class="reg-horizon__lbl">Vanilla</span>
            </div>
            <ProgressBar value={totals.ratio} class="reg-horizon__spectrum">
                <span class="reg-horizon__spectrum-num">{(totals.ratio * 100).toFixed(2)}%</span>
                <span class="reg-horizon__spectrum-lbl">custom density · {totals.customRegs} of {totals.regs} registries diverge from vanilla</span>
            </ProgressBar>
        </section>

        <!-- ===== Telescope body — index rail + scope pane ===== -->
        <div class="reg-body">
            <!-- ===== LEFT — index rail ===== -->
            <aside class="reg-rail">
                <header class="reg-rail__hd">
                    <span class="reg-rail__hd-mark">●</span>
                    <span class="reg-rail__hd-lbl">Customized</span>
                    <span class="reg-rail__hd-count">{customized.length}</span>
                </header>
                {#if customized.length === 0}
                    <div class="reg-rail__hint">
                        This connection runs only stock Mojang registries — every entry below is
                        baseline content. Custom registrations made via Minestom will surface here.
                    </div>
                {:else}
                    <div class="reg-rail__list">
                        {#each customized as a (a.id)}
                            {@render registryRow(a)}
                        {/each}
                    </div>
                {/if}

                <header class="reg-rail__hd reg-rail__hd--ambient">
                    <button
                        type="button"
                        class="reg-rail__hd-toggle"
                        onclick={() => collapseAmbient = !collapseAmbient}
                        aria-expanded={!collapseAmbient}
                        aria-controls="reg-rail-ambient"
                    >
                        <span class="reg-rail__hd-mark reg-rail__hd-mark--dim">◌</span>
                        <span class="reg-rail__hd-lbl">Ambient</span>
                        <span class="reg-rail__hd-count">{ambient.length}</span>
                        <span class="reg-rail__hd-chev" aria-hidden="true">{collapseAmbient ? '▸' : '▾'}</span>
                    </button>
                </header>
                {#if !collapseAmbient}
                    <div class="reg-rail__list reg-rail__list--ambient" id="reg-rail-ambient">
                        {#each ambient as a (a.id)}
                            {@render registryRow(a)}
                        {/each}
                    </div>
                {:else}
                    <div class="reg-rail__hint reg-rail__hint--ambient">
                        {ambient.length} vanilla-only registries hidden — click <em>Ambient</em> to inspect them.
                    </div>
                {/if}
            </aside>

            <!-- ===== RIGHT — scope pane ===== -->
            <section class="reg-scope">
                {#if !selected}
                    <div class="empty">Select a registry on the left.</div>
                {:else}
                    {@const a = selected}
                    <header class="reg-scope__hd">
                        <div class="reg-scope__title">
                            <div class="reg-scope__id">
                                {#if namespaceOf(a.id) && namespaceOf(a.id) !== 'minecraft'}
                                    <span class="reg-ns reg-ns--custom">{namespaceOf(a.id)}</span><span class="reg-scope__colon">:</span>
                                {:else}
                                    <span class="reg-ns reg-ns--vanilla">{namespaceOf(a.id) || 'minecraft'}</span><span class="reg-scope__colon">:</span>
                                {/if}
                                <span class="reg-scope__path">{pathOf(a.id)}</span>
                            </div>
                            <div class="reg-scope__sub">
                                <span class="reg-scope__chip">registry</span>
                                <Pill kind="on" dot>client registry</Pill>
                                <span class="reg-scope__rule"></span>
                                <span class="reg-scope__count">
                                    <span class="num reg-scope__count-custom">{a.customCount}</span>
                                    <span class="reg-scope__count-slash">/</span>
                                    <span class="num">{a.total}</span>
                                    <span class="reg-scope__count-lbl">custom of total</span>
                                </span>
                            </div>
                        </div>
                        <div class="reg-scope__actions">
                            <div class="btn-row">
                                <button
                                    type="button"
                                    class={customOnly ? 'primary sm' : 'ghost sm'}
                                    onclick={() => customOnly = true}
                                >Custom</button>
                                <button
                                    type="button"
                                    class={!customOnly ? 'primary sm' : 'ghost sm'}
                                    onclick={() => customOnly = false}
                                >All</button>
                            </div>
                            <input
                                class="reg-scope__search"
                                placeholder="filter by id…"
                                value={query}
                                oninput={e => query = (e.target as HTMLInputElement).value}
                                spellcheck="false"
                                autocomplete="off"
                            />
                        </div>
                    </header>

                    <div class="reg-scope__density" aria-hidden="true">
                        <div class="reg-scope__density-fill" style:--pct={(a.customRatio * 100).toFixed(3) + '%'}></div>
                    </div>

                    {#if visibleCount === 0}
                        <div class="reg-scope__empty">
                            {#if customOnly && a.customCount === 0}
                                <strong>No custom additions in this registry.</strong>
                                <span class="dim">Every entry here is baseline Mojang content — switch to <em>All</em> to inspect vanilla entries.</span>
                            {:else if query}
                                <strong>No entries match “{query}”.</strong>
                                <span class="dim">Clear the filter or switch to <em>All</em> to widen the search.</span>
                            {:else}
                                <strong>No entries.</strong>
                            {/if}
                        </div>
                    {:else}
                        <ol class="reg-entries">
                            {#each filteredEntries as e, i (e.id)}
                                {@render entryRow(e, i)}
                            {/each}
                        </ol>
                        {#if hiddenByFilter > 0}
                            <footer class="reg-scope__foot">
                                <span class="reg-scope__foot-num">{hiddenByFilter}</span>
                                <span class="reg-scope__foot-lbl">
                                    {customOnly ? 'vanilla' : 'filtered'} {hiddenByFilter === 1 ? 'entry' : 'entries'} hidden
                                </span>
                                {#if customOnly}
                                    <button type="button" class="ghost sm" onclick={() => customOnly = false}>Show all</button>
                                {:else if query}
                                    <button type="button" class="ghost sm" onclick={() => query = ''}>Clear filter</button>
                                {/if}
                            </footer>
                        {/if}
                    {/if}
                {/if}
            </section>
        </div>
    {/if}
</div>

<style>
    @layer pages {
        :global {
    /* ---- Registries tab ("Registry Telescope") --------------------- *
     * Vanilla entries are ambient noise; custom entries are signal. The single rule that
     * does the heavy lifting is namespace coloring (.reg-ns--custom vs .reg-ns--vanilla):
     * minecraft:* renders dim, anything else renders accent everywhere on the page. */
    .reg-shell { display: grid; gap: var(--pad-3); }

    .reg-horizon {
        display: grid;
        grid-template-columns: auto auto auto auto 1fr;
        gap: var(--pad-5);
        align-items: stretch;
        padding: var(--pad-3) var(--pad-4);
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
        .reg-horizon__cell {
            display: grid;
            gap: 4px;
            align-content: center;
            padding-right: var(--pad-5);
            border-right: 1px solid var(--line);
            position: relative;
            &:nth-last-of-type(2) { border-right: 0; padding-right: 0; }
            .reg-horizon__cell--big .reg-horizon__num    { font-size: var(--t-3xl); color: var(--ink); }
            .reg-horizon__cell--accent .reg-horizon__num { color: var(--acc); }
        }
        .reg-horizon__num {
            font-size: var(--t-2xl);
            line-height: 1;
            color: var(--ink);
            font-variant-numeric: tabular-nums;
            display: inline-flex;
            align-items: baseline;
            gap: 8px;
            .reg-horizon__num--dim { color: var(--ink-3); }
        }
        .reg-horizon__dot {
            width: 8px;
            height: 8px;
            background: var(--acc);
            box-shadow: 0 0 12px color-mix(in oklab, var(--acc) 60%, transparent);
            align-self: center;
            animation: reg-pulse 2.4s infinite ease-in-out;
        }
        .reg-horizon__lbl {
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
        }
        .reg-horizon__spectrum {
            align-content: center;
            min-width: 0;
        }
        .reg-horizon__spectrum-num {
            color: var(--acc);
            font-size: var(--t-md);
            line-height: 1;
            font-variant-numeric: tabular-nums;
            text-transform: none;
        }
    }

    @keyframes reg-pulse {
        0%, 100% { opacity: 1; }
        50% { opacity: 0.45; }
    }

    .reg-body {
        display: grid;
        grid-template-columns: minmax(280px, 340px) 1fr;
        gap: var(--pad-3);
        align-items: start;
        min-height: 480px;
    }
    @media (max-width: 1100px) { .reg-body { grid-template-columns: 1fr; } }

    .reg-rail {
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
        display: flex;
        flex-direction: column;
        min-width: 0;
        .reg-rail__hd {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: var(--pad-2) var(--pad-3);
            border-bottom: 1px solid var(--line);
            background: var(--bg-2);
            font-size: var(--t-xs);
            text-transform: uppercase;
            color: var(--ink-3);
            .reg-rail__hd--ambient {
                margin-top: var(--pad-2);
                background: transparent;
                border-top: 1px dashed var(--line);
                border-bottom: 0;
            }
        }
        .reg-rail__hd-toggle {
            all: unset;
            cursor: pointer;
            display: flex;
            align-items: center;
            gap: 8px;
            width: 100%;
            padding: var(--pad-2) var(--pad-3);
            margin: calc(-1 * var(--pad-2)) calc(-1 * var(--pad-3));
            &:hover { color: var(--ink); }
        }
        .reg-rail__hd-mark {
            color: var(--acc);
            font-size: var(--t-xs);
            line-height: 1;
            .reg-rail__hd-mark--dim { color: var(--ink-4); }
        }
        .reg-rail__hd-lbl { flex: 0 0 auto; }
        .reg-rail__hd-count { margin-left: auto; color: var(--ink-4); font-variant-numeric: tabular-nums; }
        .reg-rail__hd-chev { color: var(--ink-4); font-size: var(--t-xs); }

        .reg-rail__list {
            display: flex;
            flex-direction: column;
            padding: 4px 0;
            overflow: auto;
            max-height: 360px;
            .reg-rail__list--ambient { max-height: 280px; }
        }

        .reg-rail__hint {
            padding: var(--pad-3) var(--pad-3) var(--pad-4);
            color: var(--ink-4);
            font-size: var(--t-xs);
            line-height: 1;
            em { color: var(--ink-2); font-style: normal; }
            .reg-rail__hint--ambient { padding: var(--pad-2) var(--pad-3) var(--pad-3); font-style: italic; }
        }

        .reg-rail__row {
            all: unset;
            cursor: pointer;
            display: grid;
            grid-template-columns: 14px 1fr auto auto;
            gap: 8px;
            align-items: center;
            padding: 6px 10px 6px 8px;
            font-size: var(--t-sm);
            color: var(--ink-2);
            position: relative;
            transition: background var(--motion), color var(--motion);
            &:hover { background: var(--bg-2); color: var(--ink); }
            &:focus-visible { outline: 1px solid var(--acc); outline-offset: -1px; }

            &.on {
                background: var(--acc-soft);
                color: var(--ink);
                &::before {
                    content: "";
                    position: absolute;
                    left: 0;
                    top: 0;
                    bottom: 0;
                    width: 2px;
                    background: var(--acc);
                }
            }
            &.dim {
                color: var(--ink-3);
                .reg-rail__mark { color: var(--ink-4); }
                &:hover { color: var(--ink-2); }
            }
        }

        .reg-rail__mark {
            color: var(--acc);
            font-size: var(--t-xs);
            line-height: 1;
            text-align: center;
        }
        .reg-rail__name {
            min-width: 0;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .reg-rail__colon  { color: var(--ink-4); margin: 0 1px; }
        .reg-rail__path   { color: inherit; }
        .reg-rail__counts {
            display: inline-flex;
            align-items: baseline;
            font-variant-numeric: tabular-nums;
            font-size: var(--t-xs);
            color: var(--ink-4);
        }
        .reg-rail__custom { color: var(--acc); }
        .reg-rail__slash  { padding: 0 2px; color: var(--ink-4); }
        .reg-rail__total  { color: var(--ink-3); }
    }

    /* Micro density meter — narrow bar showing custom proportion. */
    .reg-meter {
        display: inline-block;
        width: 28px;
        height: 4px;
        background: var(--bg-3);
        border: 1px solid var(--line);
        position: relative;
        align-self: center;
        .reg-meter__fill {
            position: absolute;
            inset: 0 auto 0 0;
            width: var(--pct, 0%);
            background: var(--acc);
            transition: width 180ms ease-out;
        }
    }

    .reg-scope {
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
        display: flex;
        flex-direction: column;
        min-width: 0;
        .reg-scope__hd {
            display: grid;
            grid-template-columns: 1fr auto;
            gap: var(--pad-3);
            padding: var(--pad-3) var(--pad-4);
            border-bottom: 1px solid var(--line);
            background: var(--bg-1);
        }
        .reg-scope__title { min-width: 0; }
        .reg-scope__id {
            font-size: var(--t-xl);
            line-height: 1;
            color: var(--ink);
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .reg-scope__colon { color: var(--ink-4); margin: 0 2px; }
        .reg-scope__path  { color: var(--ink); }

        .reg-scope__sub {
            display: flex;
            align-items: center;
            gap: var(--pad-3);
            margin-top: 6px;
            font-size: var(--t-xs);
            color: var(--ink-4);
            flex-wrap: wrap;
        }
        .reg-scope__chip {
            padding: 1px 8px;
            background: var(--bg-2);
            border: 1px solid var(--line);
            color: var(--ink-2);
            text-transform: uppercase;
            font-size: var(--t-xs);
        }
        .reg-scope__rule {
            flex: 0 0 auto;
            width: 1px;
            height: 12px;
            background: var(--line);
        }
        .reg-scope__count {
            display: inline-flex;
            align-items: baseline;
            gap: 4px;
            text-transform: uppercase;
            color: var(--ink-4);
            .num { color: var(--ink-2); font-size: var(--t-md); }
        }
        .reg-scope__count-custom { color: var(--acc); font-size: var(--t-md); }
        .reg-scope__count-slash { color: var(--ink-4); }
        .reg-scope__count-lbl   { margin-left: 6px; }

        .reg-scope__actions { display: flex; gap: var(--pad-2); align-items: center; }
        .reg-scope__search {
            width: 220px;
            background: var(--bg-2);
            border: 1px solid var(--line);
            color: var(--ink);
            font-size: var(--t-sm);
            line-height: 1;
            padding: 6px 8px;
            &:focus-visible { outline: 1px solid var(--acc); outline-offset: -1px; }
        }

        /* Inline density indicator — full-width bar between header and list. */
        .reg-scope__density {
            height: 2px;
            background: var(--bg-2);
            position: relative;
            overflow: hidden;
        }
        .reg-scope__density-fill {
            position: absolute;
            inset: 0 auto 0 0;
            width: var(--pct, 0%);
            background: linear-gradient(90deg,
                color-mix(in oklab, var(--acc) 30%, transparent),
                var(--acc));
            transition: width 320ms cubic-bezier(.2, .6, .2, 1);
        }

        .reg-scope__empty {
            padding: var(--pad-6) var(--pad-4);
            display: grid;
            gap: 8px;
            text-align: center;
            color: var(--ink-3);
            font-size: var(--t-sm);
            strong { color: var(--ink); font-weight: 400; }
            em { color: var(--acc); font-style: normal; }
        }

        .reg-scope__foot {
            display: flex;
            align-items: center;
            gap: var(--pad-3);
            padding: var(--pad-2) var(--pad-4);
            border-top: 1px dashed var(--line);
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
        }
        .reg-scope__foot-num {
            color: var(--ink-2);
            font-size: var(--t-md);
            line-height: 1;
            font-variant-numeric: tabular-nums;
        }
        .reg-scope__foot-lbl { flex: 1; }
    }

    .reg-entries {
        list-style: none;
        margin: 0;
        padding: 0;
        counter-reset: regrow;
        max-height: 620px;
        overflow: auto;
    }

    .reg-entry {
        display: grid;
        grid-template-columns: 3px 42px 1fr auto;
        gap: var(--pad-3);
        align-items: center;
        padding: 8px var(--pad-4) 8px 0;
        border-bottom: 1px solid color-mix(in oklab, var(--line) 50%, transparent);
        font-size: var(--t-sm);
        color: var(--ink-2);
        position: relative;
        .reg-entry__rail { grid-column: 1; align-self: stretch; background: transparent; }
        .reg-entry__idx {
            font-size: var(--t-xs);
            color: var(--ink-4);
            font-variant-numeric: tabular-nums;
            text-align: right;
        }
        .reg-entry__id {
            min-width: 0;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .reg-entry__colon { color: var(--ink-4); margin: 0 2px; }
        .reg-entry__path  { color: var(--ink); }
        .reg-entry__badge {
            font-size: var(--t-xs);
            text-transform: uppercase;
            color: var(--ink-4);
            min-width: 56px;
            text-align: right;
        }

        .reg-entry--custom {
            background: color-mix(in oklab, var(--acc) 5%, transparent);
            .reg-entry__rail { background: var(--acc); }
            .reg-entry__idx  { color: var(--acc); }
            .reg-entry__path { color: var(--ink); }
            .reg-entry__badge {
                color: var(--acc);
                border: 1px solid var(--acc-line);
                padding: 1px 6px;
                background: var(--acc-soft);
                min-width: 56px;
            }
        }

        .reg-entry--vanilla {
            color: var(--ink-3);
            .reg-entry__path  { color: var(--ink-2); }
            .reg-entry__badge { font-style: italic; }
        }
    }

    /* Namespace coloring — vanilla dim, custom glows. The signature rule of the page. */
    .reg-ns { font-size: inherit; }
    .reg-ns--vanilla { color: var(--ink-4); }
    .reg-ns--custom {
        color: var(--acc);
        text-shadow: 0 0 8px color-mix(in oklab, var(--acc) 35%, transparent);
    }

    .reg-error {
        padding: var(--pad-3);
        display: grid;
        gap: var(--pad-2);
        code { background: var(--bg-2); padding: 1px 6px; color: var(--ink); }
        pre.code {
            background: var(--bg-2);
            padding: var(--pad-2);
            border: 1px solid var(--line);
            color: var(--danger);
            font-size: var(--t-sm);
            overflow: auto;
        }
    }

            @media (prefers-reduced-motion: reduce) {
                .reg-horizon__dot { animation: none; }
            }
        }
    }
</style>
