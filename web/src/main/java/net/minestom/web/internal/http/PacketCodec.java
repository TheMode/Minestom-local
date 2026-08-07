package net.minestom.web.internal.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.packet.Packet;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.http.PacketSchema.Field;
import net.minestom.web.internal.http.PacketSchema.Kind;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Decodes the dashboard's JSON payload back into a `Packet`, driven by the widget schema
/// reflected in [PacketSchema]. Routine execution consumes [#decode] with an optional expression
/// [Evaluator] so MQL strings turn into typed JSON in one pass.
public final class PacketCodec {

    private PacketCodec() {}

    /// Resolves a typed expression at decode time. Returns the JSON value to use, or null
    /// to leave the original element untouched.
    @FunctionalInterface
    public interface Evaluator {
        @Nullable JsonElement evaluate(String source, Kind kind);
    }

    public static Packet decode(String classNameOrSimple, JsonObject fields) throws Exception {
        return decode(classNameOrSimple, fields, null);
    }

    /// Decode a record packet from JSON. If `eval` is non-null and a field's kind is
    /// expression-valued, string leaves are first run through the evaluator.
    public static Packet decode(String classNameOrSimple, JsonObject fields, @Nullable Evaluator eval) throws Exception {
        Class<?> cls = PacketCatalog.packetClass(classNameOrSimple);
        return (Packet) decodeRecord(cls, fields, PacketSchema.schema(cls).orElse(null), eval);
    }

    private static Object decodeRecord(Class<?> cls, JsonObject fields, @Nullable List<Field> schema,
                                       @Nullable Evaluator eval) throws Exception {
        if (!cls.isRecord()) throw new IllegalArgumentException("Class is not a record: " + cls.getName());
        RecordComponent[] components = cls.getRecordComponents();
        Object[] args = new Object[components.length];
        Class<?>[] paramTypes = new Class<?>[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent c = components[i];
            paramTypes[i] = c.getType();
            JsonElement raw = fields == null ? null : fields.get(c.getName());
            Field f = schema == null ? null : schema.get(i);
            args[i] = decodeValue(maybeEvaluate(raw, f, eval), c.getType(), c.getGenericType(), f, eval);
        }
        Constructor<?> ctor = cls.getDeclaredConstructor(paramTypes);
        ctor.setAccessible(true);
        return ctor.newInstance(args);
    }

    private static @Nullable JsonElement maybeEvaluate(@Nullable JsonElement raw, @Nullable Field f,
                                                       @Nullable Evaluator eval) {
        if (eval == null || f == null) return raw;
        if (!f.kind().isExpression()) return raw;
        if (raw == null || !raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isString()) return raw;
        JsonElement evaluated = eval.evaluate(raw.getAsString(), f.kind());
        return evaluated == null ? raw : evaluated;
    }

    /// Map keys arrive as JSON object property strings. Evaluate them when the key field is
    /// expression-valued so `health` or `name + "_id"` resolves before decoding to the key type.
    private static String evaluateKey(String raw, @Nullable Field keyF, @Nullable Evaluator eval) {
        if (eval == null || keyF == null || !keyF.kind().isExpression()) return raw;
        JsonElement evaluated = eval.evaluate(raw, keyF.kind());
        if (evaluated == null || !evaluated.isJsonPrimitive()) return raw;
        return evaluated.getAsString();
    }

