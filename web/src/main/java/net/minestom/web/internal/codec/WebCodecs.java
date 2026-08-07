package net.minestom.web.internal.codec;

import com.google.gson.JsonElement;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Result;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.item.ItemStack;
import net.minestom.web.internal.expression.ExprValue;
import net.minestom.server.network.ConnectionState;
import net.minestom.web.*;
import net.minestom.web.internal.http.MetricsSampler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// [StructCodec] definitions for dashboard REST + WebSocket payloads. Encode/decode through
/// [WebJson] and [Transcoder#JSON] — no hand-built Gson trees for these types.
public final class WebCodecs {

    public static final Codec<Object> EXPRESSION_OR_COMPONENT = new Codec<>() {
        @Override
        public <D> Result<D> encode(Transcoder<D> coder, Object value) {
            return value instanceof Component c
                    ? Codec.COMPONENT.encode(coder, c)
                    : Codec.STRING.encode(coder, String.valueOf(value));
        }

        @Override
        public <D> Result<Object> decode(Transcoder<D> coder, D value) {
            if (value instanceof JsonElement el && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return new Result.Ok<>(el.getAsString());
            }
            Result<Component> component = Codec.COMPONENT.decode(coder, value);
            if (component instanceof Result.Ok<Component>(Component c)) return new Result.Ok<>(c);
            return Codec.STRING.decode(coder, value).mapResult(s -> (Object) s);
        }
    };

    public static final Codec<List<ItemStack>> OPTIONAL_ITEM_STACK_LIST = ItemStack.CODEC.optional().list();

    public static final Codec<ItemStack[]> ITEM_STACK_ARRAY = OPTIONAL_ITEM_STACK_LIST.transform(
            list -> list.toArray(ItemStack[]::new),
            WebCodecs::itemStackList);

    public static final Codec<Direction> DIRECTION = enumName(Direction.class);
    public static final Codec<ConnectionState> CONNECTION_STATE = enumName(ConnectionState.class);
    public static final Codec<LifecycleEvent.Kind> LIFECYCLE_KIND = enumName(LifecycleEvent.Kind.class);

    public static final StructCodec<PlayerState.Traffic> TRAFFIC = StructCodec.struct(
            "compressionThreshold", Codec.INT, traffic -> traffic.compressionThreshold,
            "pingMs", Codec.LONG, traffic -> traffic.pingMs,
            "bytesIn", Codec.LONG, traffic -> traffic.bytesIn,
            "bytesOut", Codec.LONG, traffic -> traffic.bytesOut,
            "packetsIn", Codec.LONG, traffic -> traffic.packetsIn,
            "packetsOut", Codec.LONG, traffic -> traffic.packetsOut,
            "pingHistory", Codec.LONG.list(), traffic -> traffic.pingHistory,
            PlayerState.Traffic::new);

    public static final StructCodec<Provenance> PROVENANCE = StructCodec.struct(
            "seq", Codec.LONG, Provenance::seq,
            "ts", Codec.LONG, Provenance::ts,
            "packetClass", Codec.STRING, Provenance::packetClass,
            "direction", DIRECTION, Provenance::direction,
            Provenance::new);

    public static final StructCodec<Provenance.Entry> PROVENANCE_ENTRY = StructCodec.struct(
            "source", PROVENANCE, Provenance.Entry::source,
            "prev", PatchValue.CODEC.optional(), Provenance.Entry::prev,
            "value", PatchValue.CODEC.optional(), Provenance.Entry::value,
            Provenance.Entry::new);

    public static final StructCodec<StatePatch.Append> STATE_APPEND = StructCodec.struct(
            "elements", PatchValue.CODEC.list(), StatePatch.Append::elements,
            "max", Codec.INT, StatePatch.Append::max,
            StatePatch.Append::new);

    public static final StructCodec<StatePatch> STATE_PATCH = StructCodec.struct(
            "seq", Codec.LONG, StatePatch::seq,
            "ts", Codec.LONG, StatePatch::ts,
            "values", PatchValue.STRING_MAP, StatePatch::values,
            "appends", Codec.STRING.mapValue(STATE_APPEND), StatePatch::appends,
            "provenance", Codec.STRING.mapValue(PROVENANCE), StatePatch::provenance,
            StatePatch::new);

