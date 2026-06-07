package net.minestom.web.internal.scope;

import com.google.gson.JsonObject;
import net.minestom.web.PacketEvent;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebJsonBuilders;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.http.Topics;
import net.minestom.web.internal.persist.HistoryFile;
import net.minestom.web.internal.session.PlayerView;
import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Subscribes to every session and translates events into dashboard topic publishes. Runs on the
/// session worker thread — handlers must be cheap. Feeds the scope's `players:summary` and global
/// metrics aggregates inline (no polling); wires [Session#setActivityProbes] so the session skips
/// the expensive per-player work when no subscriber wants it.
public final class ScopeSessionBridge {
    private static final Logger LOGGER = LoggerFactory.getLogger(ScopeSessionBridge.class);

    private final DashboardScope scope;
    private final Set<UUID> joinedSessions = ConcurrentHashMap.newKeySet();

    public ScopeSessionBridge(DashboardScope scope) {
        this.scope = scope;
        scope.registry.onSessionOpen(this::onSessionOpen);
        scope.registry.onSessionClose(this::onSessionClose);
        scope.registry.onSessionEvict(this::onSessionEvicted);
        for (Session existing : scope.registry.sessions()) onSessionOpen(existing);
    }

    private void onSessionOpen(Session session) {
        if (scope.persistence != null) {
            // TcpAcceptor defers notifyOpened until after setBackendAddress + setJourneyId,
            // so by the time this listener fires the routing columns are populated.
            if (session.journeyId() != null) {
                scope.persistence.recordJourneyOpen(session.journeyId(), null, HistoryFile.nowMs());
            }
            final java.net.InetSocketAddress backend = session.backendAddress();
            final String backendLabel = backend == null ? null : backend.getHostString() + ":" + backend.getPort();
            scope.persistence.recordConnect(session.id, session.journeyId(),
                    backendLabel, session.initialAddress(), HistoryFile.nowMs());
        }
        session.setActivityProbes(() -> patchWanted(session), () -> minimapWanted(session));
        session.addListener(event -> dispatch(session, event));
    }

    private boolean patchWanted(Session session) {
        // Summary subscribers consume the same field changes as a profile viewer.
        if (scope.hasSubscriber(Topics.PLAYERS_SUMMARY)) return true;
        final UUID uuid = session.playerUuid();
        return uuid != null && scope.hasSubscriber(Topics.playerState(uuid));
    }

    private boolean minimapWanted(Session session) {
        final UUID uuid = session.playerUuid();
        return uuid != null && scope.hasSubscriber(Topics.playerMinimap(uuid));
    }

    private void onSessionClose(Session session) {
        if (scope.persistence != null) {
            scope.persistence.recordDisconnect(session.id, HistoryFile.nowMs());
        }
        if (joinedSessions.contains(session.id)) {
            publishPlayers("disconnect", session);
        }
        scope.forgetSessionTraffic(session.id);
    }

    private void onSessionEvicted(PlayerView.Retained snapshot) {
        if (joinedSessions.remove(snapshot.sessionId())) publishPlayerRemove(snapshot.uuid());
        scope.forgetSessionTraffic(snapshot.sessionId());
    }

    private void dispatch(Session session, SessionEvent event) {
        try {
            switch (event) {
                case SessionEvent.Lifecycle(var ev) -> handleLifecycle(session, ev);
                case SessionEvent.PacketSeen p -> handlePacket(session, p);
                case SessionEvent.Patch p -> handlePatch(session, p);
                case SessionEvent.MinimapFrame m -> handleMinimap(session, m);
                case SessionEvent.TrafficSnapshot t -> scope.recordSessionTraffic(session.id,
                        t.bytesIn(), t.bytesOut(), t.packetsIn(), t.packetsOut());
                case SessionEvent.Closed ignored -> { }
            }
        } catch (Throwable t) {
            LOGGER.debug("scope bridge dispatch failed: {}", t.toString());
        }
    }

    private void handleLifecycle(Session session, net.minestom.web.LifecycleEvent ev) {
        final UUID uuid = session.playerUuid();
        if (uuid == null) return;
        final String topic = Topics.playerLifecycle(uuid);
        if (!scope.hasSubscriber(topic)) return;
        scope.publish(topic, WebJson.encodeAsObject(WebCodecs.LIFECYCLE_EVENT, ev));
    }

