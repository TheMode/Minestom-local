package net.minestom.web.internal.codec;

import com.google.gson.JsonElement;
import net.kyori.adventure.nbt.BinaryTag;
import net.minestom.server.network.ConnectionState;
import net.minestom.web.Direction;
import net.minestom.web.PacketEvent;
import net.minestom.web.PlayerState;
import net.minestom.web.Provenance;
import net.minestom.web.Throttle;
import net.minestom.web.internal.http.MetricsSampler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/// Wire-DTO records for dashboard REST + WebSocket payloads. Their
/// [net.minestom.server.codec.StructCodec] definitions live in [WebCodecs]; the JSON builders that
/// materialize them live in [WebJsonBuilders].
public final class WebPayloads {

    private WebPayloads() {}

    public record PlayersSummaryRow(
            UUID uuid,
            String username,
            long disconnectedAt,
            float health,
            float maxHealth,
            PlayersSummaryTraffic traffic,
            String gamemode,
            String dimension,
            String serverConnectionState,
            String clientConnectionState
    ) {
        public static PlayersSummaryRow from(PlayerState p) {
            return new PlayersSummaryRow(
                    p.uuid,
                    p.username,
                    p.disconnectedAt,
                    p.health,
                    p.maxHealth,
                    new PlayersSummaryTraffic(p.traffic.pingMs),
                    p.gamemode,
                    p.dimension,
                    String.valueOf(p.serverConnectionState),
                    String.valueOf(p.clientConnectionState));
        }
    }

    public record PlayersSummaryTraffic(long pingMs) {
    }

    public record PlayersSummaryPayload(List<PlayersSummaryRow> players) {}

    public record PlayerPacketEvent(
            UUID uuid,
            UUID connectionId,
            String username,
            long seq,
            long ts,
            Direction direction,
            ConnectionState state,
            String className,
            int sizeBytes,
            String subject,
            String subjectLabel,
            String subjectGroup,
            long ioEventSeq
    ) {
        public static PlayerPacketEvent from(PlayerState player, PacketEvent event) {
            return new PlayerPacketEvent(
                    player.uuid,
                    player.connectionId,
                    player.username,
                    event.seq(),
                    event.ts(),
                    event.direction(),
                    event.state(),
                    event.className(),
                    event.sizeBytes(),
                    event.subject(),
                    event.subjectLabel(),
                    event.subjectGroup(),
                    event.ioEventSeq());
        }
    }

    public record VisibleEntityDetail(
            int id,
            UUID uuid,
            String type,
            String group,
            double x,
            double y,
            double z,
            float yaw,
            long lastUpdate,
            long spawnSeq,
            long lastSeq,
            int packetCount,
            Map<String, Provenance> provenance,
            List<PlayerState.EntityChange> changeLog
    ) {
        static VisibleEntityDetail from(PlayerState.VisibleEntity entity) {
            return new VisibleEntityDetail(
                    entity.id,
                    entity.uuid,
                    entity.type,
                    entity.group,
                    entity.x,
                    entity.y,
                    entity.z,
                    entity.yaw,
                    entity.lastUpdate,
                    entity.spawnSeq,
                    entity.lastSeq,
                    entity.packetCount,
                    new LinkedHashMap<>(entity.provenance),
                    new ArrayList<>(entity.changeLog));
        }
    }

    public record RegistryEntryDto(String id, boolean vanilla) {}

    public record RegistryDto(String id, List<RegistryEntryDto> entries) {}

    public record RegistriesPayload(List<RegistryDto> registries) {}

    public record ScopeSummary(String id, String label, boolean replay, long createdAt, int connectionCount,
                               @org.jetbrains.annotations.Nullable String status,
                               @org.jetbrains.annotations.Nullable String error,
                               @org.jetbrains.annotations.Nullable Long endedAt) {}

    public record ServerInfo(long startedAt, int connectionCount, List<MetricsSampler.Sample> history) {}

    public record ModePayload(String mode, @org.jetbrains.annotations.Nullable ScopeSummary scope, int protocolVersion) {}

    public record PersistenceInfo(boolean enabled,
                                  @org.jetbrains.annotations.Nullable Integer protocolVersion,
                                  @org.jetbrains.annotations.Nullable Long sessionId,
                                  @org.jetbrains.annotations.Nullable String path) {}

    public record GlobalData(@org.jetbrains.annotations.Nullable BinaryTag data) {}

    public record ThrottlesSnapshot(@org.jetbrains.annotations.Nullable Throttle global, Map<UUID, Throttle> players) {}

    public record MailboxRow(UUID sessionId, @org.jetbrains.annotations.Nullable UUID playerUuid,
                             int inboxDepth, int streamListeners) {}

    public record SubjectAggregate(String id, String label, String group, int count, long lastTs, int rate) {}

    public record QueryResult(List<String> matches) {}

    public record TriggerResult(int matched, int fired, List<String> errors) {}

    public record PacketsAggregate(List<PlayerPacketEvent> rows) {}

    public record PlayersRosterEvent(String event, UUID uuid, @org.jetbrains.annotations.Nullable JsonElement player) {}
}