    public static final StructCodec<PacketEvent> PACKET_EVENT = StructCodec.struct(
            "seq", Codec.LONG, PacketEvent::seq,
            "ts", Codec.LONG, PacketEvent::ts,
            "direction", DIRECTION, PacketEvent::direction,
            "state", CONNECTION_STATE, PacketEvent::state,
            "className", Codec.STRING, PacketEvent::className,
            "sizeBytes", Codec.INT, PacketEvent::sizeBytes,
            "subject", Codec.STRING, PacketEvent::subject,
            "subjectLabel", Codec.STRING, PacketEvent::subjectLabel,
            "subjectGroup", Codec.STRING, PacketEvent::subjectGroup,
            "ioEventSeq", Codec.LONG, PacketEvent::ioEventSeq,
            PacketEvent::new);

    public static final StructCodec<LifecycleEvent> LIFECYCLE_EVENT = StructCodec.struct(
            "seq", Codec.LONG, LifecycleEvent::seq,
            "ts", Codec.LONG, LifecycleEvent::ts,
            "packetSeq", Codec.LONG, LifecycleEvent::packetSeq,
            "kind", LIFECYCLE_KIND, LifecycleEvent::kind,
            "data", WebJson.ELEMENT, LifecycleEvent::data,
            LifecycleEvent::new);

    public static final StructCodec<ControlPacket.ConsoleLine> CONSOLE_LINE = StructCodec.struct(
            "ts", Codec.LONG, ControlPacket.ConsoleLine::ts,
            "level", Codec.STRING, ControlPacket.ConsoleLine::level,
            "message", Codec.STRING, ControlPacket.ConsoleLine::message,
            ControlPacket.ConsoleLine::new);

    public static final StructCodec<ControlPacket.Metrics> CONTROL_METRICS = StructCodec.struct(
            "ts", Codec.LONG, ControlPacket.Metrics::ts,
            "processCpu", Codec.DOUBLE, ControlPacket.Metrics::processCpu,
            "heapUsed", Codec.LONG, ControlPacket.Metrics::heapUsed,
            "heapMax", Codec.LONG, ControlPacket.Metrics::heapMax,
            "threadCount", Codec.INT, ControlPacket.Metrics::threadCount,
            "uptimeMs", Codec.LONG, ControlPacket.Metrics::uptimeMs,
            "mspt", Codec.DOUBLE, ControlPacket.Metrics::mspt,
            "tps", Codec.DOUBLE, ControlPacket.Metrics::tps,
            "playerCount", Codec.INT, ControlPacket.Metrics::playerCount,
            ControlPacket.Metrics::new);

    public static final StructCodec<MetricsSampler.Sample> METRICS_SAMPLE = StructCodec.struct(
            "ts", Codec.LONG, MetricsSampler.Sample::ts,
            "bytesIn", Codec.LONG, MetricsSampler.Sample::bytesIn,
            "bytesOut", Codec.LONG, MetricsSampler.Sample::bytesOut,
            "packetsIn", Codec.LONG, MetricsSampler.Sample::packetsIn,
            "packetsOut", Codec.LONG, MetricsSampler.Sample::packetsOut,
            "connections", Codec.INT, MetricsSampler.Sample::connections,
            MetricsSampler.Sample::new);

    public static final StructCodec<PlayerState.VisibleEntityShort> VISIBLE_ENTITY_SHORT =
            StructCodec.struct(
                    "id", Codec.INT, PlayerState.VisibleEntityShort::id,
                    "uuid", Codec.UUID_STRING.optional(), PlayerState.VisibleEntityShort::uuid,
                    "type", Codec.STRING, PlayerState.VisibleEntityShort::type,
                    "group", Codec.STRING, PlayerState.VisibleEntityShort::group,
                    "x", Codec.DOUBLE, PlayerState.VisibleEntityShort::x,
                    "y", Codec.DOUBLE, PlayerState.VisibleEntityShort::y,
                    "z", Codec.DOUBLE, PlayerState.VisibleEntityShort::z,
                    "yaw", Codec.FLOAT, PlayerState.VisibleEntityShort::yaw,
                    PlayerState.VisibleEntityShort::new);

