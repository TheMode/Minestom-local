<script lang="ts">
    /// Dashboard "vital signs" strip — four purpose-built cards (Sessions / Throughput /
    /// Packets / Tick). Each card shares the same shell but has its own personality:
    ///   · Sessions   — pulse dot + connection sparkline as area underlay
    ///   · Throughput — bytes/s sparkline + delta vs trailing 60s mean
    ///   · Packets    — directional split (in/out) using --dir-cb / --dir-sb tints
    ///   · Tick       — mspt with a thin "budget" micro-bar
    ///
    /// Subscribes to the shared `throughput` series and the `metrics` topic for mspt.
    import { onMount } from 'svelte';
    import { api } from '../../lib/api.ts';
    import { subscribeTopic } from '../../state/bus.svelte.ts';
    import { throughput } from '../../state/throughput.svelte.ts';
    import { Topics } from '../../lib/topics.ts';
    import type { ControlMetrics } from '../packet-trace/types.ts';
    import { humanBytes, humanNumber } from '../../lib/util.ts';

    const SESSION_CAP = 256;
    const TICK_BUDGET_MS = 50;

    let mspt = $state<number | null>(null);
    let tps  = $state<number | null>(null);
    let everSeen = $state(0);

    onMount(() => {
        api<ControlMetrics>('/metrics/latest')
            .then(m => { if (m) { mspt = m.mspt ?? null; tps = m.tps ?? null; } })
            .catch(() => {});
    });
    subscribeTopic<ControlMetrics>(Topics.metrics, m => {
        if (typeof m.mspt === 'number') mspt = m.mspt;
        if (typeof m.tps  === 'number') tps  = m.tps;
    });

    const series = $derived(throughput.series);
    const conns  = $derived(series.connections);

    // Cumulative "ever seen" — running max of the connection series. Approximation; the proxy
    // doesn't expose a lifetime session count, so the rolling window is what we have.
    $effect(() => {
        for (const v of conns) if (v > everSeen) everSeen = v;
    });

    const cur  = $derived(conns.at(-1) ?? 0);
    const prev = $derived(conns.length >= 6 ? (conns.at(-6) ?? 0) : (conns[0] ?? 0));
    const delta = $derived(cur - prev);

    const bytesNow = $derived((series.bytesIn.at(-1) ?? 0) + (series.bytesOut.at(-1) ?? 0));
    const bytesWindow = $derived.by(() => {
        const a = series.bytesIn, b = series.bytesOut;
        const n = Math.min(a.length, b.length, 60);
        if (n === 0) return [] as number[];
        const out = new Array<number>(n);
        for (let i = 0; i < n; i++) out[i] = (a.at(-1 - i) ?? 0) + (b.at(-1 - i) ?? 0);
        return out.reverse();
    });
    const bytesAvg = $derived.by(() => {
        if (!bytesWindow.length) return 0;
        let s = 0;
        for (const v of bytesWindow) s += v;
        return s / bytesWindow.length;
    });
    const bytesDeltaPct = $derived.by(() => {
        if (bytesAvg <= 0) return 0;
        return Math.round((bytesNow - bytesAvg) / bytesAvg * 100);
    });

    const pktIn  = $derived(Math.round(series.packetsIn.at(-1)  ?? 0));
    const pktOut = $derived(Math.round(series.packetsOut.at(-1) ?? 0));
    const pktTotal = $derived(pktIn + pktOut);

    const msptDisplay = $derived(mspt == null ? '—' : (mspt < 10 ? mspt.toFixed(1) : Math.round(mspt).toString()));
    const tickPct = $derived(mspt == null ? 0 : Math.max(0, Math.min(100, (mspt / TICK_BUDGET_MS) * 100)));
    const tickMood = $derived.by(() => {
        if (mspt == null) return { word: '—',     tone: 'dim'   };
        if (mspt < 25)    return { word: 'ample', tone: 'ok'    };
        if (mspt < 40)    return { word: 'cozy',  tone: 'ok'    };
        if (mspt < 50)    return { word: 'tight', tone: 'warn'  };
        return                   { word: 'over',  tone: 'danger'};
    });

    // --- sparkline path builder --------------------------------------------------
    // Renders both a line and a closed area path for the bottom band of a card.
    // The canvas is 100×40 viewBox; the path runs across the bottom 28 units.
    function spark(data: number[]): { line: string; area: string } | null {
        if (!data || data.length < 2) return null;
        let min = Infinity, max = -Infinity;
        for (const v of data) { if (v < min) min = v; if (v > max) max = v; }
        if (!Number.isFinite(min) || !Number.isFinite(max)) return null;
        const range = (max - min) || 1;
        const step = 100 / (data.length - 1);
        let line = '';
        for (let i = 0; i < data.length; i++) {
            const x = +(i * step).toFixed(2);
            const y = +(38 - ((data[i] - min) / range) * 28 - 2).toFixed(2);
            line += (i === 0 ? 'M ' : ' L ') + x + ',' + y;
        }
        const area = line + ' L 100,40 L 0,40 Z';
        return { line, area };
    }

    const sparkSessions = $derived(spark(conns));
    const sparkBytes = $derived(spark(bytesWindow));

    function deltaGlyph(d: number): string { return d > 0 ? '▲' : d < 0 ? '▼' : '·'; }
    function deltaTone (d: number): string { return d > 0 ? 'up' : d < 0 ? 'down' : ''; }
