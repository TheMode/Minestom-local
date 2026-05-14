// Player-domain helpers shared by the Dashboard and Players views.

/// Pill kind for a player row: disconnected first, then connection-phase. Shared between the
/// Dashboard "active sessions" table and the Players view so they agree on what shade to use.
export function pillKindFor(p: { disconnectedAt?: number; serverConnectionState?: string }): 'on' | 'warn' | 'ghost' {
    if (p.disconnectedAt) return 'ghost';
    if (p.serverConnectionState === 'PLAY') return 'on';
    if (p.serverConnectionState === 'CONFIGURATION') return 'warn';
    return 'ghost';
}

/// Session length in ms: from connect to either disconnect (if offline) or `now` (if live).
export function sessionDuration(p: { connectedAt?: number; disconnectedAt?: number }, now: number): number {
    const end = p.disconnectedAt || now;
    const start = p.connectedAt || now;
    return end - start;
}