    public static final StructCodec<PlayerState.ActiveEffect> ACTIVE_EFFECT = StructCodec.struct(
            "id", Codec.STRING, PlayerState.ActiveEffect::id,
            "amplifier", Codec.INT, PlayerState.ActiveEffect::amplifier,
            "durationTicks", Codec.INT, PlayerState.ActiveEffect::durationTicks,
            "ambient", Codec.BOOLEAN, PlayerState.ActiveEffect::ambient,
            "particles", Codec.BOOLEAN, PlayerState.ActiveEffect::particles,
            PlayerState.ActiveEffect::new);

    public static final StructCodec<PlayerState.ClickEvent> CLICK_EVENT = StructCodec.struct(
            "seq", Codec.LONG, PlayerState.ClickEvent::seq,
            "ts", Codec.LONG, PlayerState.ClickEvent::ts,
            "windowId", Codec.INT, PlayerState.ClickEvent::windowId,
            "rawSlot", Codec.INT, PlayerState.ClickEvent::rawSlot,
            "kind", Codec.STRING, PlayerState.ClickEvent::kind,
            "localSlot", Codec.INT, PlayerState.ClickEvent::localSlot,
            "button", Codec.INT, PlayerState.ClickEvent::button,
            "clickType", Codec.STRING, PlayerState.ClickEvent::clickType,
            PlayerState.ClickEvent::new);

    public static final StructCodec<PlayerState.ChatLine> CHAT_LINE = StructCodec.struct(
            "ts", Codec.LONG, PlayerState.ChatLine::ts,
            "sender", Codec.STRING.optional(), PlayerState.ChatLine::sender,
            "content", Codec.COMPONENT, PlayerState.ChatLine::content,
            "style", Codec.STRING.optional(), PlayerState.ChatLine::style,
            PlayerState.ChatLine::new);

    public static final StructCodec<PlayerState.SentChatLine> SENT_CHAT_LINE = StructCodec.struct(
            "ts", Codec.LONG, PlayerState.SentChatLine::ts,
            "kind", Codec.STRING, PlayerState.SentChatLine::kind,
            "text", Codec.STRING, PlayerState.SentChatLine::text,
            PlayerState.SentChatLine::new);

    public static final StructCodec<PlayerState.BossBarSnapshot> BOSS_BAR = StructCodec.struct(
            "title", Codec.COMPONENT.optional(), PlayerState.BossBarSnapshot::title,
            "progress", Codec.FLOAT, PlayerState.BossBarSnapshot::progress,
            "color", Codec.STRING, PlayerState.BossBarSnapshot::color,
            "division", Codec.STRING, PlayerState.BossBarSnapshot::division,
            "flags", Codec.INT, PlayerState.BossBarSnapshot::flags,
            PlayerState.BossBarSnapshot::new);

    public static final StructCodec<PlayerState.NumberFormat> SCOREBOARD_NUMBER_FORMAT = StructCodec.struct(
            "format", Codec.STRING, PlayerState.NumberFormat::format,
            "content", Codec.COMPONENT.optional(), PlayerState.NumberFormat::content,
            PlayerState.NumberFormat::new);

    public static final StructCodec<PlayerState.ScoreboardRow> SCOREBOARD_ROW = StructCodec.struct(
            "score", Codec.INT, PlayerState.ScoreboardRow::score,
            "display", Codec.COMPONENT.optional(), PlayerState.ScoreboardRow::display,
            "numberFormat", SCOREBOARD_NUMBER_FORMAT.optional(), PlayerState.ScoreboardRow::numberFormat,
            PlayerState.ScoreboardRow::new);

