package net.minestom.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

/// One step in the per-connection life of a player: TCP accept, handshake intent, login phase,
/// compression negotiation, configuration handover, play start, disconnect. Persisted in the
/// per-connection ring buffer alongside [PacketRecord]; the dashboard renders these as a
/// timeline.
///
/// @param seq      monotonically increasing per connection (independent of packet seq)
/// @param ts       epoch millis
/// @param packetSeq the [PacketRecord#seq] this event was inferred from (or -1 if it was
///                  emitted outside the packet stream, like CONNECT / DISCONNECT)
/// @param kind     the lifecycle phase or signal — see [Kind]
/// @param data     the event payload as JSON. For packet-derived events this is the full
///                 decoded packet (same tree the `/api/connections/.../packets/{seq}` endpoint
///                 returns); for CONNECT / DISCONNECT it's a small ad-hoc object with the
///                 socket address.
public record LifecycleEvent(
        long seq,
        long ts,
        long packetSeq,
        Kind kind,
        JsonElement data
) {
    public LifecycleEvent {
        if (data == null) data = JsonNull.INSTANCE;
    }

    public enum Kind {
        /// TCP socket accepted — emitted before any packet has flowed.
        CONNECT,
        /// `ClientHandshakePacket` observed. `data` is the serialised packet.
        HANDSHAKE,
        /// First LOGIN-state packet (e.g. `ClientLoginStartPacket`).
        LOGIN_START,
        /// `SetCompressionPacket` observed.
        COMPRESSION_SET,
        /// `LoginSuccessPacket` observed.
        LOGIN_SUCCESS,
        /// Direction entered CONFIGURATION state — typically from server `LoginAcknowledged`.
        CONFIGURATION_START,
        /// `FinishConfigurationPacket` observed — direction switches to PLAY.
        CONFIGURATION_FINISH,
        /// Direction entered PLAY state.
        PLAY_START,
        /// Player moved between proxy backends as part of an in-flight journey. `data` is
        /// `{from: "<backend-id>"|null, to: "<backend-id>"}` — the previous backend is null
        /// when the connection was minted by the journey tracker without prior state.
        SERVER_SWITCH,
        /// Socket closed (either side).
        DISCONNECT
    }
}
