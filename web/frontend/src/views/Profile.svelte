<script lang="ts">
    import { setContext } from 'svelte';
    import { api } from '../lib/api.ts';
    import { busStatus, subscribeTopic } from '../state/bus.svelte.ts';
    import { playerState, type PlayerStateMessage } from '../lib/topics.ts';
    import { applyPatch } from '../lib/statePatch.ts';
    import { humanBytes, humanDuration, shortUuid } from '../lib/util.ts';
    import { ready as assetsReady } from '../lib/assets.ts';
    import { PROV_OPEN_KEY, PROV_OPEN_FIELD_KEY } from '../lib/provenance.ts';
    import { TABS, fmtClock, provenanceCurrentValue } from '../lib/profile.ts';
    import Crumbs from '../components/ui/Crumbs.svelte';
    import Panel from '../components/ui/Panel.svelte';
    import Pill from '../components/ui/Pill.svelte';
    import MinecraftText from '../components/mctext/MinecraftText.svelte';
    import Minimap from '../components/Minimap.svelte';
    import ProvenancePopover from '../components/overlay/ProvenancePopover.svelte';
    import ProvTooltipHost from '../components/overlay/ProvTooltipHost.svelte';
    import PlayerPackets from '../components/profile/PlayerPackets.svelte';
    import PlayerEntities from '../components/profile/PlayerEntities.svelte';
    import PlayerRegistries from '../components/profile/PlayerRegistries.svelte';
    import PlayerLifecycle from '../components/profile/PlayerLifecycle.svelte';
    import RunActionPanel from '../components/ui/RunActionPanel.svelte';
    import ChatListScrollBottom from '../components/mctext/ChatListScrollBottom.svelte';
    import IdentityPanel from '../components/profile/IdentityPanel.svelte';
    import VitalsPanel from '../components/profile/VitalsPanel.svelte';
    import AbilitiesPanel from '../components/profile/AbilitiesPanel.svelte';
    import EffectsPanel from '../components/profile/EffectsPanel.svelte';
    import PositionPanel from '../components/profile/PositionPanel.svelte';
    import HudPanel from '../components/profile/HudPanel.svelte';
    import InventoryPanel from '../components/profile/InventoryPanel.svelte';
    import AttributesPanel from '../components/profile/AttributesPanel.svelte';
    import PingPanel from '../components/profile/PingPanel.svelte';
    import ServerDataPanel from '../components/profile/ServerDataPanel.svelte';
    import { toast } from '../state/toasts.svelte.ts';

    let { uuid, tab = 'overview' } = $props();

    let player = $state(null);
    let err = $state(null);
    let tracesOn = $state(true);
    let prov = $state(null);
    let paused = $state(false);
    /// Patches that arrive before the REST snapshot lands. Applied in order once `player`
    /// hydrates, then discarded.
    let pendingPatches: PlayerStateMessage[] = [];

    $effect(() => {
        uuid;
        let alive = true;
        pendingPatches = [];
        (async () => {
            try {
                await assetsReady();
                const p = await api('/players/' + uuid);
                if (!alive) return;
                // Drain *and* publish in the same task: any patch that arrives after the array
                // is read but before `player` is assigned will see `!player` again and queue,
                // and the next handler invocation will apply it directly.
                const queued = pendingPatches;
                pendingPatches = [];
                for (const buffered of queued) applyPatch(p, buffered);
                player = p;
            } catch (e) {
                if (alive) err = e.message;
            }
        })();
        return () => { alive = false; };
    });

    subscribeTopic<PlayerStateMessage>(() => uuid ? playerState(uuid) : null, msg => {
        if (paused || !msg) return;
        if (!player) { pendingPatches.push(msg); return; }
        applyPatch(player, msg);
    });

    function openProv(field, anchor) { prov = { field, anchor }; }
    function closeProv() { prov = null; }

    setContext(PROV_OPEN_KEY, openProv);
    setContext(PROV_OPEN_FIELD_KEY, () => prov?.field ?? null);

    const baseHref = $derived('/p/' + uuid);
    const hrefFor = t => baseHref + (t === 'overview' ? '' : '/' + t);
    const now = $derived(busStatus.now);

    async function kick() {
        try {
            await api(`/players/${player.uuid}/inject`, {
                method: 'POST',
                body: { class: 'DisconnectPacket', fields: { reason: { text: 'Kicked by operator', color: 'red' } } },
            });
            toast('Kick packet injected');
        } catch (e) { toast('Kick failed: ' + e.message, 'error'); }
    }
</script>

