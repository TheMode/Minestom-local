<script module lang="ts">
    export const FIELDS = [
        { group: 'Identity', items: [
            { key: 'username',         label: 'Name',     get: p => p.username },
            { key: 'protocolVersion',  label: 'Protocol', get: p => p.protocolVersion },
            { key: 'clientBrand',      label: 'Brand',    get: p => p.clientBrand },
            { key: 'locale',           label: 'Locale',   get: p => p.locale },
        ]},
        { group: 'World', items: [
            { key: 'dimension', label: 'Dimension', get: p => (p.dimension || '').replace('minecraft:', '') },
            { key: 'gamemode',  label: 'Gamemode',  get: p => p.gamemode },
        ]},
        { group: 'Vitals', items: [
            { key: 'health',  label: 'HP',     get: p => (p.health ?? 0).toFixed(1) + ' / ' + (p.maxHealth ?? 20) },
            { key: 'food',    label: 'Food',   get: p => (p.food ?? 0) + ' / 20' },
            { key: 'xpLevel', label: 'XP Lvl', get: p => p.xpLevel ?? 0 },
            { key: 'xpBar',   label: 'XP Bar', get: p => Math.round((p.xpBar ?? 0) * 100) + '%' },
        ]},
        { group: 'Position', items: [
            { key: 'posX',  label: 'X',     get: p => (p.posX ?? 0).toFixed(2) },
            { key: 'posY',  label: 'Y',     get: p => (p.posY ?? 0).toFixed(2) },
            { key: 'posZ',  label: 'Z',     get: p => (p.posZ ?? 0).toFixed(2) },
            { key: 'yaw',   label: 'Yaw',   get: p => (p.yaw ?? 0).toFixed(1) + '°' },
            { key: 'pitch', label: 'Pitch', get: p => (p.pitch ?? 0).toFixed(1) + '°' },
        ]},
        { group: 'Network', items: [
            { key: 'traffic.pingMs', label: 'Ping', get: p => p.traffic.pingMs + ' ms' },
        ]},
    ];

    export const TRAIL_GETTERS = {
        'health':  p => p.health,
        'food':    p => p.food,
        'xpLevel': p => p.xpLevel,
        'xpBar':   p => p.xpBar,
        'traffic.pingMs': p => p.traffic.pingMs,
        'posX':    p => p.posX,
        'posY':    p => p.posY,
        'posZ':    p => p.posZ,
        'yaw':     p => p.yaw,
        'pitch':   p => p.pitch,
    };

</script>

<script lang="ts">
    import type { PacketRow } from '../../lib/packetAgg.ts';
    import { fieldsForPacket } from '../../lib/packetTrace.ts';
    import { fmtAge, shortClass } from '../../lib/util.ts';
    import { bus } from '../../lib/api.ts';
    import { Topics } from '../../lib/topics.ts';
    import { usePacketAggregate } from '../../state/packetAggregate.svelte.ts';
    import Panel from '../ui/Panel.svelte';
    import Sparkline from '../ui/Sparkline.svelte';
    import PacketAggregatePanels from '../packets/PacketAggregatePanels.svelte';
    import PacketTrace from '../packet-trace/PacketTrace.svelte';

    let { player, paused = false } = $props();

    let flash = $state.raw<Record<string, number>>({});
    let trails = $state.raw<Record<string, number[]>>({});
    let clock = $state(Date.now());
    let playheadClass = $state('');
    let aggSort = $state('count');
    let trailsOn = $state(true);
    let feedLive = $state(true);

    $effect(() => {
        const id = player?.uuid;
        feedLive = !player?.disconnectedAt;
        if (!id) return;
        return bus.subscribe(Topics.players, msg => {
            if (msg.uuid === id && msg.event === 'disconnect') feedLive = false;
        });
    });

    const feed = usePacketAggregate(
        () => player?.uuid && player?.connectionId ? [{ uuid: player.uuid, connectionId: player.connectionId }] : [],
    {
        lanes: false,
        resetKey: () => player?.uuid ?? null,
        enabled: () => !paused && feedLive,
        history: false,
        onRow: row => flashRow(row),
    });

    function flashRow(row: PacketRow) {
        for (const f of fieldsForPacket(row.className)) flash = { ...flash, [f]: Date.now() };
        playheadClass = row.className;
    }

    const linkedFields = $derived(new Set(fieldsForPacket(playheadClass)));

    $effect(() => {
        const id = setInterval(() => {
            if (paused) return;
            const p = player;
            if (!p) return;
            const next = { ...trails };
            for (const key in TRAIL_GETTERS) {
                const v = TRAIL_GETTERS[key](p);
                if (typeof v !== 'number') continue;
                const arr = (next[key] || []).slice();
                arr.push(v);
                if (arr.length > 60) arr.shift();
                next[key] = arr;
            }
            trails = next;
        }, 500);
        return () => clearInterval(id);
    });

    $effect(() => {
        const id = setInterval(() => {
            if (!paused) clock = Date.now();
        }, 300);
        return () => clearInterval(id);
    });
