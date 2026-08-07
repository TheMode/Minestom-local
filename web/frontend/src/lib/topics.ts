import type { JsonObject, PlayerSummary } from './types.ts';
import type { StatePatch } from './statePatch.ts';
import type { PacketRow } from './packetAgg.ts';

/// Central catalog of WebSocket topic names produced by `DashboardServer`. Kept in sync with
/// `web/src/main/java/net/minestom/web/internal/http/Topics.java` — adding a topic on one side
/// without the other is a bug.

export const Topics = {
    console: 'console',
    metrics: 'metrics',
    global: 'global',
    players: 'players',
    playersSummary: 'players:summary',
    packetsAggregate: 'packets:aggregate',
    serverMetrics: 'server:metrics',
    /// Replay scope status transitions (running → done/error).
    scope: 'scope',
} as const;

export const playerLifecycle = (uuid: string): string => `player:${uuid}:lifecycle`;
export const playerPackets   = (uuid: string): string => `player:${uuid}:packets`;
export const playerMinimap   = (uuid: string): string => `player:${uuid}:minimap`;
export const playerState     = (uuid: string): string => `player:${uuid}:state`;

// Payload shapes mirror the JSON each backend publisher emits. Use with `bus.subscribe<T>` /
// `subscribeTopic<T>` to type a handler's argument.

export type ConsoleMessage = JsonObject & { ts: number; level: string; message: string };
export type GlobalMessage = JsonObject & { data: JsonObject | null };

export type PlayersMessage = JsonObject & {
    event: 'add' | 'disconnect' | 'remove';
    uuid: string;
    player?: PlayerSummary;
};

export type PlayersSummaryMessage = JsonObject & {
    players: PlayerSummary[];
};

export type PacketsAggregateMessage = JsonObject & {
    rows: Record<string, unknown>[];
};

export type PlayerLifecycleMessage = JsonObject & { seq?: number };

/// A coalesced [StatePatch] — see `lib/statePatch.ts`. Carries the changes for one player
/// since the last drain; the frontend applies it on top of its locally-mirrored snapshot.
export type PlayerStateMessage = StatePatch;

export type MinimapTileDto = { x: number; z: number; tile: string };
export type MinimapEntityDto = {
    id: number;
    uuid?: string;
    type: string;
    group: string;
    x: number;
    y: number;
    z: number;
    yaw?: number;
};

/// Minimap v2 — unified 10 Hz frames: pose, entities, pre-rasterized RGBA tiles (base64).
export type PlayerMinimapMessage = JsonObject & {
    v?: number;
    posX?: number;
    posY?: number;
    posZ?: number;
    yaw?: number;
    entities?: MinimapEntityDto[];
    chunks?: MinimapTileDto[];
    loaded?: MinimapTileDto[];
    unloaded?: { x: number; z: number }[];
};

/// `player:<uuid>:packets` — a single decoded packet event. Same shape as [PacketRow] (which
/// `normalizeRow` produces) plus the optional resolved `username`. `connectionId` is optional on
/// the wire; `normalizeRow` fills it from the subscription context.
export type PlayerPacketsMessage = JsonObject & Omit<PacketRow, 'connectionId'> & {
    connectionId?: string;
    username?: string;
};