</script>

<section class="stat-strip" aria-label="Vital signs">
    <article class="stat-card stat-card--sessions">
        <header class="stat-card__hd">
            <span class="stat-card__dot" aria-hidden="true"></span>
            <span class="stat-card__label">Sessions live</span>
        </header>
        <div class="stat-card__value">
            <span class="stat-card__num acc">{cur}</span>
            <span class="stat-card__sfx">/ {SESSION_CAP}</span>
        </div>
        <div class="stat-card__sub">
            <span class={'stat-card__delta ' + deltaTone(delta)}>
                <span class="stat-card__glyph">{deltaGlyph(delta)}</span>
                {delta > 0 ? '+' : ''}{delta} in last 5s
            </span>
            <span class="stat-card__sep">·</span>
            <span class="stat-card__ever">ε {everSeen} ever</span>
        </div>
        {#if sparkSessions}
            <svg class="stat-card__spark" viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
                <path d={sparkSessions.area} fill="var(--acc-soft)"/>
                <path d={sparkSessions.line} stroke="var(--acc)" stroke-width="1.25" fill="none" vector-effect="non-scaling-stroke"/>
            </svg>
        {/if}
    </article>

    <article class="stat-card stat-card--throughput">
        <header class="stat-card__hd">
            <span class="stat-card__label">Throughput</span>
        </header>
        <div class="stat-card__value">
            <span class="stat-card__num">{humanBytes(bytesNow).replace(/ \w+$/, '')}</span>
            <span class="stat-card__unit">{humanBytes(bytesNow).replace(/^[\d.]+ /, '')}</span>
            <span class="stat-card__sfx">/s</span>
        </div>
        <div class="stat-card__sub">
            <span class={'stat-card__delta ' + deltaTone(bytesDeltaPct)}>
                <span class="stat-card__glyph">{deltaGlyph(bytesDeltaPct)}</span>
                {bytesDeltaPct > 0 ? '+' : ''}{bytesDeltaPct}% vs 1m avg
            </span>
        </div>
        {#if sparkBytes}
            <svg class="stat-card__spark" viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
                <path d={sparkBytes.area} fill="color-mix(in oklab, var(--ink-2) 12%, transparent)"/>
                <path d={sparkBytes.line} stroke="var(--ink-2)" stroke-width="1.25" fill="none" vector-effect="non-scaling-stroke"/>
            </svg>
        {/if}
    </article>

    <article class="stat-card stat-card--packets">
        <header class="stat-card__hd">
            <span class="stat-card__label">Packets</span>
        </header>
        <div class="stat-card__value">
            <span class="stat-card__num">{humanNumber(pktTotal)}</span>
            <span class="stat-card__sfx">/s</span>
        </div>
        <div class="stat-card__sub stat-card__sub--dir">
            <span class="stat-card__dir dir-sb" title="client → server">
                <span class="stat-card__glyph">◄</span> {humanNumber(pktIn)}
            </span>
            <span class="stat-card__sep">·</span>
            <span class="stat-card__dir dir-cb" title="server → client">
                <span class="stat-card__glyph">►</span> {humanNumber(pktOut)}
            </span>
        </div>
        <div class="stat-card__rails" aria-hidden="true">
            <span class="stat-card__rail dir-sb" style:--w={pktTotal ? Math.min(100, (pktIn  / pktTotal) * 100) + '%' : '0%'}></span>
            <span class="stat-card__rail dir-cb" style:--w={pktTotal ? Math.min(100, (pktOut / pktTotal) * 100) + '%' : '0%'}></span>
        </div>
    </article>

    <article class={'stat-card stat-card--tick tone-' + tickMood.tone}>
        <header class="stat-card__hd">
            <span class="stat-card__label">Tick</span>
        </header>
        <div class="stat-card__value">
            <span class="stat-card__num">{msptDisplay}</span>
            <span class="stat-card__sfx">ms</span>
        </div>
        <div class="stat-card__sub">
            <span class="stat-card__ever">budget {TICK_BUDGET_MS}</span>
            <span class="stat-card__sep">·</span>
            <span class={'stat-card__mood ' + tickMood.tone}>{tickMood.word}</span>
            {#if tps != null}
                <span class="stat-card__sep">·</span>
                <span class="stat-card__ever">{tps.toFixed(0)} tps</span>
            {/if}
        </div>
        <div class="stat-card__budget" aria-hidden="true">
            <span class="stat-card__budget-fill" style:width={tickPct + '%'}></span>
            <span class="stat-card__budget-tick" style:left="80%"></span>
        </div>
    </article>
</section>
