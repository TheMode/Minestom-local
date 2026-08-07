<script lang="ts">
    import { api } from '../lib/api.ts';
    import { players as playersStore } from '../state/players.svelte.ts';
    import { shortUuid } from '../lib/util.ts';
    import {
        type Throttle, ZERO, BW_MAX, bwToFrac, fracToBw, fmtBw,
        isActive, throttleEquals, scopePath,
    } from '../lib/throttle.ts';
    import ViewHead from '../components/ui/ViewHead.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import { toast } from '../state/toasts.svelte.ts';

    type Mode = 'global' | 'player';

    let mode = $state<Mode>('global');
    let selectedUuid = $state<string | null>(null);
    let perPlayer = $state<Record<string, Throttle>>({});
    /// Drafts always hold a Throttle (zero-initialised); pressing Apply pushes them to the
    /// backend, Disengage DELETEs them. The live state below is nullable: `null` == bypass.
    let globalDraft = $state<Throttle>({ ...ZERO });
    let playerDraft = $state<Throttle>({ ...ZERO });
    let liveGlobal = $state<Throttle | null>(null);
    let playerFilter = $state('');
    let initialised = $state(false);

    playersStore.boot();
    const onlinePlayers = $derived(playersStore.list);

    const active = $derived(mode === 'global' ? globalDraft : playerDraft);
    const livePlayer = $derived(selectedUuid ? (perPlayer[selectedUuid] ?? null) : null);

    const dirty = $derived.by(() => {
        if (mode === 'global') return !throttleEquals(globalDraft, liveGlobal ?? ZERO);
        if (!selectedUuid) return false;
        return !throttleEquals(playerDraft, livePlayer ?? ZERO);
    });

    async function refresh() {
        try {
            const r = await api<{ global: Throttle | null; players: Record<string, Throttle> }>('/throttle');
            liveGlobal = r.global ? normalize(r.global) : null;
            perPlayer = Object.fromEntries(Object.entries(r.players || {}).map(([k, v]) => [k, normalize(v)]));
            if (!initialised) {
                globalDraft = { ...(liveGlobal ?? ZERO) };
                initialised = true;
            }
        } catch (e) {
            toast('Failed to load throttles: ' + (e as Error).message, 'error');
        }
    }
    $effect(() => { refresh(); });

    function normalize(t: any): Throttle {
        return {
            latencyMs: Number(t?.latencyMs ?? 0),
            jitterMs: Number(t?.jitterMs ?? 0),
            bandwidthBytesPerSec: Number(t?.bandwidthBytesPerSec ?? 0),
            direction: t?.direction ?? null,
        };
    }

    function selectPlayer(uuid: string | null) {
        // Click-again deselects — same chip toggles off.
        const next = uuid && uuid === selectedUuid ? null : uuid;
        selectedUuid = next;
        playerDraft = next ? { ...(perPlayer[next] ?? ZERO) } : { ...ZERO };
    }

    async function apply() {
        try {
            if (mode === 'global') {
                const next = normalize(await api<Throttle>('/throttle/global', { method: 'PUT', body: globalDraft }));
                liveGlobal = isActive(next) ? next : null;
                globalDraft = { ...next };
                toast(isActive(next) ? 'Global throttle engaged' : 'Global throttle stored (no-op)');
            } else {
                if (!selectedUuid) { toast('Select a player first', 'error'); return; }
                const next = normalize(await api<Throttle>('/throttle/players/' + selectedUuid, { method: 'PUT', body: playerDraft }));
                if (isActive(next)) {
                    perPlayer = { ...perPlayer, [selectedUuid]: next };
                } else {
                    const copy = { ...perPlayer };
                    delete copy[selectedUuid];
                    perPlayer = copy;
                }
                playerDraft = { ...next };
                toast(isActive(next) ? `Throttle engaged for ${labelFor(selectedUuid)}` : `Throttle stored for ${labelFor(selectedUuid)} (no-op)`);
            }
        } catch (e) {
            toast('Failed to apply: ' + (e as Error).message, 'error');
        }
    }

    async function disengage() {
        try {
            if (mode === 'global') {
                await api('/throttle/global', { method: 'DELETE' });
                liveGlobal = null;
                globalDraft = { ...ZERO };
                toast('Global throttle disengaged');
            } else {
                if (!selectedUuid) return;
                await api('/throttle/players/' + selectedUuid, { method: 'DELETE' });
                const copy = { ...perPlayer };
                delete copy[selectedUuid];
                perPlayer = copy;
                playerDraft = { ...ZERO };
                toast('Throttle cleared for ' + labelFor(selectedUuid));
            }
        } catch (e) {
            toast((e as Error).message, 'error');
        }
    }

    function setDir(d: Throttle['direction']) {
        if (mode === 'global') globalDraft = { ...globalDraft, direction: d };
        else playerDraft = { ...playerDraft, direction: d };
    }
    function patch(patchObj: Partial<Throttle>) {
        if (mode === 'global') globalDraft = { ...globalDraft, ...patchObj };
        else playerDraft = { ...playerDraft, ...patchObj };
    }
    function reset() {
        if (mode === 'global') globalDraft = { ...ZERO };
        else playerDraft = { ...ZERO };
    }

    function labelFor(uuid: string) {
        const p = onlinePlayers.find(p => p.uuid === uuid);
        return p?.username || shortUuid(uuid);
    }

    const filteredPlayers = $derived.by(() => {
        const q = playerFilter.trim().toLowerCase();
        if (!q) return onlinePlayers;
        return onlinePlayers.filter(p =>
            (p.username || '').toLowerCase().includes(q) ||
            (p.uuid || '').toLowerCase().includes(q));
    });

    const targetedCount = $derived(Object.values(perPlayer).filter(isActive).length);
    const liveActiveGlobal = $derived(isActive(liveGlobal));
    const canDisengage = $derived(mode === 'global' ? liveActiveGlobal : !!(selectedUuid && livePlayer));
    const draftActive = $derived(isActive(active));
    const statusLabel = $derived.by(() => {
        if (liveActiveGlobal && targetedCount) return `GLOBAL + ${targetedCount} TARGETED`;
        if (liveActiveGlobal) return 'GLOBAL ENGAGED';
        if (targetedCount) return `${targetedCount} TARGETED`;
        return 'IDLE';
    });
    const engaged = $derived(liveActiveGlobal || targetedCount > 0);

    function summarize(t: Throttle | null) {
        if (!t) return 'pass-through';
        const parts: string[] = [];
        if (t.latencyMs) parts.push(t.latencyMs + 'ms');
        if (t.jitterMs) parts.push('±' + t.jitterMs + 'ms');
        if (t.bandwidthBytesPerSec) parts.push(fmtBw(t.bandwidthBytesPerSec));
        if (t.direction) parts.push(t.direction === 'CLIENTBOUND' ? 'S→C' : 'C→S');
        return parts.length ? parts.join(' · ') : 'no-op';
    }

    const TICK10 = Array.from({ length: 11 }, (_, i) => i);
    const TICK20 = Array.from({ length: 21 }, (_, i) => i);

    // Bandwidth uses a variable display unit (B/s, KB/s, MB/s) that adapts to the magnitude.
    type BwUnit = 'B/s' | 'KB/s' | 'MB/s';
    function pickBwUnit(b: number): BwUnit {
        if (b >= 1024 * 1024) return 'MB/s';
        if (b >= 1024) return 'KB/s';
        return 'B/s';
    }
    const bwUnitDiv = (u: BwUnit) => u === 'MB/s' ? 1024 * 1024 : u === 'KB/s' ? 1024 : 1;
    const bwUnitStep = (u: BwUnit) => u === 'MB/s' ? '0.01' : u === 'KB/s' ? '0.1' : '1';
    const bwValueIn = (b: number, u: BwUnit) => u === 'B/s' ? b : +(b / bwUnitDiv(u)).toFixed(u === 'MB/s' ? 2 : 1);

    let bwUnit = $state<BwUnit>('B/s');
    let bwInputFocused = $state(false);
    // Re-snap the unit when the bandwidth changes from anywhere else (slider, mode swap, refresh).
    // While the input is focused, keep the unit frozen so the user's keystrokes don't get
    // interpreted in a different unit than they're typing into.
    $effect(() => {
        if (!bwInputFocused) bwUnit = pickBwUnit(active.bandwidthBytesPerSec);
    });

    function readInt(e: Event, min: number, max: number): number {
        const raw = (e.currentTarget as HTMLInputElement).value;
        if (raw === '') return 0;
        const n = Number(raw);
        if (!Number.isFinite(n)) return 0;
        return Math.max(min, Math.min(max, Math.round(n)));
    }
