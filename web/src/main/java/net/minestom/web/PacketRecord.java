package net.minestom.web;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;

/// Decoded packet detail cached for inspector reads.
///
/// @param seq         monotonically increasing per connection
/// @param ts          epoch millis at capture time
/// @param direction   CLIENTBOUND (server → client) or SERVERBOUND (client → server)
/// @param state       the connection state at decode time
/// @param className   simple record class name
/// @param sizeBytes   on-wire size
/// @param record      the decoded Java record reference (lazy-serialised to JSON on read)
public record PacketRecord(
        long seq,
        long ts,
        Direction direction,
        ConnectionState state,
        String className,
        int sizeBytes,
        Packet record
) {}