</script>

<div class="ppk-shell">
    <div class="stack" style="min-width: 0;">
        <div class="ppk-aggs">
            <PacketAggregatePanels
                agg={feed.agg}
                sortBy={aggSort}
                version={feed.version}
                topMeta="this player"
                heatmapMeta={aggSort}
                max={10}
                onSortBy={v => aggSort = v}
            />
        </div>

        <Panel title="Packet trace" meta="seq tape · Space pause · ←→ step" flush headless>
            <PacketTrace
                {player}
                {paused}
                streamLive={feedLive}
                onHistory={rows => feed.ingestRows(rows, player?.uuid ?? '')}
                onPlayheadChange={row => { if (row) playheadClass = row.className; }}
                onResetFeed={() => feed.reset()}
            />
        </Panel>
    </div>

    <aside class="ppk-state-mirror ppk-side">
        <Panel title="State at playhead" meta={trailsOn ? 'trails on' : 'flashes only'} flush>
            {#snippet actions()}
                <div class="btn-row">
                    <button class={!trailsOn ? 'primary sm' : 'ghost sm'} onclick={() => trailsOn = false}>Flashes</button>
                    <button class={trailsOn ? 'primary sm' : 'ghost sm'} onclick={() => trailsOn = true}>+ Trails</button>
                </div>
            {/snippet}
            {#each FIELDS as g (g.group)}
                <div>
                    <div class="ppk-state-mirror__group">{g.group}</div>
                    {#each g.items as f (f.key)}
                        {@const flashAt = flash[f.key]}
                        {@const isFlashed = flashAt && clock - flashAt < 700}
                        {@const trail = trails[f.key]}
                        {@const showTrail = trailsOn && trail && trail.length > 4}
                        {@const linked = linkedFields.has(f.key)}
                        {@const src = player?.provenance?.[f.key]}
                        {@const value = f.get(player)}
                        <div
                            class={'ppk-state-mirror__field' + (isFlashed ? ' flashed' : '') + (linked ? ' linked' : '')}
                            title={src ? `${shortClass(src.packetClass || '').replace(/Packet$/, '')} #${src.seq} · ${fmtAge(clock - src.ts)} ago` : 'no source'}
                        >
                            <span class="ppk-state-mirror__lbl">{f.label}</span>
                            <span class="ppk-state-mirror__trail">
                                {#if showTrail}
                                    <Sparkline data={trail} color={isFlashed ? 'var(--acc)' : 'var(--ink-3)'} />
                                {/if}
                            </span>
                            <span class="ppk-state-mirror__val">{value == null || value === '' ? '·' : value}</span>
                        </div>
                    {/each}
                </div>
            {/each}
        </Panel>
    </aside>
</div>

<style>
    @layer pages {
        :global {
    /* ---- Per-player Packets tab (state mirror) ---------------------- */
    .ppk-shell {
        display: grid;
        grid-template-columns: minmax(0, 1fr) minmax(280px, 360px);
        gap: var(--pad-3);
        align-items: start;
    }
    .ppk-aggs {
        display: grid;
        grid-template-columns: 1fr 1fr;
        gap: var(--pad-3);
        min-width: 0;
    }

    @media (max-width: 1100px) {
        .ppk-shell { grid-template-columns: 1fr; }
        .ppk-aggs  { grid-template-columns: 1fr; }
    }

    .ppk-state-mirror {
        min-width: 0;
        position: sticky;
        top: var(--pad-3);
        .ppk-state-mirror__group {
            padding: 6px var(--pad-3);
            font-size: var(--t-xs);
            color: var(--ink-3);
            text-transform: uppercase;
            background: var(--bg-2);
            border-bottom: 1px solid var(--line);
            border-top: 1px solid var(--line);
            &:first-child { border-top: none; }
        }

        .ppk-state-mirror__field {
            display: grid;
            grid-template-columns: 90px minmax(0, 1fr) auto;
            align-items: center;
            column-gap: var(--pad-2);
            padding: 6px var(--pad-3);
            border-bottom: 1px solid var(--line);
            transition: background var(--motion);
            min-height: 26px;
            &.flashed {
                animation: ppk-flash 700ms ease-out;
                .ppk-state-mirror__val { color: var(--acc); }
            }
            &.linked { background: color-mix(in oklab, var(--acc) 14%, transparent); }
        }

        .ppk-state-mirror__lbl {
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }

        .ppk-state-mirror__val {
            text-align: right;
            color: var(--ink);
            font-variant-numeric: tabular-nums;
            font-size: var(--t-sm);
            white-space: nowrap;
            transition: color var(--motion);
        }

        .ppk-state-mirror__trail {
            height: 14px;
            min-width: 0;
            align-self: center;
            canvas { width: 100%; height: 100%; display: block; }
        }

        .ppk-state-mirror__composite { padding: var(--pad-2) var(--pad-3); border-bottom: 1px solid var(--line); }

        .ppk-state-mirror__label {
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
            margin-bottom: 4px;
        }
    }

    @keyframes ppk-flash {
        0%   { background: color-mix(in oklab, var(--acc) 36%, transparent); }
        50%  { background: color-mix(in oklab, var(--acc) 18%, transparent); }
        100% { background: transparent; }
    }

    .ppk-vitals {
        display: grid;
        gap: 1px;
        background: var(--line);
        .ppk-vitals__row {
            display: grid;
            grid-template-columns: 50px 1fr 1fr;
            align-items: center;
            gap: var(--pad-2);
            padding: 6px var(--pad-3);
            background: var(--bg-1);
            &.flashed { animation: ppk-flash 700ms ease-out; }
        }

        .ppk-vitals__lbl {
            font-size: var(--t-xs);
            color: var(--ink-4);
            text-transform: uppercase;
        }
        .ppk-vitals__val {
            font-size: var(--t-md);
            color: var(--ink);
            font-variant-numeric: tabular-nums;
            &.acc    { color: var(--acc); }
            &.warn   { color: var(--warn); }
            &.danger { color: var(--danger); }
        }
        .ppk-vitals__spark {
            height: 18px;
            min-width: 0;
            display: block;
            canvas { width: 100%; height: 100%; display: block; }
        }
    }

    .ppk-hotbar {
        display: grid;
        grid-template-columns: repeat(9, 1fr);
        gap: 2px;
        padding: var(--pad-3);
        .ppk-hotbar__slot {
            aspect-ratio: 1;
            display: grid;
            place-items: center;
            background: var(--bg-2);
            border: 1px solid var(--line);
            color: var(--ink-3);
            font-size: var(--t-xs);
            position: relative;
            overflow: hidden;
            &.selected { border-color: var(--acc); box-shadow: 0 0 0 1px var(--acc) inset; }
            &.flashed { animation: ppk-flash 700ms ease-out; }

            .lbl {
                text-align: center;
                color: var(--ink-2);
                overflow: hidden;
                text-overflow: ellipsis;
                white-space: nowrap;
            }
            .count {
                position: absolute;
                right: 2px;
                bottom: 1px;
                font-size: var(--t-xs);
                color: var(--acc);
            }
        }
    }

    .ppk-scope {
        position: relative;
        aspect-ratio: 1;
        background: var(--sunk);
        box-shadow: var(--bevel-sunk);
        overflow: hidden;
        .ppk-scope__grid {
            position: absolute;
            inset: 0;
            background:
                linear-gradient(to right,  color-mix(in oklab, var(--acc) 8%, transparent) 1px, transparent 1px),
                linear-gradient(to bottom, color-mix(in oklab, var(--acc) 8%, transparent) 1px, transparent 1px);
            background-size: 25% 25%;
        }
        .ppk-scope__trail {
            position: absolute;
            inset: 0;
            pointer-events: none;
            svg { width: 100%; height: 100%; display: block; }
        }
        .ppk-scope__player {
            position: absolute;
            width: 6px;
            height: 6px;
            background: var(--acc);
            transform: translate(-50%, -50%);
            box-shadow: 0 0 8px var(--acc);
        }
        .ppk-scope__north {
            position: absolute;
            top: 4px;
            right: 4px;
            color: var(--ink-4);
            font-size: var(--t-xs);
        }
        .ppk-scope__coord {
            position: absolute;
            bottom: 4px;
            left: 4px;
            color: var(--ink-3);
            font-size: var(--t-xs);
            background: color-mix(in oklab, var(--bg-0) 80%, transparent);
            padding: 1px 4px;
        }
    }
        }
    }
</style>
