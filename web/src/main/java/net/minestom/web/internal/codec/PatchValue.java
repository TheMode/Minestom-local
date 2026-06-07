package net.minestom.web.internal.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.Result;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.codec.TranscoderProxy;
import net.minestom.server.codec.Codec.RawValue;
import net.minestom.server.item.ItemStack;
import net.minestom.web.PlayerState;
import net.minestom.web.Provenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/// Encodes heterogeneous `StatePatch` / snapshot values through typed [Codec]s, falling back
/// to the Java transcoder for plain collections and scalars Gson used to accept.
public final class PatchValue {
    private static final Logger LOGGER = LoggerFactory.getLogger(PatchValue.class);
    private static final Set<Class<?>> WARNED_RAW_TYPES = ConcurrentHashMap.newKeySet();
    public static final Codec<Map<String, Object>> STRING_MAP = new Codec<>() {
        @Override
        public <D> Result<D> encode(Transcoder<D> coder, Map<String, Object> value) {
            return CODEC.encode(coder, value);
        }

        @Override
        public <D> Result<Map<String, Object>> decode(Transcoder<D> coder, D value) {
            Result<Transcoder.MapLike<D>> mapResult = coder.getMap(value);
            if (!(mapResult instanceof Result.Ok<Transcoder.MapLike<D>>(Transcoder.MapLike<D> map))) return mapResult.cast();
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            for (String key : map.keys()) {
                Result<D> raw = map.getValue(key);
                if (!(raw instanceof Result.Ok<D>(D item))) return raw.cast();
                Result<Object> decoded = CODEC.decode(coder, item);
                if (!(decoded instanceof Result.Ok<Object>(Object object))) return decoded.cast();
                out.put(key, object);
            }
            return new Result.Ok<>(out);
        }
    };

    public static final Codec<Object> CODEC = new Codec<>() {
        @Override
        public <D> Result<D> encode(Transcoder<D> coder, Object value) {
            return switch (value) {
                case null -> new Result.Ok<>(coder.createNull());
                case List<?> list -> encodeList(coder, list);
                case Map<?, ?> map when isStringKeyed(map) -> {
                    @SuppressWarnings("unchecked")
                    Map<String, ?> stringMap = (Map<String, ?>) map;
                    yield encodeStringMap(coder, stringMap);
                }
                default -> {
                    Codec<?> typed = codecFor(value);
                    yield typed != null ? encodeTyped(coder, typed, value) : encodeRaw(coder, value);
                }
            };
        }

        @SuppressWarnings("unchecked")
        private <D, T> Result<D> encodeTyped(Transcoder<D> coder, Codec<?> typed, Object value) {
            return ((Codec<T>) typed).encode(coder, (T) value);
        }

        private <D> Result<D> encodeRaw(Transcoder<D> coder, Object value) {
            // Plain scalars/collections legitimately land here, but a web-owned type reaching the
            // raw transcoder means a typed codec is missing from codecFor — it would ship a wrong
            // (Java-shaped) value silently. Surface it once per offending class.
            if (value != null && value.getClass().getName().startsWith("net.minestom.web")
                    && WARNED_RAW_TYPES.add(value.getClass())) {
                LOGGER.warn("no typed codec for {}; falling back to raw transcoder (wire shape may be wrong)",
                        value.getClass().getName());
            }
            return Codec.RAW_VALUE.encode(coder, RawValue.of(Transcoder.JAVA, value))
                    .map(boxed -> boxed instanceof RawValue raw
                            ? raw.convertTo(coder) : new Result.Error<>("raw transcoder did not box a RawValue"));
        }

        private <D> Result<D> encodeList(Transcoder<D> coder, List<?> list) {
            Transcoder.ListBuilder<D> builder = coder.createList(list.size());
            for (Object element : list) {
                Result<D> encoded = encode(coder, element);
                if (!(encoded instanceof Result.Ok<D>(D item))) return encoded;
                builder.add(item);
            }
            return new Result.Ok<>(builder.build());
        }

        private <D> Result<D> encodeStringMap(Transcoder<D> coder, Map<String, ?> map) {
            if (TranscoderProxy.extractDelegate(coder) == Transcoder.JSON) {
                JsonObject object = new JsonObject();
                for (Map.Entry<String, ?> entry : map.entrySet()) {
                    Result<D> encoded = encode(coder, entry.getValue());
                    if (!(encoded instanceof Result.Ok<D>(D item))) return encoded;
                    object.add(entry.getKey(), (JsonElement) item);
                }
                @SuppressWarnings("unchecked")
                D boxed = (D) object;
                return new Result.Ok<>(boxed);
            }
            Transcoder.MapBuilder<D> builder = coder.createMap();
            for (Map.Entry<String, ?> entry : map.entrySet()) {
                Result<D> encoded = encode(coder, entry.getValue());
                if (!(encoded instanceof Result.Ok<D>(D item))) return encoded;
                builder.put(entry.getKey(), item);
            }
            return new Result.Ok<>(builder.build());
        }

        @Override
        public <D> Result<Object> decode(Transcoder<D> coder, D value) {
            return Codec.RAW_VALUE.decode(coder, value)
                    .map(raw -> raw.convertTo(Transcoder.JAVA).mapResult(PatchValue::nullifyOptional));
        }
    };

