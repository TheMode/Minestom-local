<script module lang="ts">
    import { TriggerType } from '../../lib/routineWire.ts';

    export const KINDS = [
        { id: TriggerType.onMatch, icon: '▶', label: 'On match', detail: 'Edge — fires once when a player starts matching the filter.' },
        { id: TriggerType.onUnmatch, icon: '◀', label: 'On unmatch', detail: 'Edge — fires once when a player stops matching the filter.' },
        { id: TriggerType.interval, icon: '⟳', label: 'Interval', detail: 'Periodic — fires every N ms for every matching player.' },
        { id: TriggerType.onPacket, icon: '⚡', label: 'On packet', detail: 'Per-packet — fires for every decoded packet of the given class (after debounce).' },
    ];

    export const INTERVAL_PRESETS = [
        { ms: 100, label: '100ms' },
        { ms: 1000, label: '1s' },
        { ms: 5000, label: '5s' },
        { ms: 30000, label: '30s' },
        { ms: 60000, label: '1m' },
        { ms: 300000, label: '5m' },
    ];

    const trim = (n: number) => Number.isInteger(n) ? String(n) : n.toFixed(1).replace(/\.0$/, '');

    export function humanInterval(ms: number) {
        if (!ms || ms < 0) return 'never';
        if (ms < 1000) return `${ms} ms`;
        if (ms < 60_000) {
            const s = ms / 1000;
            return s === 1 ? '1 second' : `${trim(s)} seconds`;
        }
        if (ms < 3_600_000) {
            const m = ms / 60_000;
            return m === 1 ? 'once per minute' : `every ${trim(m)} minutes`;
        }
        const h = ms / 3_600_000;
        return h === 1 ? 'once per hour' : `every ${trim(h)} hours`;
    }

    export function defaults(id: string) {
        if (id === TriggerType.interval) return { type: TriggerType.interval, millis: 5000 };
        if (id === TriggerType.onPacket) return { type: TriggerType.onPacket, packet: '' };
        return { type: id };
    }

    export function normalise(t: { type?: string; packet?: string; millis?: number } | null | undefined) {
        const id = KINDS.find(k => k.id === t?.type)?.id ?? TriggerType.onMatch;
        if (id === TriggerType.interval) return { type: TriggerType.interval, millis: Number(t?.millis) || 5000 };
        if (id === TriggerType.onPacket) return { type: TriggerType.onPacket, packet: String(t?.packet ?? '') };
        return { type: id };
    }

    export { TriggerType };
</script>

<script lang="ts">
    import PacketSelector from '../packets/PacketSelector.svelte';

    let { value, onChange } = $props();

    const groupId = 'trig-' + Math.random().toString(36).slice(2, 9);
    const state = $derived(normalise(value));
    const kind = $derived(KINDS.find(k => k.id === state.type));

    function set(next: ReturnType<typeof normalise>) { onChange?.(normalise(next)); }
</script>

<div class="trigger-editor">
    <div class="seg-control editor-tabs" role="radiogroup">
        {#each KINDS as k (k.id)}
            <label class="seg-control__item editor-tab">
                <input
                    type="radio"
                    name={groupId}
                    value={k.id}
                    checked={state.type === k.id}
                    onchange={() => state.type === k.id ? null : set(defaults(k.id))}
                />
                <span class="editor-tab__icon">{k.icon}</span>
                <span class="editor-tab__label">{k.label}</span>
            </label>
        {/each}
    </div>
    <div class="editor-detail">{kind?.detail || ''}</div>
    <div class="editor-config">
        {#if state.type === TriggerType.interval}
            {@const millis = state.millis}
            {@const isPreset = INTERVAL_PRESETS.some(p => p.ms === millis)}
            <div class="trig-interval">
                <div class="trig-interval__label">Fire every</div>
                <div class="seg-control trig-interval__seg" role="group" aria-label="Interval">
                    {#each INTERVAL_PRESETS as p (p.ms)}
                        <button
                            type="button"
                            class={'trig-interval__chip' + (p.ms === millis ? ' is-on' : '')}
                            onclick={() => set({ ...state, millis: p.ms })}
                        >{p.label}</button>
                    {/each}
                    <span class={'trig-interval__custom' + (isPreset ? '' : ' is-on')}>
                        <input
                            type="number"
                            min={100}
                            step={100}
                            value={millis}
                            onchange={e => set({ ...state, millis: Math.max(0, Number(e.currentTarget.value) || 0) })}
                            aria-label="Custom interval in milliseconds"
                        />
                        <span class="trig-interval__unit">ms</span>
                    </span>
                </div>
                <div class="trig-interval__hint">≈ {humanInterval(millis)}</div>
            </div>
        {:else if state.type === TriggerType.onPacket}
            <label class="editor-field editor-field--col">
                <span class="editor-label">Packet</span>
                <PacketSelector value={state.packet} onChange={v => set({ ...state, packet: v })} />
            </label>
            <div class="editor-hint">Simple class name (e.g. <code>ClientChatMessagePacket</code>) — matched against every decoded packet.</div>
        {/if}
    </div>
</div>