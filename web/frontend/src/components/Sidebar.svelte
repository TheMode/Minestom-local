<script lang="ts">
    import { busStatus } from '../state/bus.svelte.ts';
    import { mode, REPLAY_TERMINAL } from '../state/mode.svelte.ts';
    import { players as playersStore } from '../state/players.svelte.ts';
    import { throughput, bytesSeries, packetsSeries } from '../state/throughput.svelte.ts';
    import { humanBytes, humanNumber, shortUuid } from '../lib/util.ts';
    import { TABS as PROFILE_TABS } from '../lib/profile.ts';
    import Sparkline from './ui/Sparkline.svelte';

    const NAV = [
        { path: '/',         key: 'dashboard', label: 'Dashboard', glyph: '▥', live: false },
        { path: '/players',  key: 'players',   label: 'Players',   glyph: '◯', live: false },
        { path: '/packets',  key: 'packets',   label: 'Packets',   glyph: '⇄', live: false },
        { path: '/terminal', key: 'terminal',  label: 'Terminal',  glyph: '›_', live: true },
        { path: '/trigger',  key: 'trigger',   label: 'Trigger',   glyph: '⚐', live: true },
        { path: '/actions',  key: 'actions',   label: 'Actions',   glyph: '⚙', live: true },
        { path: '/routines', key: 'routines',  label: 'Routines',  glyph: '∾', live: true },
        { path: '/throttle', key: 'throttle',  label: 'Throttle',  glyph: '≋', live: true },
    ];

    const MIN_WIDTH = 180;
    const MAX_WIDTH = 420;
    const STORAGE_KEY = 'mw-sidebar-w';

    const loadWidth = () => {
        try {
            const v = parseInt(localStorage.getItem(STORAGE_KEY) || '0', 10);
            if (Number.isFinite(v) && v >= MIN_WIDTH && v <= MAX_WIDTH) return v;
        } catch {}
        return 244;
    };
    const saveWidth = w => { try { localStorage.setItem(STORAGE_KEY, String(w)); } catch {} };

    let { navKey, currentUuid, profileTab, onTweaks, isReplay = false } = $props();
    const visibleNav = $derived(isReplay ? NAV.filter(n => !n.live) : NAV);

    let width = $state(loadWidth());
    let dragging = false;

    let mobileOpen = $state(false);

    $effect(() => {
        document.documentElement.style.setProperty('--side-w', width + 'px');
    });
    // Drives `body[data-chrome="side"] #app { grid-template-columns: var(--side-w) 1fr; }`.
    // The cleanup matters for replay-mode Landing, where Sidebar unmounts and the bare main
    // would otherwise stay squeezed into the empty sidebar track.
    $effect(() => {
        document.body.dataset.chrome = 'side';
        return () => { delete document.body.dataset.chrome; };
    });

    $effect(() => {
        if (mobileOpen) document.body.dataset.sidebar = 'open';
        else delete document.body.dataset.sidebar;
    });

    // Auto-close when nav target changes so a link tap on mobile dismisses the drawer.
    $effect(() => { navKey; profileTab; currentUuid; mobileOpen = false; });

    $effect(() => {
        const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') mobileOpen = false; };
        document.addEventListener('keydown', onKey);
        return () => document.removeEventListener('keydown', onKey);
    });

    $effect(() => {
        const onMove = e => {
            if (!dragging) return;
            width = Math.max(MIN_WIDTH, Math.min(MAX_WIDTH, e.clientX));
        };
        const onUp = () => {
            if (!dragging) return;
            dragging = false;
            document.body.style.userSelect = '';
            document.body.style.cursor = '';
            saveWidth(width);
        };
        window.addEventListener('pointermove', onMove);
        window.addEventListener('pointerup', onUp);
        return () => {
            window.removeEventListener('pointermove', onMove);
            window.removeEventListener('pointerup', onUp);
        };
    });

    const list = $derived(playersStore.visible);
    const onlineCount = $derived(playersStore.onlineCount);
    const currentPlayer = $derived(currentUuid ? playersStore.list.find(p => p.uuid === currentUuid) : null);
    const ok = $derived(busStatus.connected);
    const bpsSeries = $derived(bytesSeries(throughput.series));
    const ppsSeries = $derived(packetsSeries(throughput.series));
    const bpsNow = $derived(bpsSeries.at(-1) || 0);
    const ppsNow = $derived(ppsSeries.at(-1) || 0);

    const replayStatus = $derived(mode.scope?.status);
    const replayEnded = $derived(!!replayStatus && REPLAY_TERMINAL.has(replayStatus));
    // Sidebar value cell ellipsises past ~80px, so keep replay labels to a single word.
    const modeLabel = $derived.by(() => {
        if (!isReplay) return ok ? 'live' : 'off';
        if (replayStatus === 'error') return 'failed';
        if (replayStatus === 'done')  return 'ended';
        return 'replay';
    });

    function avatarStyle(name) {
        let h = 0;
        for (let i = 0; i < (name || '').length; i++) h = (h * 13 + name.charCodeAt(i)) | 0;
        const hue = Math.abs(h) % 360;
        return `background: oklch(28% 0.04 ${hue}); color: oklch(85% 0.12 ${hue})`;
    }