    public static final StructCodec<PlayerState.ScoreboardSnapshot> SCOREBOARD = StructCodec.struct(
            "objectiveName", Codec.STRING.optional(), PlayerState.ScoreboardSnapshot::objectiveName,
            "displayName", Codec.COMPONENT.optional(), PlayerState.ScoreboardSnapshot::displayName,
            "slot", Codec.STRING.optional(), PlayerState.ScoreboardSnapshot::slot,
            "rows", Codec.STRING.mapValue(SCOREBOARD_ROW), PlayerState.ScoreboardSnapshot::rows,
            PlayerState.ScoreboardSnapshot::new);

    public static final StructCodec<PlayerState.TabListSnapshot> TAB_LIST = StructCodec.struct(
            "header", Codec.COMPONENT.optional(), PlayerState.TabListSnapshot::header,
            "footer", Codec.COMPONENT.optional(), PlayerState.TabListSnapshot::footer,
            PlayerState.TabListSnapshot::new);

    public static final StructCodec<PlayerState.DamageEvent> DAMAGE_EVENT = StructCodec.struct(
            "ts", Codec.LONG, PlayerState.DamageEvent::ts,
            "amount", Codec.DOUBLE, PlayerState.DamageEvent::amount,
            "source", Codec.STRING.optional(), PlayerState.DamageEvent::source,
            "attackerId", Codec.INT.optional(), PlayerState.DamageEvent::attackerId,
            PlayerState.DamageEvent::new);

    public static final StructCodec<PlayerState.OpenedWindow> OPENED_WINDOW = StructCodec.struct(
            "id", Codec.INT, PlayerState.OpenedWindow::id,
            "type", Codec.STRING, PlayerState.OpenedWindow::type,
            "title", Codec.COMPONENT.optional(), PlayerState.OpenedWindow::title,
            "slots", ITEM_STACK_ARRAY, PlayerState.OpenedWindow::slots,
            "properties", Codec.STRING.mapValue(Codec.INT), PlayerState.OpenedWindow::properties,
            PlayerState.OpenedWindow::new);

    public static final StructCodec<Throttle> THROTTLE = StructCodec.struct(
            "latencyMs", Codec.INT.optional(0), Throttle::latencyMs,
            "jitterMs", Codec.INT.optional(0), Throttle::jitterMs,
            "bandwidthBytesPerSec", Codec.LONG.optional(0L), Throttle::bandwidthBytesPerSec,
            "direction", DIRECTION.optional(), Throttle::direction,
            Throttle::new);

    public static final Codec<Throttle> THROTTLE_OPTIONAL = THROTTLE.optional();

    public static final StructCodec<WebPayloads.ThrottlesSnapshot> THROTTLES_SNAPSHOT = StructCodec.struct(
            "global", THROTTLE_OPTIONAL, WebPayloads.ThrottlesSnapshot::global,
            "players", Codec.UUID_STRING.mapValue(THROTTLE), WebPayloads.ThrottlesSnapshot::players,
            WebPayloads.ThrottlesSnapshot::new);

    public static final StructCodec<WebPayloads.ScopeSummary> SCOPE_SUMMARY = StructCodec.struct(
            "id", Codec.STRING, WebPayloads.ScopeSummary::id,
            "label", Codec.STRING, WebPayloads.ScopeSummary::label,
            "replay", Codec.BOOLEAN, WebPayloads.ScopeSummary::replay,
            "createdAt", Codec.LONG, WebPayloads.ScopeSummary::createdAt,
            "connectionCount", Codec.INT, WebPayloads.ScopeSummary::connectionCount,
            "status", Codec.STRING.optional(), WebPayloads.ScopeSummary::status,
            "error", Codec.STRING.optional(), WebPayloads.ScopeSummary::error,
            "endedAt", Codec.LONG.optional(), WebPayloads.ScopeSummary::endedAt,
            WebPayloads.ScopeSummary::new);

    public static final Codec<List<WebPayloads.ScopeSummary>> SCOPE_SUMMARY_LIST = SCOPE_SUMMARY.list();

    public static final StructCodec<WebPayloads.ServerInfo> SERVER_INFO = StructCodec.struct(
            "startedAt", Codec.LONG, WebPayloads.ServerInfo::startedAt,
            "connectionCount", Codec.INT, WebPayloads.ServerInfo::connectionCount,
            "history", METRICS_SAMPLE.list(), WebPayloads.ServerInfo::history,
            WebPayloads.ServerInfo::new);

