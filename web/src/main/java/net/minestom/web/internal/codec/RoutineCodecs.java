package net.minestom.web.internal.codec;

import com.google.gson.JsonObject;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.web.Action;
import net.minestom.web.RegisteredRoutine;
import net.minestom.web.Routine;
import net.minestom.web.internal.http.PacketCatalog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/// [StructCodec] unions for the routine editor ([Routine.Trigger], [Action]).
///
/// Wire shape (camelCase discriminators, shared {@code packet} field for class names):
/// <pre>{@code
/// {"type":"onMatch"}
/// {"type":"onPacket","packet":"ClientChatPacket"}
/// {"type":"inject","packet":"...","fields":{...}}
/// {"type":"ref","id":"<uuid>"}
/// }</pre>
public final class RoutineCodecs {

    private static final StructCodec<Routine.Trigger.OnMatch> TRIGGER_ON_MATCH_CODEC =
            StructCodec.struct(Routine.Trigger.OnMatch::new);
    private static final StructCodec<Routine.Trigger.OnUnmatch> TRIGGER_ON_UNMATCH_CODEC =
            StructCodec.struct(Routine.Trigger.OnUnmatch::new);
    private static final StructCodec<Routine.Trigger.OnPacket> TRIGGER_ON_PACKET_CODEC = StructCodec.struct(
            "packet", Codec.STRING, trigger -> trigger.packetClass().getSimpleName(),
            RoutineCodecs::decodeOnPacket);
    private static final StructCodec<Routine.Trigger.Interval> TRIGGER_INTERVAL_CODEC = StructCodec.struct(
            "millis", Codec.LONG, Routine.Trigger.Interval::millis,
            Routine.Trigger.Interval::new);

    public static final StructCodec<Routine.Trigger> TRIGGER = Codec.STRING.unionType("type",
            type -> switch (type) {
                case "onMatch" -> TRIGGER_ON_MATCH_CODEC;
                case "onUnmatch" -> TRIGGER_ON_UNMATCH_CODEC;
                case "onPacket" -> TRIGGER_ON_PACKET_CODEC;
                case "interval" -> TRIGGER_INTERVAL_CODEC;
                default -> null;
            },
            trigger -> switch (trigger) {
                case Routine.Trigger.OnMatch _ -> "onMatch";
                case Routine.Trigger.OnUnmatch _ -> "onUnmatch";
                case Routine.Trigger.OnPacket _ -> "onPacket";
                case Routine.Trigger.Interval _ -> "interval";
            });

    private record ActionRef(UUID id) {}

    private static final StructCodec<ActionRef> ACTION_REF_CODEC = StructCodec.struct(
            "id", Codec.UUID_STRING, ActionRef::id,
            ActionRef::new);

    @SuppressWarnings("unchecked")
    private static final StructCodec<Action>[] ACTION_SLOT = (StructCodec<Action>[]) new StructCodec<?>[1];

    public static final StructCodec<Action> ACTION;

    static {
        StructCodec<Action.Inject> inject = StructCodec.struct(
                "packet", Codec.STRING, Action.Inject::className,
                "fields", PatchValue.STRING_MAP.optional(Map.of()), Action.Inject::fields,
                Action.Inject::new);
        StructCodec<Action.Chat> chat = StructCodec.struct(
                "component", WebCodecs.EXPRESSION_OR_COMPONENT, Action.Chat::component,
                Action.Chat::new);
        StructCodec<Action.SetCustom> setCustom = StructCodec.struct(
                "key", Codec.STRING, Action.SetCustom::key,
                "value", Codec.STRING, Action.SetCustom::value,
                Action.SetCustom::new);
        StructCodec<Action.Move> move = StructCodec.struct(
                "address", Codec.STRING, Action.Move::address,
                Action.Move::new);
        StructCodec<Action.Sequence> sequence = StructCodec.struct(
                "actions", Codec.ForwardRef(() -> ACTION_SLOT[0]).list(), Action.Sequence::actions,
                Action.Sequence::new);

        ACTION_SLOT[0] = ACTION = Codec.STRING.unionType("type",
                type -> switch (type) {
                    case "inject" -> inject;
                    case "chat" -> chat;
                    case "setCustom" -> setCustom;
                    case "move" -> move;
                    case "sequence" -> sequence;
                    default -> null;
                },
                action -> switch (action) {
                    case Action.Inject _ -> "inject";
                    case Action.Chat _ -> "chat";
                    case Action.SetCustom _ -> "setCustom";
                    case Action.Move _ -> "move";
                    case Action.Sequence _ -> "sequence";
                });
    }

    public static Routine.Trigger decodeTrigger(JsonObject obj) {
        if (obj == null) return new Routine.Trigger.OnMatch();
        return WebJson.decode(TRIGGER, obj);
    }

    private static Routine.Trigger.OnPacket decodeOnPacket(String className) {
        if (className == null || className.isBlank()) throw new IllegalArgumentException("packet required");
        try {
            return new Routine.Trigger.OnPacket(PacketCatalog.packetClass(className.trim()));
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException("unknown packet class: " + className, e);
        }
    }

    public static Action decodeAction(JsonObject obj, Function<UUID, Action> resolveRef) {
        if (obj == null) throw new IllegalArgumentException("missing action");
        if ("ref".equals(obj.has("type") ? obj.get("type").getAsString() : null)) {
            ActionRef ref = WebJson.decode(ACTION_REF_CODEC, obj);
            return resolveRef.apply(ref.id());
        }
        return WebJson.decode(ACTION, obj);
    }

    /// Wire shape for a single routine. Trigger / Action go through their registered Gson
    /// hierarchy adapters in [net.minestom.web.internal.http.JsonSerialization] so callers can
    /// hand this directly to Gson.
    public static Map<String, Object> routineJson(RegisteredRoutine registered) {
        Routine r = registered.routine();
        var out = new LinkedHashMap<String, Object>();
        out.put("id", r.id().toString());
        out.put("name", r.name());
        out.put("ql", r.ql().source());
        out.put("trigger", r.trigger());
        out.put("action", r.action());
        out.put("debounceMs", r.debounceMs());
        out.put("enabled", registered.enabled());
        return out;
    }

    public static List<Map<String, Object>> routinesJson(java.util.Collection<RegisteredRoutine> routines) {
        return routines.stream().map(RoutineCodecs::routineJson).toList();
    }

    private RoutineCodecs() {}
}