</script>

<button
    type="button"
    class="siderail-trigger"
    aria-label={mobileOpen ? 'Close navigation' : 'Open navigation'}
    aria-expanded={mobileOpen}
    onclick={() => mobileOpen = !mobileOpen}
>{mobileOpen ? '×' : '≡'}</button>

<button
    type="button"
    class="siderail-scrim"
    aria-label="Close navigation"
    onclick={() => mobileOpen = false}
></button>

<aside class="siderail" aria-label="Primary navigation">
    <div class="siderail__health">
        <div class={'siderail__health-row' + (ok && !replayEnded ? ' ok' : ' down')}>
            <span class="lbl">{isReplay ? 'Mode' : 'Proxy'}</span>
            <span class="val">{modeLabel}</span>
        </div>
        <div class={'siderail__health-row' + (ok ? ' ok' : ' down')}>
            <span class="lbl">Bus</span><span class="val">{ok ? 'ok' : 'down'}</span>
        </div>
        <div class="siderail__health-row">
            <span class="lbl">Online</span><span class="val">{onlineCount}</span>
        </div>
        <div class="siderail__health-dots" title={replayEnded ? 'Replay ended' : 'Live tick (last 20)'}>
            {#each Array(20) as _, i (i)}
                <i class={ok && !replayEnded ? 'live' : ''}></i>
            {/each}
        </div>
    </div>

    <nav class="siderail__nav">
        {#each visibleNav as n (n.key)}
            {@const isPlayers = n.key === 'players'}
            {@const showName = isPlayers && currentUuid}
            {@const activePlayerName = currentPlayer?.username || shortUuid(currentUuid || '')}
            <a
                href={n.path}
                class="siderail__nav-link"
                aria-current={n.key === navKey ? 'page' : undefined}
            >
                <span class="siderail__glyph">{n.glyph}</span>
                <span class="siderail__nav-label">
                    {n.label}
                    {#if showName}<span class="siderail__nav-active"> [{activePlayerName}]</span>{/if}
                </span>
                {#if isPlayers && !currentUuid && onlineCount > 0}
                    <span class="siderail__nav-count">{onlineCount}</span>
                {/if}
            </a>
            {#if isPlayers && currentUuid}
                <div class="siderail__subnav">
                    {#each PROFILE_TABS as t (t.id)}
                        {@const href = '/p/' + currentUuid + (t.id === 'overview' ? '' : '/' + t.id)}
                        <a
                            {href}
                            class="siderail__subnav-link"
                            aria-current={profileTab === t.id ? 'page' : undefined}
                        >
                            <span class="siderail__subnav-label">{t.label}</span>
                            {#if t.live}<span class="siderail__subnav-live">live</span>{/if}
                        </a>
                    {/each}
                </div>
            {/if}
        {/each}
    </nav>

    <div class="siderail__players">
        <div class="siderail__players-h">
            <span>Players · {list.length}</span>
            <span class="dim" style="font-size: var(--t-xs);">jump</span>
        </div>
        <div class="siderail__players-list">
            {#each list.slice(0, 16) as p (p.uuid)}
                {@const offline = !!p.disconnectedAt}
                <a
                    href={'/p/' + p.uuid}
                    class="siderail__player"
                    class:siderail__player--offline={offline}
                    aria-current={currentUuid === p.uuid ? 'true' : undefined}
                    title={p.username || shortUuid(p.uuid)}
                >
                    <span class="siderail__avatar" style={avatarStyle(p.username || p.uuid)}>
                        {(p.username || p.uuid || '?').slice(0, 2).toUpperCase()}
                    </span>
                    <span class="siderail__player-name">{p.username || shortUuid(p.uuid)}</span>
                    <span class="siderail__player-ping">{offline ? 'offline' : p.traffic.pingMs + 'ms'}</span>
                </a>
            {/each}
            {#if list.length === 0}<div class="empty empty--compact">No connections.</div>{/if}
        </div>
    </div>

    <div class="siderail__throughput">
        <div class="siderail__throughput-row">
            <span class="siderail__throughput-sw" style="background: var(--ink-3)"></span>
            <span class="lbl">Bytes</span>
            <span class="val">{humanBytes(bpsNow)}/s</span>
        </div>
        <div class="siderail__throughput-row">
            <span class="siderail__throughput-sw" style="background: var(--acc)"></span>
            <span class="lbl">Packets</span>
            <span class="val">{humanNumber(Math.round(ppsNow))}/s</span>
        </div>
        <div class="siderail__spark">
            <Sparkline data={ppsSeries} color="var(--acc)" fill="var(--acc-soft)" />
        </div>
        <button type="button" class="ghost sm" onclick={onTweaks}>Tweaks</button>
    </div>

    <div
        class="siderail__resize"
        role="separator"
        aria-orientation="vertical"
        aria-label="Resize sidebar"
        title="Drag to resize"
        onpointerdown={() => {
            dragging = true;
            document.body.style.userSelect = 'none';
            document.body.style.cursor = 'col-resize';
        }}
    ></div>
</aside>
