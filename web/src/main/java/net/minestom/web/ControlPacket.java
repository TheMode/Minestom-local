package net.minestom.web;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;

/// Payload exchanged between the dashboard and the embedding (game) side of an in-JVM
/// Minestom deployment. The bridge is just a typed mailbox — there is no transport, no
/// serialisation, no id registry.
public sealed interface ControlPacket {

    /// Execute a command line on the game side. Web → game.
    record Command(String command) implements ControlPacket {}

    /// One line of console output observed on the game side. Game → web.
    /// `level` is free-form (`INFO`, `WARN`, `ERROR`, `STDOUT`, …).
    record ConsoleLine(long ts, String level, String message) implements ControlPacket {}

    /// Periodic JVM + tick snapshot. Game → web.
    ///
    /// - `processCpu` / `heapUsed` / `heapMax` / `threadCount` / `uptimeMs` come from
    ///   `OperatingSystemMXBean` and friends.
    /// - `mspt` is the most recent server-tick duration in milliseconds.
    /// - `tps` is the effective ticks-per-second derived from `mspt` (capped at the target rate).
    /// - `playerCount` is the live online roster size.
    record Metrics(long ts, double processCpu,
                   long heapUsed, long heapMax,
                   int threadCount, long uptimeMs,
                   double mspt, double tps,
                   int playerCount) implements ControlPacket {}

    /// Send a chat message to every player. Web → game.
    record Broadcast(Component message) implements ControlPacket {}

    /// Kick a player by uuid with a reason. Web → game. The reason is shown as the client-side
    /// disconnect message.
    record Kick(java.util.UUID target, String reason) implements ControlPacket {}

    /// Global server NBT — server-wide state that doesn't belong to any single player (event
    /// id, season number, active modifiers, etc.). Bidirectional. Queryable through `global.*`
    /// paths in MQL and expressions.
    record ServerData(CompoundBinaryTag data) implements ControlPacket {}
}
