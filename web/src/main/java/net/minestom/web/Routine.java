package net.minestom.web;

import net.minestom.server.network.packet.Packet;

import java.util.UUID;

/// A trigger → action automation. Routines live entirely in memory; the registry vanishes on
/// proxy restart.
public record Routine(
        UUID id,
        String name,
        Query ql,
        Trigger trigger,
        Action action,
        long debounceMs
) {

    /// Discriminator for when a [Routine] should fire.
    public sealed interface Trigger {
        /// Fires whenever a player starts matching the routine query.
        record OnMatch() implements Trigger {}
        /// Fires whenever a player stops matching the routine query.
        record OnUnmatch() implements Trigger {}
        /// Fires for every decoded packet whose class equals `packetClass`.
        /// Subject to the routine's `debounceMs`.
        record OnPacket(Class<? extends Packet> packetClass) implements Trigger {}
        /// Fires every `millis` milliseconds for every matching player.
        record Interval(long millis) implements Trigger {}
    }
}