    private static @Nullable Object decodeValue(@Nullable JsonElement value, Class<?> type, Type generic,
                                                @Nullable Field f, @Nullable Evaluator eval) throws Exception {
        if (value == null || value.isJsonNull()) return structuralDefault(type);
        if (type == ItemStack.class) return WebJson.decode(ItemStack.CODEC, value);
        if (type == Component.class) return WebJson.decode(Codec.COMPONENT, evaluateComponentTree(value, eval));
        Kind k = PacketSchema.kindOf(type);
        if (k != null) return decodeLeaf(value, k);
        if (type.isEnum()) {
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("expected enum string for " + type.getSimpleName() + ", got " + value);
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object e = Enum.valueOf((Class<Enum>) type, value.getAsString());
            return e;
        }
        if (List.class.isAssignableFrom(type) && value.isJsonArray() && generic instanceof ParameterizedType pt) {
            Type elemT = pt.getActualTypeArguments()[0];
            Field elemF = f instanceof Field.ListF lf ? lf.element() : null;
            List<Object> out = new ArrayList<>();
            for (JsonElement el : value.getAsJsonArray()) {
                out.add(decodeValue(maybeEvaluate(el, elemF, eval), PacketSchema.rawClass(elemT), elemT, elemF, eval));
            }
            return out;
        }
        if (Map.class.isAssignableFrom(type) && value.isJsonObject() && generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            Class<?> keyCls = PacketSchema.rawClass(args[0]);
            Field keyF = f instanceof Field.MapF mf ? mf.key() : null;
            Field valF = f instanceof Field.MapF mf ? mf.value() : null;
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : value.getAsJsonObject().entrySet()) {
                out.put(decodeMapKey(evaluateKey(e.getKey(), keyF, eval), keyCls),
                        decodeValue(maybeEvaluate(e.getValue(), valF, eval), PacketSchema.rawClass(args[1]), args[1], valF, eval));
            }
            return out;
        }
        if (type.isRecord() && value.isJsonObject()) {
            List<Field> nested = PacketSchema.schema(type).orElse(null);
            return decodeRecord(type, value.getAsJsonObject(), nested, eval);
        }
        throw new IllegalArgumentException("Unsupported component type: " + type.getName());
    }

    private static Object decodeLeaf(JsonElement v, Kind k) {
        return switch (k) {
            case BYTE -> v.getAsByte();
            case SHORT -> v.getAsShort();
            case INT -> v.getAsInt();
            case LONG -> v.getAsLong();
            case FLOAT -> v.getAsFloat();
            case DOUBLE -> v.getAsDouble();
            case BOOLEAN -> v.getAsBoolean();
            case CHAR -> {
                String s = v.isJsonPrimitive() && v.getAsJsonPrimitive().isString() ? v.getAsString() : "";
                yield s.isEmpty() ? '\0' : s.charAt(0);
            }
            case STRING -> v.getAsString();
            case UUID -> java.util.UUID.fromString(v.getAsString());
            default -> throw new IllegalStateException("not a leaf kind: " + k);
        };
    }

    private static @Nullable Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        return 0.0;
    }

    /// Sensible non-null default for omitted / null fields. Record canonical constructors
    /// (and downstream packet handlers) frequently NPE on null component values, so a missing
    /// `message` Component becomes [Component#empty], a missing `itemStack` becomes
    /// [ItemStack#AIR], absent collections become empty, and enums fall back to the first constant.
    private static @Nullable Object structuralDefault(Class<?> type) {
        if (type == Component.class) return Component.empty();
        if (type == ItemStack.class) return ItemStack.AIR;
        if (List.class.isAssignableFrom(type)) return List.of();
        if (Map.class.isAssignableFrom(type)) return Map.of();
        if (type.isEnum()) {
            Object[] consts = type.getEnumConstants();
            if (consts != null && consts.length > 0) return consts[0];
        }
        return defaultFor(type);
    }

    /// Walk a Component JSON tree and run every `text` leaf (and recursively any `extra`
    /// children) through the evaluator with [Kind#STRING]. Bare identifiers like
    /// `player.name` resolve to the live value; expressions that fail to compile or eval
    /// (e.g. a plain word like `hello`) fall back to the original literal so casual text
    /// still works without quoting.
    private static JsonElement evaluateComponentTree(JsonElement v, @Nullable Evaluator eval) {
        if (eval == null || v == null) return v;
        if (v.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement child : v.getAsJsonArray()) out.add(evaluateComponentTree(child, eval));
            return out;
        }
        if (!v.isJsonObject()) return v;
        JsonObject in = v.getAsJsonObject();
        JsonObject out = new JsonObject();
        for (Map.Entry<String, JsonElement> e : in.entrySet()) {
            String key = e.getKey();
            JsonElement val = e.getValue();
            if (("text".equals(key) || "translate".equals(key) || "fallback".equals(key))
                    && val.isJsonPrimitive() && val.getAsJsonPrimitive().isString()) {
                out.add(key, tryEvalString(val.getAsString(), eval, val));
            } else if ("extra".equals(key) || "with".equals(key)) {
                out.add(key, evaluateComponentTree(val, eval));
            } else {
                out.add(key, val);
            }
        }
        return out;
    }

    private static JsonElement tryEvalString(String src, Evaluator eval, JsonElement fallback) {
        if (src.isEmpty()) return fallback;
        try {
            JsonElement r = eval.evaluate(src, Kind.STRING);
            return r != null ? r : fallback;
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static Object decodeMapKey(String raw, Class<?> keyCls) {
        Kind k = PacketSchema.kindOf(keyCls);
        if (k != null) return switch (k) {
            case STRING -> raw;
            case BYTE -> Byte.parseByte(raw);
            case SHORT -> Short.parseShort(raw);
            case INT -> Integer.parseInt(raw);
            case LONG -> Long.parseLong(raw);
            case UUID -> java.util.UUID.fromString(raw);
            default -> raw;
        };
        if (keyCls.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object e = Enum.valueOf((Class<Enum>) keyCls, raw);
            return e;
        }
        return raw;
    }
}