    public static final StructCodec<WebPayloads.ModePayload> MODE_PAYLOAD = StructCodec.struct(
            "mode", Codec.STRING, WebPayloads.ModePayload::mode,
            "scope", SCOPE_SUMMARY.optional(), WebPayloads.ModePayload::scope,
            "protocolVersion", Codec.INT, WebPayloads.ModePayload::protocolVersion,
            WebPayloads.ModePayload::new);

    public static final StructCodec<WebPayloads.PersistenceInfo> PERSISTENCE_INFO = StructCodec.struct(
            "enabled", Codec.BOOLEAN, WebPayloads.PersistenceInfo::enabled,
            "protocolVersion", Codec.INT.optional(), WebPayloads.PersistenceInfo::protocolVersion,
            "sessionId", Codec.LONG.optional(), WebPayloads.PersistenceInfo::sessionId,
            "path", Codec.STRING.optional(), WebPayloads.PersistenceInfo::path,
            WebPayloads.PersistenceInfo::new);

    public static final StructCodec<WebPayloads.GlobalData> GLOBAL_DATA = StructCodec.struct(
            "data", Codec.NBT.optional(), WebPayloads.GlobalData::data,
            WebPayloads.GlobalData::new);

    public static final StructCodec<WebPayloads.MailboxRow> MAILBOX_ROW = StructCodec.struct(
            "sessionId", Codec.UUID_STRING, WebPayloads.MailboxRow::sessionId,
            "playerUuid", Codec.UUID_STRING.optional(), WebPayloads.MailboxRow::playerUuid,
            "inboxDepth", Codec.INT, WebPayloads.MailboxRow::inboxDepth,
            "streamListeners", Codec.INT, WebPayloads.MailboxRow::streamListeners,
            WebPayloads.MailboxRow::new);

    public static final Codec<List<WebPayloads.MailboxRow>> MAILBOX_ROW_LIST = MAILBOX_ROW.list();

    public static final StructCodec<WebPayloads.SubjectAggregate> SUBJECT_AGGREGATE = StructCodec.struct(
            "id", Codec.STRING, WebPayloads.SubjectAggregate::id,
            "label", Codec.STRING, WebPayloads.SubjectAggregate::label,
            "group", Codec.STRING, WebPayloads.SubjectAggregate::group,
            "count", Codec.INT, WebPayloads.SubjectAggregate::count,
            "lastTs", Codec.LONG, WebPayloads.SubjectAggregate::lastTs,
            "rate", Codec.INT, WebPayloads.SubjectAggregate::rate,
            WebPayloads.SubjectAggregate::new);

    public static final Codec<List<WebPayloads.SubjectAggregate>> SUBJECT_AGGREGATE_LIST = SUBJECT_AGGREGATE.list();

    public static final StructCodec<WebPayloads.QueryResult> QUERY_RESULT = StructCodec.struct(
            "matches", Codec.STRING.list(), WebPayloads.QueryResult::matches,
            WebPayloads.QueryResult::new);

    public static final StructCodec<WebPayloads.TriggerResult> TRIGGER_RESULT = StructCodec.struct(
            "matched", Codec.INT, WebPayloads.TriggerResult::matched,
            "fired", Codec.INT, WebPayloads.TriggerResult::fired,
            "errors", Codec.STRING.list(), WebPayloads.TriggerResult::errors,
            WebPayloads.TriggerResult::new);

    public static final Codec<List<String>> STRING_LIST = Codec.STRING.list();

    public static final StructCodec<WebPayloads.PlayersSummaryTraffic> PLAYERS_SUMMARY_TRAFFIC = StructCodec.struct(
            "pingMs", Codec.LONG, WebPayloads.PlayersSummaryTraffic::pingMs,
            WebPayloads.PlayersSummaryTraffic::new);