{#snippet playersCrumb()}<a href="/players">Players</a>{/snippet}
{#snippet nameCrumb()}{player?.username || player?.uuid}{/snippet}

{#snippet receivedChat(received)}
    {#if received.length === 0}
        <div class="empty">No chat received.</div>
    {:else}
        {#each received as line, i (i)}
            <div class="chat-msg" class:player={line.style === 'player'} class:system={line.style === 'system'}>
                <span class="chat-msg__when">
                    {fmtClock(line.ts)}
                    {#if line.sender}<span class="chat-msg__who dim mono"> · {shortUuid(line.sender)}</span>{/if}
                </span>
                <span class="chat-msg__body mc-component"><MinecraftText value={line.content} /></span>
            </div>
        {/each}
    {/if}
{/snippet}

{#snippet sentChat(sent)}
    {#if sent.length === 0}
        <div class="empty">No outgoing chat captured yet.</div>
    {:else}
        {#each sent as m, i (i)}
            <div class="chat-msg outgoing">
                <span class="chat-msg__when">{fmtClock(m.ts)}</span>
                <span class="chat-msg__body">
                    {#if m.kind === 'command'}<span class="acc">/</span>{/if}
                    {m.text}
                </span>
            </div>
        {/each}
    {/if}
{/snippet}

{#if err}
    <div class="panel">
        <div class="panel-body">
            <h2>Player not found</h2>
            <pre class="code">{err}</pre>
        </div>
    </div>
{:else if !player}
    <div class="empty">Loading…</div>
{:else}
    <div class="v-profile" data-traces={tracesOn ? 'on' : 'off'}>
        <div class="view-head view-head--compact">
            <div>
                <Crumbs steps={[playersCrumb, nameCrumb]} />
            </div>
            <div class="view-actions">
                <button
                    class={paused ? 'primary sm' : 'ghost sm'}
                    onclick={() => paused = !paused}
                    title={paused ? 'Resume live updates' : 'Freeze this profile at the current state'}
                >{paused ? '▶ Resume' : '❚❚ Pause'}</button>
                <button class="ghost sm" onclick={() => { navigator.clipboard.writeText(player.uuid || '').catch(() => {}); toast('UUID copied'); }}>⎘ Copy UUID</button>
                <button class="danger sm" onclick={kick}>Kick</button>
            </div>
        </div>
        <div class="pheader">
            <div class="pheader__avatar">{(player.username || '?').slice(0, 2).toUpperCase()}</div>
            <div>
                <div class="pheader__name">{player.username || 'unknown'}</div>
                <div class="pheader__meta">
                    <Pill kind="on" dot>{player.serverConnectionState || '—'}</Pill>
                    <span><span class="lbl">UUID</span><span class="v mono">{shortUuid(player.uuid)}</span></span>
                    <span><span class="lbl">Session</span><span class="v">{humanDuration(now - (player.connectedAt || now))}</span></span>
                    {#if player.protocolVersion != null}<span><span class="lbl">Protocol</span><span class="v">{player.protocolVersion}</span></span>{/if}
                    {#if player.locale}<span><span class="lbl">Locale</span><span class="v">{player.locale}</span></span>{/if}
                </div>
            </div>
            <div class="pheader__live">
                <div class="pheader__live-cell">
                    <span class="num">{player.traffic.pingMs}<span class="dim small"> ms</span></span>
                    <span>Ping</span>
                </div>
                <div class="pheader__live-cell">
                    <span class="num">{humanBytes(player.traffic.bytesIn + player.traffic.bytesOut)}</span>
                    <span>Total i/o</span>
                </div>
            </div>
        </div>
        {#if paused}
            <div class="profile-paused-banner">
                Frozen · live state updates and packet streams are paused. Click <em>Resume</em> to continue.
            </div>
        {/if}

        <nav class="ptabs" aria-label="Player views">
            {#each TABS as t (t.id)}
                <a href={hrefFor(t.id)} aria-current={tab === t.id ? 'page' : undefined}>
                    {t.label}
                    {#if t.live}<span class="ptab-count">live</span>{/if}
                </a>
            {/each}
        </nav>

        {#if tab === 'overview'}
            <div class="callout">
                <Pill kind="on">ⓘ Provenance</Pill>
                <span class="dim small grow">
                    Every traceable value carries a quiet dotted underline.
                    Hover to peek the source packet · click to pin the full history.
                </span>
                <button class="ghost sm" onclick={() => tracesOn = !tracesOn}>{tracesOn ? 'Hide all traces' : 'Show traces'}</button>
            </div>

            <div class="v-profile__shell">
                <div class="col gap-lg">
                    <IdentityPanel p={player} />
                    <VitalsPanel p={player} />
                    <AbilitiesPanel p={player} />
                    <EffectsPanel p={player} />
                    <PositionPanel p={player} />
                </div>
                <div class="col gap-lg">
                    <HudPanel p={player} />
                    <InventoryPanel p={player} />
                    <AttributesPanel p={player} />
                </div>
                <div class="col gap-lg">
                    <section class="panel panel--tall"><Minimap {uuid} {player} {paused} /></section>
                    <PingPanel p={player} />
                    <ServerDataPanel p={player} />
                </div>
            </div>
        {:else if tab === 'packets'}
            <PlayerPackets {player} {paused} />
        {:else if tab === 'lifecycle'}
            <PlayerLifecycle {player} />
        {:else if tab === 'inventory'}
            <InventoryPanel p={player} />
        {:else if tab === 'world'}
            <div class="profile-world-stage">
                <Minimap {uuid} {player} {paused} />
            </div>
        {:else if tab === 'entities'}
            <PlayerEntities {player} {paused} />
        {:else if tab === 'registries'}
            <PlayerRegistries {player} />
        {:else if tab === 'action'}
            <RunActionPanel p={player} />
        {:else if tab === 'chat'}
            <div class="chat-split">
                <Panel title="As the player sees it" meta={`${(player.recentChat || []).slice(-100).length} messages`} flush>
                    <ChatListScrollBottom dependency={(player.recentChat || []).slice(-100).length}>
                        {#snippet children()}{@render receivedChat((player.recentChat || []).slice(-100))}{/snippet}
                    </ChatListScrollBottom>
                </Panel>
                <Panel title="What they sent" meta={`${(player.sentChat || []).slice(-100).length} captured`} flush>
                    <ChatListScrollBottom dependency={(player.sentChat || []).slice(-100).length}>
                        {#snippet children()}{@render sentChat((player.sentChat || []).slice(-100))}{/snippet}
                    </ChatListScrollBottom>
                </Panel>
            </div>
        {/if}

        {#if prov}
            <ProvenancePopover
                {uuid}
                field={prov.field}
                anchor={prov.anchor}
                valueOf={f => provenanceCurrentValue(player, f)}
                sourceSeq={player?.provenance?.[prov.field]?.seq ?? null}
                onClose={closeProv}
            />
        {/if}
        <ProvTooltipHost />
    </div>
{/if}