</script>

{#snippet throttleCrumb()}Throttle{/snippet}
{#snippet title()}Traffic <em>shaper</em>{/snippet}
{#snippet headActions()}
    <button class="ghost" onclick={reset} disabled={!draftActive}>Zero</button>
    <button class="ghost" onclick={disengage} disabled={!canDisengage}>Disengage</button>
    <button class="primary" onclick={apply} disabled={!dirty}>▶ Apply</button>
{/snippet}
{#snippet tickMarks(ticks)}
    <div class="thr-fader__ticks">
        {#each ticks as t (t)}<i class:maj={t % 5 === 0}></i>{/each}
    </div>
{/snippet}

{#snippet faderScale(labels)}
    <div class="thr-fader__scale">
        {#each labels as label (label)}<span>{label}</span>{/each}
    </div>
{/snippet}

<ViewHead crumbs={[throttleCrumb]} {title} actions={headActions} />

<div class="thr-deck">
    <section class="thr-status" class:engaged class:idle={!engaged}>
        <div class="thr-status__lights">
            {#each Array(6) as _, i (i)}<i class:on={engaged} style={`--d:${i * 80}ms`}></i>{/each}
        </div>
        <div class="thr-status__head">
            <span class="thr-status__label">SHAPER</span>
            <span class="thr-status__sep">·</span>
            <span class="thr-status__state">{statusLabel}</span>
        </div>
        <div class="thr-status__body">
            <span class="thr-status__sum">
                Global: <em>{summarize(liveGlobal)}</em>
            </span>
            <span class="thr-status__sum">
                Targeted: <em>{targetedCount} {targetedCount === 1 ? 'player' : 'players'}</em>
            </span>
        </div>
        <svg class="thr-scope" viewBox="0 0 200 32" preserveAspectRatio="none" aria-hidden="true">
            <path d={scopePath(draftActive ? active : null)} fill="none" stroke="currentColor" stroke-width="1" vector-effect="non-scaling-stroke" />
        </svg>
    </section>

    <section class="thr-target">
        <div class="seg-control seg-control--cards thr-target__modes" role="tablist">
            <button
                type="button"
                role="tab"
                aria-selected={mode === 'global'}
                class="thr-mode seg-control__item"
                class:on={mode === 'global'}
                onclick={() => mode = 'global'}>
                <span class="thr-mode__bar"></span>
                <span class="thr-mode__lbl">Broadcast</span>
                <span class="thr-mode__hint">applies to every connection</span>
            </button>
            <button
                type="button"
                role="tab"
                aria-selected={mode === 'player'}
                class="thr-mode seg-control__item"
                class:on={mode === 'player'}
                onclick={() => mode = 'player'}>
                <span class="thr-mode__bar"></span>
                <span class="thr-mode__lbl">Targeted</span>
                <span class="thr-mode__hint">single player override</span>
            </button>
        </div>

        {#if mode === 'player'}
            <div class="thr-roster">
                <div class="thr-roster__bar">
                    <input
                        class="thr-roster__filter"
                        type="text"
                        placeholder='filter players — username or uuid…'
                        bind:value={playerFilter}
                        spellcheck="false"
                        autocomplete="off"
                    />
                    {#if selectedUuid}
                        <span class="thr-roster__sel">target → <em>{labelFor(selectedUuid)}</em></span>
                    {/if}
                </div>
                <div class="thr-roster__grid">
                    {#each filteredPlayers as p (p.uuid)}
                        {@const isSel = selectedUuid === p.uuid}
                        {@const live = perPlayer[p.uuid]}
                        <button
                            type="button"
                            class="thr-chip"
                            class:on={isSel}
                            class:lit={isActive(live)}
                            onclick={() => selectPlayer(p.uuid)}
                            title={p.uuid}
                        >
                            <span class="thr-chip__dot"></span>
                            <span class="thr-chip__name">{p.username || shortUuid(p.uuid)}</span>
                            {#if isActive(live)}<span class="thr-chip__tag">{summarize(live)}</span>{/if}
                        </button>
                    {/each}
                    {#if filteredPlayers.length === 0}
                        <div class="empty empty--compact grid-full">No matching connections.</div>
                    {/if}
                </div>
            </div>
        {/if}
    </section>

    <section class="thr-strip" class:off={!draftActive}>
        <div class="thr-strip__cell wide">
            <span class="thr-strip__k">Direction</span>
            <div class="thr-dir">
                <button type="button" class="thr-dir__opt" class:on={active.direction === null} onclick={() => setDir(null)}>
                    <i class="cb"></i><i class="sb"></i><span>Both</span>
                </button>
                <button type="button" class="thr-dir__opt" class:on={active.direction === 'CLIENTBOUND'} onclick={() => setDir('CLIENTBOUND')}>
                    <i class="cb"></i><span>S → C</span>
                </button>
                <button type="button" class="thr-dir__opt" class:on={active.direction === 'SERVERBOUND'} onclick={() => setDir('SERVERBOUND')}>
                    <i class="sb"></i><span>C → S</span>
                </button>
            </div>
        </div>
        <div class="thr-strip__cell">
            <span class="thr-strip__k">Draft</span>
            <span class="thr-strip__live" class:dim={!draftActive}>{summarize(active)}</span>
        </div>
        {#if mode === 'player' && selectedUuid && livePlayer}
            <div class="thr-strip__cell">
                <span class="thr-strip__k">Live (target)</span>
                <span class="thr-strip__live">{summarize(livePlayer)}</span>
            </div>
        {/if}
    </section>

    <section class="thr-rack">
        <article class="thr-mod" class:lit={active.latencyMs > 0}>
            <header>
                <span class="thr-mod__idx">01</span>
                <span class="thr-mod__lbl">Latency</span>
                <span class="thr-mod__unit">ms</span>
            </header>
            <div class="thr-mod__readout">
                <input
                    type="number" min="0" max="60000" step="5"
                    class="thr-mod__num"
                    value={active.latencyMs}
                    oninput={e => patch({ latencyMs: readInt(e, 0, 60_000) })}
                />
                <small>ms · base delay</small>
            </div>
            <div class="thr-fader">
                {@render tickMarks(TICK20)}
                <input
                    type="range" min="0" max="2000" step="5"
                    value={active.latencyMs}
                    style:--pct={(Math.min(active.latencyMs, 2000) / 2000 * 100) + '%'}
                    oninput={e => patch({ latencyMs: +(e.currentTarget as HTMLInputElement).value })}
                />
                {@render faderScale(['0', '500', '1k', '1.5k', '2k'])}
            </div>
            <footer>fixed ms added per packet — both ends feel it</footer>
        </article>

        <article class="thr-mod" class:lit={active.jitterMs > 0}>
            <header>
                <span class="thr-mod__idx">02</span>
                <span class="thr-mod__lbl">Jitter</span>
                <span class="thr-mod__unit">± ms</span>
            </header>
            <div class="thr-mod__readout">
                <span class="thr-mod__prefix">±</span>
                <input
                    type="number" min="0" max="10000" step="5"
                    class="thr-mod__num"
                    value={active.jitterMs}
                    oninput={e => patch({ jitterMs: readInt(e, 0, 10_000) })}
                />
                <small>ms · random variance</small>
            </div>
            <div class="thr-fader">
                {@render tickMarks(TICK10)}
                <input
                    type="range" min="0" max="500" step="5"
                    value={active.jitterMs}
                    style:--pct={(Math.min(active.jitterMs, 500) / 500 * 100) + '%'}
                    oninput={e => patch({ jitterMs: +(e.currentTarget as HTMLInputElement).value })}
                />
                {@render faderScale(['0', '125', '250', '375', '500'])}
            </div>
            <footer>uniform [0…N) extra latency, picked per packet</footer>
        </article>

        <article class="thr-mod" class:lit={active.bandwidthBytesPerSec > 0}>
            <header>
                <span class="thr-mod__idx">03</span>
                <span class="thr-mod__lbl">Bandwidth</span>
                <span class="thr-mod__unit">cap</span>
            </header>
            <div class="thr-mod__readout">
                <input
                    type="number" min="0" max={BW_MAX / bwUnitDiv(bwUnit)} step={bwUnitStep(bwUnit)}
                    class="thr-mod__num"
                    value={bwValueIn(active.bandwidthBytesPerSec, bwUnit)}
                    onfocus={() => bwInputFocused = true}
                    onblur={() => bwInputFocused = false}
                    oninput={e => {
                        const raw = parseFloat((e.currentTarget as HTMLInputElement).value);
                        if (!Number.isFinite(raw) || raw < 0) return;
                        patch({ bandwidthBytesPerSec: Math.min(BW_MAX, Math.round(raw * bwUnitDiv(bwUnit))) });
                    }}
                />
                <span class="thr-mod__unit-tag">{bwUnit}</span>
                {#if !active.bandwidthBytesPerSec}<small>· unlimited</small>{/if}
            </div>
            <div class="thr-fader">
                {@render tickMarks(TICK20)}
                <input
                    type="range" min="0" max="1000" step="1"
                    value={Math.round(bwToFrac(active.bandwidthBytesPerSec) * 1000)}
                    style:--pct={(bwToFrac(active.bandwidthBytesPerSec) * 100) + '%'}
                    oninput={e => patch({ bandwidthBytesPerSec: fracToBw(+(e.currentTarget as HTMLInputElement).value / 1000) })}
                />
                {@render faderScale(['0', '1K', '32K', '1M', '16M'])}
            </div>
            <footer>per-direction outgoing cap · log-scale</footer>
        </article>

    </section>

    {#if Object.keys(perPlayer).length > 0}
        <Panel title="Active overrides" flush>
            {#snippet meta()}<span>{targetedCount}</span> engaged{/snippet}
            <table class="list thr-overrides">
                <thead><tr><th>Player</th><th>UUID</th><th>Throttle</th><th></th></tr></thead>
                <tbody>
                    {#each Object.entries(perPlayer) as [uuid, t] (uuid)}
                        <tr>
                            <td class="name">{labelFor(uuid)}</td>
                            <td class="dim mono small">{shortUuid(uuid)}</td>
                            <td class="dim small">{summarize(t)}</td>
                            <td class="right">
                                <div class="player-actions">
                                    <button class="sm" onclick={() => { mode = 'player'; selectPlayer(uuid); }}>Tune</button>
                                    <button class="sm ghost" onclick={async () => {
                                        await api('/throttle/players/' + uuid, { method: 'DELETE' });
                                        const c = { ...perPlayer }; delete c[uuid]; perPlayer = c;
                                        if (selectedUuid === uuid) playerDraft = { ...ZERO };
                                        toast('Cleared ' + labelFor(uuid));
                                    }}>Clear</button>
                                </div>
                            </td>
                        </tr>
                    {/each}
                </tbody>
            </table>
        </Panel>
    {/if}
</div>

<style>
    @layer pages {
        :global {
    /* ---- Throttle page --------------------------------------------- */
    .thr-deck {
        display: flex;
        flex-direction: column;
        gap: var(--pad-4);
    }
    .thr-status {
        position: relative;
        display: grid;
        grid-template-columns: auto 1fr auto;
        grid-template-rows: auto auto;
        column-gap: var(--pad-5);
        row-gap: 6px;
        padding: var(--pad-4) var(--pad-5);
        background:
            repeating-linear-gradient(0deg, transparent 0, transparent 2px, color-mix(in oklab, var(--ink-4) 4%, transparent) 2px, color-mix(in oklab, var(--ink-4) 4%, transparent) 3px),
            var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
        color: var(--ink-3);
        overflow: hidden;
    }
    .thr-status.engaged { color: var(--acc); }
    .thr-status__lights {
        display: grid;
        grid-template-columns: repeat(6, 6px);
        gap: 4px;
        align-self: center;
        grid-row: 1 / span 2;
    }
    .thr-status__lights i {
        width: 6px;
        height: 6px;
        background: var(--line-2);
        box-shadow: var(--bevel-sunk);
    }
    .thr-status__lights i.on {
        background: var(--acc);
        animation: thr-blink 1.2s ease-in-out infinite;
        animation-delay: var(--d, 0ms);
        box-shadow:
            inset 0 1px 0 0 color-mix(in oklab, white 25%, transparent),
            0 0 6px var(--acc-line);
    }
    @keyframes thr-blink {
        0%, 100% { opacity: 1; }
        50% { opacity: 0.35; }
    }
    .thr-status__head {
        display: flex;
        align-items: center;
        gap: 10px;
        font-size: var(--t-xs);
        text-transform: uppercase;
        color: var(--ink-3);
    }
    .thr-status__label { color: var(--ink); }
    .thr-status.engaged .thr-status__state { color: var(--acc); }
    .thr-status__sep { color: var(--ink-4); }
    .thr-status__body {
        grid-column: 2;
        grid-row: 2;
        display: flex;
        gap: var(--pad-5);
        flex-wrap: wrap;
        font-size: var(--t-sm);
        color: var(--ink-3);
    }
    .thr-status__sum em { color: var(--ink); font-style: normal; }
    .thr-scope {
        grid-column: 3;
        grid-row: 1 / span 2;
        width: 200px;
        height: 56px;
        align-self: center;
        color: currentColor;
    }
    .thr-target {
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
        padding: var(--pad-4);
        display: flex;
        flex-direction: column;
        gap: var(--pad-3);
    }
    .thr-target__modes {
        grid-template-columns: 1fr 1fr;
    }
    .thr-mode__bar {
        position: absolute;
        left: 0;
        top: 0;
        bottom: 0;
        width: 4px;
        background: var(--line);
        pointer-events: none;
    }
    .seg-control--cards > .thr-mode.on .thr-mode__bar,
    .thr-mode.on .thr-mode__bar { background: var(--acc); }
    .thr-mode__lbl {
        font-size: var(--t-md);
        text-transform: uppercase;
        color: var(--ink);
    }
    .thr-mode__hint {
        font-size: var(--t-xs);
        color: var(--ink-4);
    }
    .thr-roster { display: flex; flex-direction: column; gap: var(--pad-2); }
    .thr-roster__bar { display: flex; align-items: center; gap: var(--pad-3); }
    .thr-roster__filter { max-width: 360px; }
    .thr-roster__sel {
        font-size: var(--t-xs);
        text-transform: uppercase;
        color: var(--ink-3);
    }
    .thr-roster__sel em { color: var(--acc); font-style: normal; }
    .thr-roster__grid {
        display: grid;
        grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
        gap: 6px;
        max-height: 220px;
        overflow-y: auto;
        padding: var(--pad-2);
        background: var(--sunk);
        border: 1px solid var(--line);
        box-shadow: var(--bevel-sunk);
    }
    .thr-chip {
        display: grid;
        grid-template-columns: auto 1fr;
        align-items: center;
        gap: var(--pad-2);
        padding: 6px 10px;
        background: var(--bg-2);
        border: 1px solid var(--line);
        font-size: var(--t-sm);
        color: var(--ink-2);
        text-transform: none;
        text-align: left;
        cursor: pointer;
        box-shadow: var(--bevel);
    }
    .thr-chip__dot { width: 6px; height: 6px; background: var(--ink-4); display: inline-block; }
    .thr-chip.lit .thr-chip__dot { background: var(--warn); animation: thr-blink 1.6s linear infinite; }
    .thr-chip.on { color: var(--ink); border-color: var(--acc-line); background: var(--bg-3); }
    .thr-chip.on .thr-chip__dot { background: var(--acc); }
    .thr-chip__name { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .thr-chip__tag {
        grid-column: 2;
        font-size: var(--t-xs);
        color: var(--warn);
        text-transform: uppercase;
    }
    .thr-strip {
        display: flex;
        flex-wrap: wrap;
        gap: var(--pad-5);
        align-items: stretch;
        padding: var(--pad-3) var(--pad-4);
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
    }
    .thr-strip.off { opacity: 0.7; }
    .thr-strip__cell { display: flex; flex-direction: column; gap: 6px; }
    .thr-strip__cell.wide { flex: 1; min-width: 240px; }
    .thr-strip__k {
        font-size: var(--t-xs);
        text-transform: uppercase;
        color: var(--ink-3);
    }
    .thr-strip__live { font-size: var(--t-sm); color: var(--warn); }
    .thr-strip__live.dim { color: var(--ink-4); }
    .thr-dir {
        display: inline-flex;
        border: 1px solid var(--line);
        background: var(--bg-2);
        box-shadow: var(--bevel);

        .thr-dir__opt {
            display: inline-flex;
            align-items: center;
            gap: 6px;
            padding: var(--pad-2) var(--pad-3);
            background: transparent;
            border: 0;
            border-left: 1px solid var(--line);
            border-radius: 0;
            box-shadow: none;
            color: var(--ink-3);
            font-size: var(--t-xs);
            text-transform: uppercase;
            cursor: pointer;
            &:first-child { border-left: 0; }
            &:hover { color: var(--ink); background: var(--bg-3); }
            &:active { box-shadow: none; }
            &.on {
                color: var(--ink);
                background: var(--bg-3);
                box-shadow: inset 0 0 0 1px var(--acc-line);
            }
            i { width: 6px; height: 6px; display: inline-block; }
            i.cb { background: var(--dir-cb); }
            i.sb { background: var(--dir-sb); }
        }
    }
    .thr-rack {
        display: grid;
        grid-template-columns: repeat(auto-fit, minmax(320px, 1fr));
        gap: var(--pad-4);
    }
    .thr-mod {
        position: relative;
        display: grid;
        grid-template-rows: auto auto auto auto;
        gap: var(--pad-3);
        padding: var(--pad-4);
        background: var(--bg-1);
        border: 1px solid var(--line);
        box-shadow: var(--bevel);
        transition: border-color var(--motion);
        overflow: hidden;
    }
    .thr-mod.lit { border-color: var(--acc-line); }
    .thr-mod.lit::before {
        content: "";
        position: absolute;
        left: 0;
        top: 0;
        bottom: 0;
        width: 3px;
        background: var(--acc);
    }
    .thr-mod > header { display: flex; align-items: baseline; gap: var(--pad-3); }
    .thr-mod__idx { font-size: var(--t-xs); color: var(--ink-4); }
    .thr-mod__lbl {
        font-size: var(--t-md);
        color: var(--ink);
        text-transform: uppercase;
    }
    .thr-mod__unit {
        margin-left: auto;
        font-size: var(--t-xs);
        color: var(--ink-4);
        text-transform: uppercase;
    }
    .thr-mod__readout {
        display: flex;
        align-items: baseline;
        gap: var(--pad-2);
        min-width: 0;
        padding: var(--pad-3) var(--pad-4);
        background: var(--sunk);
        border: 1px solid var(--line);
        box-shadow: var(--bevel-sunk);
        color: var(--ink);
        line-height: 1;
        font-variant-numeric: tabular-nums;
    }
    .thr-mod.lit .thr-mod__readout { color: var(--acc); }
    .thr-mod__prefix { font-size: var(--t-2xl); color: inherit; }
    .thr-mod__num {
        flex: 1 1 0;
        min-width: 0;
        width: 100%;
        padding: 0;
        background: transparent;
        border: 0;
        box-shadow: none;
        font-size: var(--t-2xl);
        line-height: 1;
        color: inherit;
        font-variant-numeric: tabular-nums;
        text-align: left;
        -moz-appearance: textfield;
    }
    .thr-mod__num:focus { outline: none; color: var(--ink); }
    .thr-mod__num::-webkit-outer-spin-button,
    .thr-mod__num::-webkit-inner-spin-button {
        -webkit-appearance: none;
        margin: 0;
    }
    .thr-mod__readout small {
        flex: 0 0 auto;
        font-size: var(--t-xs);
        color: var(--ink-4);
        text-transform: uppercase;
        white-space: nowrap;
    }
    .thr-mod__unit-tag {
        flex: 0 0 auto;
        font-size: var(--t-md);
        color: var(--ink-3);
        text-transform: uppercase;
        white-space: nowrap;
        font-variant-numeric: tabular-nums;
    }
    .thr-mod.lit .thr-mod__unit-tag { color: var(--acc); }
    .thr-fader {
        position: relative;
        display: flex;
        flex-direction: column;
        gap: 4px;
    }
    .thr-fader__ticks {
        display: grid;
        grid-auto-flow: column;
        grid-auto-columns: 1fr;
        align-items: end;
        height: 12px;
        padding: 0 6px;
    }
    .thr-fader__ticks i {
        width: 1px;
        height: 4px;
        background: var(--ink-4);
        opacity: 0.5;
        justify-self: center;
    }
    .thr-fader__ticks i.maj {
        height: 8px;
        opacity: 1;
        background: var(--ink-3);
    }
    .thr-fader__scale {
        display: flex;
        justify-content: space-between;
        font-size: var(--t-xs);
        color: var(--ink-4);
        padding: 0 2px;
    }
    .thr-fader input[type="range"] {
        -webkit-appearance: none;
        appearance: none;
        width: 100%;
        height: 18px;
        padding: 0;
        background: transparent;
        border: 0;
        box-shadow: none;
        cursor: pointer;
    }
    .thr-fader input[type="range"]::-webkit-slider-runnable-track {
        height: 6px;
        background:
            var(--acc) 0 0 / var(--pct, 0%) 100% no-repeat,
            var(--sunk);
        border: 1px solid var(--line);
        box-shadow: var(--bevel-sunk);
    }
    .thr-fader input[type="range"]::-moz-range-track {
        height: 6px;
        background: var(--sunk);
        border: 1px solid var(--line);
        box-shadow: var(--bevel-sunk);
    }
    .thr-fader input[type="range"]::-moz-range-progress {
        height: 6px;
        background: var(--acc);
    }
    .thr-fader input[type="range"]::-webkit-slider-thumb {
        -webkit-appearance: none;
        width: 12px;
        height: 20px;
        margin-top: -8px;
        background: var(--bg-3);
        border: 1px solid var(--line-2);
        cursor: grab;
    }
    .thr-fader input[type="range"]::-moz-range-thumb {
        width: 12px;
        height: 20px;
        background: var(--bg-3);
        border: 1px solid var(--line-2);
        border-radius: 0;
        cursor: grab;
    }
    .thr-fader input[type="range"]:focus { outline: none; }
    .thr-fader input[type="range"]:focus::-webkit-slider-thumb {
        border-color: var(--acc);
        background: var(--bg-2);
    }
    .thr-fader input[type="range"]:focus::-moz-range-thumb {
        border-color: var(--acc);
        background: var(--bg-2);
    }
    .thr-mod > footer {
        font-size: var(--t-xs);
        color: var(--ink-4);
    }
    .thr-overrides td.right { text-align: right; }
    @media (max-width: 720px) {
        .thr-status { grid-template-columns: auto 1fr; }
        .thr-scope { display: none; }
    }

    @media (prefers-reduced-motion: reduce) {
        .thr-status__lights i.on,
        .thr-chip.lit .thr-chip__dot { animation: none; }
    }
        }
    }
</style>