    public static final StructCodec<WebPayloads.PlayersSummaryRow> PLAYERS_SUMMARY_ROW = StructCodec.struct(
            "uuid", Codec.UUID_STRING, WebPayloads.PlayersSummaryRow::uuid,
            "username", Codec.STRING.optional(), WebPayloads.PlayersSummaryRow::username,
            "disconnectedAt", Codec.LONG, WebPayloads.PlayersSummaryRow::disconnectedAt,
            "health", Codec.FLOAT, WebPayloads.PlayersSummaryRow::health,
            "maxHealth", Codec.FLOAT, WebPayloads.PlayersSummaryRow::maxHealth,
            "traffic", PLAYERS_SUMMARY_TRAFFIC, WebPayloads.PlayersSummaryRow::traffic,
            "gamemode", Codec.STRING.optional(), WebPayloads.PlayersSummaryRow::gamemode,
            "dimension", Codec.STRING.optional(), WebPayloads.PlayersSummaryRow::dimension,
            "serverConnectionState", Codec.STRING, WebPayloads.PlayersSummaryRow::serverConnectionState,
            "clientConnectionState", Codec.STRING, WebPayloads.PlayersSummaryRow::clientConnectionState,
            WebPayloads.PlayersSummaryRow::new);

    public static final StructCodec<WebPayloads.PlayersSummaryPayload> PLAYERS_SUMMARY = StructCodec.struct(
            "players", PLAYERS_SUMMARY_ROW.list(), WebPayloads.PlayersSummaryPayload::players,
            WebPayloads.PlayersSummaryPayload::new);

    public static final StructCodec<WebPayloads.PlayerPacketEvent> PLAYER_PACKET_EVENT = StructCodec.struct(
            "uuid", Codec.UUID_STRING, WebPayloads.PlayerPacketEvent::uuid,
            "connectionId", Codec.UUID_STRING.optional(), WebPayloads.PlayerPacketEvent::connectionId,
            "username", Codec.STRING.optional(), WebPayloads.PlayerPacketEvent::username,
            "seq", Codec.LONG, WebPayloads.PlayerPacketEvent::seq,
            "ts", Codec.LONG, WebPayloads.PlayerPacketEvent::ts,
            "direction", DIRECTION, WebPayloads.PlayerPacketEvent::direction,
            "state", CONNECTION_STATE, WebPayloads.PlayerPacketEvent::state,
            "className", Codec.STRING, WebPayloads.PlayerPacketEvent::className,
            "sizeBytes", Codec.INT, WebPayloads.PlayerPacketEvent::sizeBytes,
            "subject", Codec.STRING, WebPayloads.PlayerPacketEvent::subject,
            "subjectLabel", Codec.STRING, WebPayloads.PlayerPacketEvent::subjectLabel,
            "subjectGroup", Codec.STRING, WebPayloads.PlayerPacketEvent::subjectGroup,
            "ioEventSeq", Codec.LONG, WebPayloads.PlayerPacketEvent::ioEventSeq,
            WebPayloads.PlayerPacketEvent::new);

    public static final StructCodec<WebPayloads.PacketsAggregate> PACKETS_AGGREGATE = StructCodec.struct(
            "rows", PLAYER_PACKET_EVENT.list(), WebPayloads.PacketsAggregate::rows,
            WebPayloads.PacketsAggregate::new);

    public static final StructCodec<WebPayloads.PlayersRosterEvent> PLAYERS_ROSTER_EVENT = StructCodec.struct(
            "event", Codec.STRING, WebPayloads.PlayersRosterEvent::event,
            "uuid", Codec.UUID_STRING, WebPayloads.PlayersRosterEvent::uuid,
            "player", WebJson.ELEMENT.optional(), WebPayloads.PlayersRosterEvent::player,
            WebPayloads.PlayersRosterEvent::new);

    public static final Codec<List<PacketEvent>> PACKET_EVENT_LIST = PACKET_EVENT.list();

    public static final Codec<List<LifecycleEvent>> LIFECYCLE_EVENT_LIST = LIFECYCLE_EVENT.list();

    public static final Codec<List<ControlPacket.ConsoleLine>> CONSOLE_LINE_LIST = CONSOLE_LINE.list();