    private void handlePacket(Session session, SessionEvent.PacketSeen ev) {
        final PacketEvent timelineEvent = ev.timelineEvent();
        if (scope.persistence != null && timelineEvent != null) {
            scope.persistence.recordPacketEvent(session.id, timelineEvent);
        }
        if (ev.playerUuid() == null) return;
        if (joinedSessions.add(session.id)) {
            // First time we know who this connection belongs to — back-fill the journey row's
            // player_uuid. The :web module never queries it; the column + idx_journey_player exist
            // for external/archive consumers that want every connection on a player's journey.
            if (scope.persistence != null && session.journeyId() != null) {
                scope.persistence.recordJourneyPlayerUuid(session.journeyId(), ev.playerUuid());
            }
            publishPlayers("add", session);
        }
        if (timelineEvent == null) return;
        // Build the wire event at most once, even when both the aggregate and per-player topics
        // are subscribed.
        final String packetsTopic = Topics.playerPackets(ev.playerUuid());
        final boolean aggregateWanted = scope.hasSubscriber(Topics.PACKETS_AGGREGATE);
        final boolean perPlayerWanted = scope.hasSubscriber(packetsTopic);
        if (!aggregateWanted && !perPlayerWanted) return;
        final WebPayloads.PlayerPacketEvent event = buildPlayerEvent(ev, timelineEvent);
        if (aggregateWanted) scope.notePacketAggregate(event);
        if (perPlayerWanted) scope.publish(packetsTopic, WebJson.encodeAsObject(WebCodecs.PLAYER_PACKET_EVENT, event));
    }

    private void handlePatch(Session session, SessionEvent.Patch ev) {
        final UUID uuid = session.playerUuid();
        if (uuid == null) return;
        // Listener fires synchronously on the owner thread — direct PlayerState read is safe.
        if (scope.hasSubscriber(Topics.PLAYERS_SUMMARY)) {
            scope.notePlayerSummary(WebPayloads.PlayersSummaryRow.from(session.playerForOwnerThread()));
        }
        final String topic = Topics.playerState(uuid);
        if (!scope.hasSubscriber(topic)) return;
        scope.publish(topic, WebJson.encodeAsObject(WebCodecs.STATE_PATCH, ev.patch(), session.jsonCoder));
    }

    private void handleMinimap(Session session, SessionEvent.MinimapFrame ev) {
        final UUID uuid = session.playerUuid();
        if (uuid == null) return;
        final String topic = Topics.playerMinimap(uuid);
        if (!scope.hasSubscriber(topic)) return;
        scope.publish(topic, ev.frame());
    }

    private WebPayloads.PlayerPacketEvent buildPlayerEvent(SessionEvent.PacketSeen ev, PacketEvent timelineEvent) {
        return new WebPayloads.PlayerPacketEvent(
                ev.playerUuid(),
                ev.connectionId(),
                ev.username(),
                timelineEvent.seq(),
                timelineEvent.ts(),
                timelineEvent.direction(),
                timelineEvent.state(),
                timelineEvent.className(),
                timelineEvent.sizeBytes(),
                timelineEvent.subject(),
                timelineEvent.subjectLabel(),
                timelineEvent.subjectGroup(),
                timelineEvent.ioEventSeq());
    }

    private void publishPlayers(String event, Session session) {
        final UUID uuid = session.playerUuid();
        if (uuid == null) return;
        final JsonObject player = "add".equals(event) || "disconnect".equals(event)
                ? session.readState(p -> WebJsonBuilders.playerStateJson(p, session.jsonCoder)) : null;
        publishRoster(new WebPayloads.PlayersRosterEvent(event, uuid, player));
    }

    private void publishPlayerRemove(UUID uuid) {
        publishRoster(new WebPayloads.PlayersRosterEvent("remove", uuid, null));
    }

    private void publishRoster(WebPayloads.PlayersRosterEvent event) {
        scope.publish(Topics.PLAYERS, WebJson.encodeAsObject(WebCodecs.PLAYERS_ROSTER_EVENT, event));
    }
}
