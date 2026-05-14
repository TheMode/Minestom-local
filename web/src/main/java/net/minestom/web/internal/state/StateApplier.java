package net.minestom.web.internal.state;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.configuration.ClientFinishConfigurationPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginAcknowledgedPacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.server.configuration.FinishConfigurationPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.web.*;
import net.minestom.web.internal.http.JsonSerialization;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionEvent;
import net.minestom.web.internal.session.SessionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/// Applies a decoded packet on the session's owner thread: records in the ring buffer, runs
/// the dispatched updater, mirrors connection state + traffic counters, emits lifecycle events
/// for protocol-phase milestones, and publishes a [SessionEvent.PacketSeen] to the session's
/// stream.
public final class StateApplier {
    private static final Logger LOGGER = LoggerFactory.getLogger(StateApplier.class);

    @FunctionalInterface
    interface Updater<P extends Packet> {
        void apply(PlayerState state, Direction direction, ConnectionState connState, P packet);
    }

    private static final Map<Class<? extends Packet>, Updater<?>> UPDATERS;

    static {
        var map = new HashMap<Class<? extends Packet>, Updater<?>>();
        map.putAll(SessionWorldUpdaters.LISTENERS);
        map.putAll(VitalsUpdaters.LISTENERS);
        map.putAll(InventoryUpdaters.LISTENERS);
        map.putAll(ChatHudUpdaters.LISTENERS);
        map.putAll(EntityUpdaters.LISTENERS);
        map.putAll(WorldUpdaters.LISTENERS);
        UPDATERS = Map.copyOf(map);
    }

    @SuppressWarnings("unchecked")
    static <P extends Packet> Map.Entry<Class<? extends Packet>, Updater<?>> entry(Class<P> cls, Updater<P> updater) {
        return Map.entry(cls, updater);
    }

    @SafeVarargs
    static Map<Class<? extends Packet>, Updater<?>> listeners(Map.Entry<Class<? extends Packet>, Updater<?>>... entries) {
        return Map.ofEntries(entries);
    }

    /// Apply a single packet's updaters — used by unit tests that drive [PlayerState] directly.
    @SuppressWarnings("unchecked")
    public static void applyPacket(PlayerState state, Direction direction, ConnectionState connState, Packet packet) {
        var updater = UPDATERS.get(packet.getClass());
        if (updater != null) ((Updater<Packet>) updater).apply(state, direction, connState, packet);
    }

    private final SessionRegistry registry;

    public StateApplier(SessionRegistry registry) {
        this.registry = registry;
    }

    public void apply(Session session, Direction direction, ConnectionState state,
                      Packet packet, int sizeBytes, long ioEventSeq) {
        final PlayerState player = session.playerForOwnerThread();
        final ConnectionState clientStateBefore = player.clientConnectionState;
        final ConnectionState serverStateBefore = player.serverConnectionState;

        // Record first so the seq we hand to updaters matches the wire record.
        final PacketRecord record = session.packets.recordDecoded(direction, state, packet, sizeBytes, ioEventSeq);
        player.currentProvenance = new Provenance(
                record.seq(),
                System.currentTimeMillis(),
                packet.getClass().getSimpleName(),
                direction);
        try {
            applyPacket(player, direction, state, packet);
        } catch (Throwable t) {
            LOGGER.warn("state update failed for {}: {}", packet.getClass().getSimpleName(), t.toString());
        } finally {
            player.currentProvenance = null;
        }
        // Player-POV counters: SERVERBOUND = bytes/packets FROM the player.
        if (direction == Direction.SERVERBOUND) player.traffic.packetsIn++;
        else player.traffic.packetsOut++;
        // Mirror live session state for HTTP readers. The displayed threshold tracks the
        // upstream leg — that's where compression was always set previously, and any
        // independent client-leg value only differs during the brief online-mode auth.
        player.clientConnectionState = session.clientToServerState;
        player.serverConnectionState = session.serverToClientState;
        if (clientStateBefore != player.clientConnectionState) {
            player.markDirty("clientConnectionState");
        }
        if (serverStateBefore != player.serverConnectionState) {
            player.markDirty("serverConnectionState");
        }
        player.traffic.compressionThreshold = session.upstreamCompressionThreshold;

        final UUID playerUuid = session.refreshPlayerUuid();
        if (playerUuid != null) registry.markLive(session);
        recordLifecycle(session, packet, direction, record.seq(), clientStateBefore, serverStateBefore);
        // Run on-packet routines before publishing — any SetCustom side-effect must be part of
        // the same state revision the next patch will ship.
        session.evaluateRoutinesOnPacket(packet);
        session.publish(new SessionEvent.PacketSeen(
                direction, state, packet, session.packets.latestEvent(),
                player.uuid, player.connectionId, player.username));
    }

    private void recordLifecycle(Session session, Packet packet, Direction direction, long seq,
                                 ConnectionState clientBefore, ConnectionState serverBefore) {
        final LifecycleEvent.Kind kind = switch (packet) {
            case ClientHandshakePacket _ -> LifecycleEvent.Kind.HANDSHAKE;
            case ClientLoginStartPacket _ -> LifecycleEvent.Kind.LOGIN_START;
            case SetCompressionPacket _ -> LifecycleEvent.Kind.COMPRESSION_SET;
            case LoginSuccessPacket _ -> LifecycleEvent.Kind.LOGIN_SUCCESS;
            case ClientLoginAcknowledgedPacket _ -> LifecycleEvent.Kind.CONFIGURATION_START;
            case FinishConfigurationPacket _,
                 ClientFinishConfigurationPacket _ -> LifecycleEvent.Kind.CONFIGURATION_FINISH;
            default -> null;
        };
        if (kind != null) {
            emit(session, session.lifecycle.record(kind, seq, serialisePacket(packet, direction)));
            return;
        }
        // Direction-level transitions not covered by the packet matches above — the decoder has
        // already advanced session.{client,server}ToClientState; surface a single PLAY_START
        // per direction.
        if (clientBefore != ConnectionState.PLAY && session.clientToServerState == ConnectionState.PLAY) {
            emit(session, session.lifecycle.record(LifecycleEvent.Kind.PLAY_START, seq, directionJson("CLIENT_TO_SERVER")));
        }
        if (serverBefore != ConnectionState.PLAY && session.serverToClientState == ConnectionState.PLAY) {
            emit(session, session.lifecycle.record(LifecycleEvent.Kind.PLAY_START, seq, directionJson("SERVER_TO_CLIENT")));
        }
    }

    private void emit(Session session, LifecycleEvent event) {
        session.publish(new SessionEvent.Lifecycle(event));
    }

    /// JSON-ify the live packet using the same Gson adapter the per-packet REST endpoint uses;
    /// fall back to a `{ error }` payload if serialisation throws so the lifecycle entry still
    /// renders.
    private static JsonElement serialisePacket(Packet packet, Direction direction) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("className", packet.getClass().getSimpleName());
            o.addProperty("direction", direction.name());
            o.add("record", JsonSerialization.GSON.toJsonTree(packet));
            return o;
        } catch (Throwable t) {
            JsonObject o = new JsonObject();
            o.addProperty("className", packet.getClass().getSimpleName());
            o.addProperty("error", t.toString());
            return o;
        }
    }

    private static JsonObject directionJson(String direction) {
        JsonObject o = new JsonObject();
        o.addProperty("direction", direction);
        return o;
    }
}