    public static final StructCodec<PlayerState.EntityChange> ENTITY_CHANGE = StructCodec.struct(
            "source", PROVENANCE, PlayerState.EntityChange::source,
            "field", Codec.STRING, PlayerState.EntityChange::field,
            "prev", PatchValue.CODEC.optional(), PlayerState.EntityChange::prev,
            "value", PatchValue.CODEC, PlayerState.EntityChange::value,
            PlayerState.EntityChange::new);

    public static final StructCodec<WebPayloads.VisibleEntityDetail> VISIBLE_ENTITY_DETAIL = StructCodec.struct(
            "id", Codec.INT, WebPayloads.VisibleEntityDetail::id,
            "uuid", Codec.UUID_STRING.optional(), WebPayloads.VisibleEntityDetail::uuid,
            "type", Codec.STRING, WebPayloads.VisibleEntityDetail::type,
            "group", Codec.STRING, WebPayloads.VisibleEntityDetail::group,
            "x", Codec.DOUBLE, WebPayloads.VisibleEntityDetail::x,
            "y", Codec.DOUBLE, WebPayloads.VisibleEntityDetail::y,
            "z", Codec.DOUBLE, WebPayloads.VisibleEntityDetail::z,
            "yaw", Codec.FLOAT, WebPayloads.VisibleEntityDetail::yaw,
            "lastUpdate", Codec.LONG, WebPayloads.VisibleEntityDetail::lastUpdate,
            "spawnSeq", Codec.LONG, WebPayloads.VisibleEntityDetail::spawnSeq,
            "lastSeq", Codec.LONG, WebPayloads.VisibleEntityDetail::lastSeq,
            "packetCount", Codec.INT, WebPayloads.VisibleEntityDetail::packetCount,
            "provenance", Codec.STRING.mapValue(PROVENANCE), WebPayloads.VisibleEntityDetail::provenance,
            "changeLog", ENTITY_CHANGE.list(), WebPayloads.VisibleEntityDetail::changeLog,
            WebPayloads.VisibleEntityDetail::new);

    public static final Codec<Map<String, List<Provenance.Entry>>> PROVENANCE_HISTORY =
            Codec.STRING.mapValue(PROVENANCE_ENTRY.list());

    public static final StructCodec<WebPayloads.RegistryEntryDto> REGISTRY_ENTRY = StructCodec.struct(
            "id", Codec.STRING, WebPayloads.RegistryEntryDto::id,
            "vanilla", Codec.BOOLEAN, WebPayloads.RegistryEntryDto::vanilla,
            WebPayloads.RegistryEntryDto::new);

    public static final StructCodec<WebPayloads.RegistryDto> REGISTRY = StructCodec.struct(
            "id", Codec.STRING, WebPayloads.RegistryDto::id,
            "entries", REGISTRY_ENTRY.list(), WebPayloads.RegistryDto::entries,
            WebPayloads.RegistryDto::new);

    public static final StructCodec<WebPayloads.RegistriesPayload> REGISTRIES = StructCodec.struct(
            "registries", REGISTRY.list(), WebPayloads.RegistriesPayload::registries,
            WebPayloads.RegistriesPayload::new);

    private WebCodecs() {}

    static <E extends Enum<E>> Codec<E> enumName(Class<E> type) {
        return Codec.STRING.transform(name -> Enum.valueOf(type, name), Enum::name);
    }

    public static ItemStack nullIfAir(ItemStack stack) {
        return stack == null || stack.isAir() ? null : stack;
    }

    public static Component componentFromEval(ExprValue value) {
        return switch (value) {
            case ExprValue.Null _ -> Component.empty();
            case ExprValue.Opaque(var raw) when raw instanceof Component c -> c;
            case ExprValue.Dict _, ExprValue.Coll _ ->
                    WebJson.decode(Codec.COMPONENT, WebJson.encode(PatchValue.CODEC, value.toObject()));
            default -> Component.text(value.str());
        };
    }

    public static List<ItemStack> itemStackList(ItemStack[] source) {
        if (source == null) return null;
        var out = new ArrayList<ItemStack>(source.length);
        for (ItemStack stack : source) out.add(nullIfAir(stack));
        return out;
    }
}
