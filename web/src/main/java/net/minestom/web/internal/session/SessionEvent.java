package net.minestom.web.internal.session;

import com.google.gson.JsonObject;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.web.Direction;
import net.minestom.web.LifecycleEvent;
import net.minestom.web.PacketEvent;
import net.minestom.web.StatePatch;

import java.util.UUID;

public sealed interface SessionEvent {

    record Patch(StatePatch patch) implements SessionEvent {}

    record Lifecycle(LifecycleEvent event) implements SessionEvent {}

    record PacketSeen(
            Direction direction,
            ConnectionState state,
            Packet packet,
            PacketEvent timelineEvent,
            UUID playerUuid,
            UUID connectionId,
            String username
    ) implements SessionEvent {}

    record MinimapFrame(JsonObject frame) implements SessionEvent {}

    record TrafficSnapshot(
            long ts,
            long bytesIn,
            long bytesOut,
            long packetsIn,
            long packetsOut
    ) implements SessionEvent {}

    record Closed(long ts) implements SessionEvent {}
}