    private PatchValue() {}

    private static boolean isStringKeyed(Map<?, ?> map) {
        for (Object key : map.keySet()) {
            if (key != null && !(key instanceof String)) return false;
        }
        return true;
    }

    private static Object nullifyOptional(Object value) {
        if (value instanceof java.util.Optional<?> optional) return optional.orElse(null);
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) out.add(nullifyOptional(item));
            return out;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) out.put(key, nullifyOptional(entry.getValue()));
            }
            return out;
        }
        return value;
    }

    private static Codec<?> codecFor(Object value) {
        return switch (value) {
            case Boolean b -> Codec.BOOLEAN;
            case Byte b -> Codec.BYTE;
            case Short s -> Codec.SHORT;
            case Integer i -> Codec.INT;
            case Long l -> Codec.LONG;
            case Float f -> Codec.FLOAT;
            case Double d -> Codec.DOUBLE;
            case String s -> Codec.STRING;
            case UUID u -> Codec.UUID_STRING;
            case ItemStack stack -> ItemStack.CODEC;
            case Component c -> Codec.COMPONENT;
            case BinaryTag tag -> Codec.NBT;
            case Provenance p -> WebCodecs.PROVENANCE;
            case PlayerState.ActiveEffect e -> WebCodecs.ACTIVE_EFFECT;
            case PlayerState.OpenedWindow w -> WebCodecs.OPENED_WINDOW;
            case PlayerState.ClickEvent c -> WebCodecs.CLICK_EVENT;
            case PlayerState.ChatLine c -> WebCodecs.CHAT_LINE;
            case PlayerState.SentChatLine s -> WebCodecs.SENT_CHAT_LINE;
            case PlayerState.BossBarSnapshot b -> WebCodecs.BOSS_BAR;
            case PlayerState.ScoreboardSnapshot s -> WebCodecs.SCOREBOARD;
            case PlayerState.TabListSnapshot t -> WebCodecs.TAB_LIST;
            case PlayerState.DamageEvent d -> WebCodecs.DAMAGE_EVENT;
            case PlayerState.VisibleEntityShort e -> WebCodecs.VISIBLE_ENTITY_SHORT;
            default -> null;
        };
    }

    public static List<PlayerState.VisibleEntityShort> visibleEntities(PlayerState p) {
        List<PlayerState.VisibleEntityShort> out = new ArrayList<>(p.visibleEntities.size());
        for (PlayerState.VisibleEntity e : p.visibleEntities.values()) {
            out.add(PlayerState.VisibleEntityShort.from(e));
        }
        return out;
    }

    static Map<String, PlayerState.BossBarSnapshot> bossBars(PlayerState p) {
        Map<String, PlayerState.BossBarSnapshot> out = new LinkedHashMap<>(p.bossBars.size());
        for (var e : p.bossBars.entrySet()) out.put(String.valueOf(e.getKey()), e.getValue());
        return out;
    }
}
