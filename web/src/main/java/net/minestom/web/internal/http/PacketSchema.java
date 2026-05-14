package net.minestom.web.internal.http;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.minestom.server.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// Reflects a packet record into a typed widget schema. A class is *analyzable* iff every record
/// component resolves to a known [Kind] (primitive, string, uuid, enum, item, component, record,
/// list-of-analyzable, or string-keyed map-of-analyzable). [PacketCodec] consumes this model to
/// decode the dashboard's JSON payload back into a `Packet`; `/api/packet/describe/{class}`
/// consumes [#describe].
public final class PacketSchema {

    private PacketSchema() {}

    public enum Kind {
        BYTE(byte.class, Byte.class), SHORT(short.class, Short.class),
        INT(int.class, Integer.class), LONG(long.class, Long.class),
        FLOAT(float.class, Float.class), DOUBLE(double.class, Double.class),
        BOOLEAN(boolean.class, Boolean.class), CHAR(char.class, Character.class),
        STRING(String.class), UUID(java.util.UUID.class),
        ENUM, RECORD, LIST, MAP, ITEM, COMPONENT;

        final Class<?>[] classes;
        Kind(Class<?>... classes) { this.classes = classes; }

        public boolean isNumeric() {
            return this == BYTE || this == SHORT || this == INT || this == LONG
                    || this == FLOAT || this == DOUBLE;
        }

        /// Kinds whose value comes from the user as a free-form expression string. Booleans,
        /// enums, lists, maps, items, components, and records use structured widgets.
        public boolean isExpression() {
            return isNumeric() || this == CHAR || this == STRING || this == UUID;
        }

        /// Map keys must round-trip through JSON object keys (which are strings), so only
        /// scalar kinds qualify.
        public boolean canBeMapKey() {
            return this == STRING || this == UUID || this == ENUM
                    || this == BYTE || this == SHORT || this == INT || this == LONG;
        }
    }

    private static final Map<Class<?>, Kind> KIND_BY_CLASS = buildKindByClass();

    private static Map<Class<?>, Kind> buildKindByClass() {
        Map<Class<?>, Kind> m = new HashMap<>();
        for (Kind k : Kind.values()) for (Class<?> c : k.classes) m.put(c, k);
        m.put(ItemStack.class, Kind.ITEM);
        m.put(Component.class, Kind.COMPONENT);
        return Map.copyOf(m);
    }

    /// The leaf [Kind] for a scalar class, or null for enums/records/lists/maps.
    static @Nullable Kind kindOf(Class<?> type) {
        return KIND_BY_CLASS.get(type);
    }

    /// One node in a packet's widget tree. The variant carries only the data its kind needs.
    public sealed interface Field {
        String name();
        Kind kind();

        record Leaf(String name, Kind kind) implements Field {}
        record EnumF(String name, List<String> values) implements Field {
            public Kind kind() { return Kind.ENUM; }
        }
        record RecordF(String name, List<Field> components) implements Field {
            public Kind kind() { return Kind.RECORD; }
        }
        record ListF(String name, Field element) implements Field {
            public Kind kind() { return Kind.LIST; }
        }
        record MapF(String name, Field key, Field value) implements Field {
            public Kind kind() { return Kind.MAP; }
        }
    }

    private static final ConcurrentHashMap<Class<?>, Optional<List<Field>>> SCHEMA_CACHE = new ConcurrentHashMap<>();

    /// Returns the typed schema for a record packet, or empty if any component resolves
    /// to an unsupported type. Cached per class.
    public static Optional<List<Field>> schema(Class<?> cls) {
        return SCHEMA_CACHE.computeIfAbsent(cls, c -> Optional.ofNullable(buildSchema(c, new HashSet<>())));
    }

    public static boolean isAnalyzable(Class<?> cls) { return schema(cls).isPresent(); }

    private static @Nullable List<Field> buildSchema(Class<?> cls, Set<Class<?>> visiting) {
        if (!cls.isRecord()) return null;
        if (!visiting.add(cls)) return null;
        try {
            List<Field> fields = new ArrayList<>();
            for (RecordComponent c : cls.getRecordComponents()) {
                Field f = fieldFor(c.getName(), c.getType(), c.getGenericType(), visiting);
                if (f == null) return null;
                fields.add(f);
            }
            return List.copyOf(fields);
        } finally {
            visiting.remove(cls);
        }
    }

    private static @Nullable Field fieldFor(String name, Class<?> type, Type generic, Set<Class<?>> visiting) {
        Kind k = KIND_BY_CLASS.get(type);
        if (k != null) return new Field.Leaf(name, k);
        if (type.isEnum()) {
            List<String> values = new ArrayList<>();
            for (Object e : type.getEnumConstants()) values.add(((Enum<?>) e).name());
            return new Field.EnumF(name, List.copyOf(values));
        }
        if (List.class.isAssignableFrom(type) && generic instanceof ParameterizedType pt) {
            Type arg = pt.getActualTypeArguments()[0];
            Field element = fieldFor("item", rawClass(arg), arg, visiting);
            return element == null ? null : new Field.ListF(name, element);
        }
        if (Map.class.isAssignableFrom(type) && generic instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            Field key = fieldFor("key", rawClass(args[0]), args[0], visiting);
            Field val = fieldFor("value", rawClass(args[1]), args[1], visiting);
            if (key == null || val == null || !key.kind().canBeMapKey()) return null;
            return new Field.MapF(name, key, val);
        }
        if (type.isRecord()) {
            List<Field> nested = buildSchema(type, visiting);
            return nested == null ? null : new Field.RecordF(name, nested);
        }
        return null;
    }

    static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType pt) return (Class<?>) pt.getRawType();
        return Object.class;
    }

    /// Wire format for `/api/packet/describe/{class}`: `{class, analyzable, components?}`.
    public static JsonObject describe(String classNameOrSimple) throws ClassNotFoundException {
        Class<?> cls = PacketCatalog.packetClass(classNameOrSimple);
        JsonObject out = new JsonObject();
        out.addProperty("class", cls.getName());
        Optional<List<Field>> s = schema(cls);
        out.addProperty("analyzable", s.isPresent());
        s.ifPresent(fields -> out.add("components", fieldsJson(fields)));
        return out;
    }

    private static JsonArray fieldsJson(List<Field> fields) {
        JsonArray array = new JsonArray();
        for (Field f : fields) array.add(fieldJson(f));
        return array;
    }

    private static JsonObject fieldJson(Field f) {
        JsonObject o = new JsonObject();
        o.addProperty("name", f.name());
        o.addProperty("kind", f.kind().name().toLowerCase());
        switch (f) {
            case Field.EnumF e -> {
                JsonArray values = new JsonArray();
                for (String v : e.values()) values.add(v);
                o.add("values", values);
            }
            case Field.RecordF r -> o.add("components", fieldsJson(r.components()));
            case Field.ListF l -> o.add("element", fieldJson(l.element()));
            case Field.MapF m -> {
                o.add("key", fieldJson(m.key()));
                o.add("value", fieldJson(m.value()));
            }
            case Field.Leaf _ -> {}
        }
        return o;
    }
}
