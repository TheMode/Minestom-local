import { api, bus, getScope, setScope } from '../lib/api.ts';
import { Topics } from '../lib/topics.ts';

type ServerMode = 'live' | 'replay';
type ReplayStatus = 'pending' | 'running' | 'done' | 'error';

type ScopeSummary = {
    id: string;
    label: string;
    replay: boolean;
    createdAt: number;
    connectionCount: number;
    status?: ReplayStatus;
    error?: string;
    /// Wall-clock ms at which the replay ended (status ∈ done/error); use instead of `Date.now()`
    /// for any counter that should freeze when the file is exhausted.
    endedAt?: number;
};

export const REPLAY_TERMINAL: ReadonlySet<ReplayStatus> = new Set<ReplayStatus>(['done', 'error']);

type ModeResponse = {
    mode: ServerMode;
    scope: ScopeSummary | null;
    protocolVersion: number;
};

/// Reactive server-mode state. Booted once from `GET /api/mode`. `scope` is the only field
/// that mutates after boot (after upload / eviction); `mode` and `protocolVersion` are
/// effectively constant.
class Mode {
    mode = $state<ServerMode | null>(null);
    scope = $state<ScopeSummary | null>(null);
    protocolVersion: number | null = null;

    async boot(): Promise<void> {
        if (this.mode !== null) return;
        let m = await api<ModeResponse>('/mode');
        // Cached scope id didn't resolve — file expired, server restart, or a stale replay
        // id sitting in sessionStorage when the proxy is now in live mode (would 404 every
        // scoped endpoint). Drop it and refetch to pick up the actual default scope.
        if (m.scope == null && getScope()) {
            setScope(null);
            m = await api<ModeResponse>('/mode');
        }
        this.mode = m.mode;
        this.protocolVersion = m.protocolVersion;
        this.scope = m.scope;
        bus.subscribe<ScopeSummary>(Topics.scope, summary => {
            if (summary.id === this.scope?.id) this.scope = summary;
        });
    }

    /// Upload a SQLite history and switch the bus to its new scope. Sets `scope` from the
    /// server's response directly — no extra `/api/mode` round-trip.
    async uploadReplay(file: Blob | ArrayBuffer | Uint8Array, label?: string,
                       respectTimestamps = true): Promise<ScopeSummary> {
        const headers: Record<string, string> = { 'Content-Type': 'application/octet-stream' };
        if (label) headers['X-Replay-Label'] = label;
        headers['X-Replay-Respect-Timestamps'] = respectTimestamps ? 'true' : 'false';
        const summary = await api<ScopeSummary>('/replay', {
            method: 'POST',
            headers,
            body: file as BodyInit,
        });
        setScope(summary.id);
        this.scope = summary;
        return summary;
    }

    async deleteCurrentScope(): Promise<void> {
        const id = getScope();
        if (!id) return;
        try { await api(`/replay/${id}`, { method: 'DELETE' }); } catch {}
        setScope(null);
        this.scope = null;
    }
}

export const mode = new Mode();
