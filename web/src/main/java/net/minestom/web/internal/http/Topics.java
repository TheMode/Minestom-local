package net.minestom.web.internal.http;

import java.util.UUID;

/// Central catalog of WebSocket topic names published by [DashboardServer]. Kept in sync with
/// `web/frontend/src/lib/topics.ts` — adding a new topic on one side without the other is a bug.
public final class Topics {
    private Topics() {}

    public static final String CONSOLE = "console";
    public static final String METRICS = "metrics";
    public static final String GLOBAL = "global";
    public static final String PLAYERS = "players";
    /// Replay scope status transitions (running → done/error).
    public static final String SCOPE = "scope";
    /// Batched lightweight roster fields (ping, health, …) for list views — not full state patches.
    public static final String PLAYERS_SUMMARY = "players:summary";
    /// Batched packet rows across all sessions for the global packet analysis view.
    public static final String PACKETS_AGGREGATE = "packets:aggregate";
    public static final String SERVER_METRICS = "server:metrics";

    public static String playerLifecycle(UUID uuid) { return "player:" + uuid + ":lifecycle"; }
    public static String playerPackets(UUID uuid)   { return "player:" + uuid + ":packets"; }
    public static String playerMinimap(UUID uuid)   { return "player:" + uuid + ":minimap"; }
    public static String playerState(UUID uuid)     { return "player:" + uuid + ":state"; }
}
