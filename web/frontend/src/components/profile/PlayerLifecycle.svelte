<script module lang="ts">
    /// Visual presentation for each lifecycle kind. Order in the legend matches the natural
    /// session progression: CONNECT → HANDSHAKE → LOGIN → CONFIG → PLAY → DISCONNECT.
    export const KIND_META = {
        CONNECT:              { label: 'Connect',              glyph: '◉', accent: 'var(--ink-3)' },
        HANDSHAKE:            { label: 'Handshake',            glyph: '↪', accent: 'oklch(75% 0.13 230)' },
        LOGIN_START:          { label: 'Login start',          glyph: '⌗', accent: 'oklch(78% 0.18 80)' },
        COMPRESSION_SET:      { label: 'Compression set',      glyph: '≋', accent: 'oklch(72% 0.18 310)' },
        LOGIN_SUCCESS:        { label: 'Login success',        glyph: '✓', accent: 'var(--acc)' },
        CONFIGURATION_START:  { label: 'Configuration start',  glyph: '⚙', accent: 'oklch(78% 0.13 148)' },
        CONFIGURATION_FINISH: { label: 'Configuration finish', glyph: '⚙', accent: 'oklch(78% 0.13 148)' },
        PLAY_START:           { label: 'Play start',           glyph: '▶', accent: 'var(--acc)' },
        DISCONNECT:           { label: 'Disconnect',           glyph: '✕', accent: 'var(--danger)' },
    };

    /// Walk a JSON tree and emit `[dotted-path, leafValue]` pairs. Used to surface the scalar
    /// fields of a captured packet ({record.username, record.gameProfile.uuid, …}) without
    /// rendering a full tree. Arrays of scalars collapse to "[…, …]"; arrays of objects skip
    /// individual entries.
    export function flattenLeaves(value: unknown, prefix = '', depth = 0): [string, string][] {
        if (depth > 4 || value == null) return [];
        if (Array.isArray(value)) {
            if (value.length === 0) return [[prefix || '·', '[]']];
            const allScalar = value.every(v => v == null || typeof v !== 'object');
            if (allScalar) return [[prefix || '·', '[' + value.map(String).join(', ') + ']']];
            return [[prefix || '·', `[${value.length} items]`]];
        }
        if (typeof value === 'object') {
            const out: [string, string][] = [];
            for (const [k, v] of Object.entries(value as Record<string, unknown>)) {
                const path = prefix ? `${prefix}.${k}` : k;
                out.push(...flattenLeaves(v, path, depth + 1));
            }
            return out;
        }
        return [[prefix || '·', String(value)]];
    }
</script>

<script lang="ts">
    import { api } from '../../lib/api.ts';
    import { subscribeTopic } from '../../state/bus.svelte.ts';
    import { playerLifecycle, type PlayerLifecycleMessage } from '../../lib/topics.ts';
    import { navigate } from '../../lib/nav.ts';
    import { fmtAge } from '../../lib/util.ts';
    import Panel from '../ui/Panel.svelte';

    let { player } = $props();

    const uuid = $derived(player?.uuid);

    let events = $state([]);
    let err = $state(null);

    $effect(() => {
        if (!uuid) return;
        let alive = true;
        api(`/players/${uuid}/lifecycle`)
            .then(r => { if (alive) { events = r || []; err = null; } })
            .catch(e => { if (alive) err = String(e.message || e); });
        return () => { alive = false; };
    });

    subscribeTopic<PlayerLifecycleMessage>(() => uuid ? playerLifecycle(uuid) : null, msg => {
        if (!msg || msg.seq == null) return;
        if (events.some(e => e.seq === msg.seq)) return;
        events = [...events, msg];
    });

    const first = $derived(events[0]?.ts ?? null);
    const last = $derived(events[events.length - 1]?.ts ?? null);
</script>

{#snippet lifecycleContent(e, meta, dt)}
    <span class="lifecycle__glyph">{meta.glyph}</span>
    <div class="lifecycle__body">
        <div class="lifecycle__head">
            <span class="lifecycle__kind">{meta.label}</span>
            {#if dt != null}<span class="lifecycle__gap">+{fmtAge(dt)}</span>{/if}
            {#if e.packetSeq > 0}<span class="lifecycle__seq">#{e.packetSeq}</span>{/if}
        </div>
        {#each flattenLeaves(e.data) as [k, v] (k)}
            <div class="lifecycle__kv">
                <span class="lifecycle__k">{k}</span>
                <span class="lifecycle__v">{v}</span>
            </div>
        {/each}
    </div>
    <span class="lifecycle__ts" title={new Date(e.ts).toISOString()}>
        {new Date(e.ts).toLocaleTimeString('en-GB', { hour12: false })}
    </span>
{/snippet}

<Panel
    title="Connection lifecycle"
    meta={events.length === 0 ? '—' : `${events.length} events${first && last ? ` · ${fmtAge(last - first)} span` : ''}`}
    flush
>
    {#if err}
        <div class="empty">Error · {err}</div>
    {:else if events.length === 0}
        <div class="empty">No lifecycle events captured yet.</div>
    {:else}
        <ol class="lifecycle">
            {#each events as e, i (e.seq)}
                {@const meta = KIND_META[e.kind] || { label: e.kind, glyph: '·', accent: 'var(--ink-3)' }}
                {@const dt = i === 0 ? null : e.ts - events[i - 1].ts}
                <li class="lifecycle__item">
                    {#if e.packetSeq > 0}
                        <button
                            type="button"
                            class="lifecycle__step data-row data-row--interactive has-packet"
                            onclick={() => navigate(`/p/${uuid}/packets?seq=${e.packetSeq}`)}
                            style:--phase={meta.accent}
                        >
                            {@render lifecycleContent(e, meta, dt)}
                        </button>
                    {:else}
                        <div class="lifecycle__step data-row" style:--phase={meta.accent}>
                            {@render lifecycleContent(e, meta, dt)}
                        </div>
                    {/if}
                    </li>
            {/each}
        </ol>
    {/if}
</Panel>
