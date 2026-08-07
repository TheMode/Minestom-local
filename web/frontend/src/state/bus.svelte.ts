import { bus } from '../lib/api.ts';
import { mode, REPLAY_TERMINAL } from './mode.svelte.ts';
import type { PacketTopicMessage } from '../lib/types.ts';

/// Reactive wrapper around the singleton bus connection state, plus a 1Hz wall-clock tick
/// for the topbar live badges. Freezes on `scope.endedAt` once a replay is exhausted, so
/// uptime + session-duration counters stop climbing.
class BusStatus {
    connected = $state(bus.connected);
    now = $state(Date.now());

    constructor() {
        bus.addEventListener('open',  () => { this.connected = true; });
        bus.addEventListener('close', () => { this.connected = false; });
        setInterval(() => {
            const s = mode.scope;
            if (s?.status && REPLAY_TERMINAL.has(s.status)) {
                this.now = s.endedAt ?? this.now;
                return;
            }
            this.now = Date.now();
        }, 1000);
    }
}

export const busStatus = new BusStatus();

/// Subscribe to a WebSocket topic for the lifetime of the calling component.
/// Pass `null` for `topic` to disable. Must be called from a component `<script>` or `.svelte.ts`
/// reactive context — uses `$effect` to register and clean up.
export function subscribeTopic<T extends PacketTopicMessage = PacketTopicMessage>(
    topicGetter: string | null | (() => string | null),
    handler: (message: T) => void,
): void {
    $effect(() => {
        const t = typeof topicGetter === 'function' ? topicGetter() : topicGetter;
        if (!t) return undefined;
        return bus.subscribe(t, handler);
    });
}
