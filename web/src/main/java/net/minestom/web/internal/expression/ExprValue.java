package net.minestom.web.internal.expression;

import net.kyori.adventure.nbt.CompoundBinaryTag;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public sealed interface ExprValue {
    ExprValue NULL = new Null();

    record Null() implements ExprValue {}
    record Bool(boolean value) implements ExprValue {}
    record Num(double value) implements ExprValue {}
    record Str(String value) implements ExprValue {}
    record Vec3(double x, double y, double z) implements ExprValue {}
    record Tag(CompoundBinaryTag value) implements ExprValue {}
    record Coll(List<ExprValue> value) implements ExprValue {}
    record Dict(Map<String, ExprValue> value) implements ExprValue {}
    record Opaque(Object value) implements ExprValue {}

    default String str() {
        return switch (this) {
            case Null _ -> "";
            case Str s -> s.value();
            default -> String.valueOf(toObject());
        };
    }

    default double num() {
        return switch (this) {
            case Null _ -> 0;
            case Num n -> n.value();
            case Bool b -> b.value() ? 1 : 0;
            default -> {
                try { yield Double.parseDouble(str()); }
                catch (NumberFormatException e) { yield 0; }
            }
        };
    }

    default boolean isTruthy() {
        return switch (this) {
            case Null _ -> false;
            case Bool b -> b.value();
            case Num n -> n.value() != 0;
            case Str s -> !s.value().isEmpty();
            case Coll c -> !c.value().isEmpty();
            case Dict d -> !d.value().isEmpty();
            default -> true;
        };
    }

    static ExprValue of(Object o) {
        return switch (o) {
            case null -> NULL;
            case Boolean b -> new Bool(b);
            case Number n -> new Num(n.doubleValue());
            case String s -> new Str(s);
            case CompoundBinaryTag tag -> new Tag(tag);
            case Collection<?> c -> {
                var items = new ArrayList<ExprValue>(c.size());
                for (Object item : c) items.add(of(item));
                yield new Coll(items);
            }
            case Map<?, ?> m -> {
                var map = new LinkedHashMap<String, ExprValue>(m.size());
                for (var e : m.entrySet()) map.put(String.valueOf(e.getKey()), of(e.getValue()));
                yield new Dict(map);
            }
            default -> new Opaque(o);
        };
    }

    default Object toObject() {
        return switch (this) {
            case Null _ -> null;
            case Bool b -> b.value();
            case Num n -> n.value();
            case Str s -> s.value();
            case Vec3 v -> new double[]{v.x(), v.y(), v.z()};
            case Tag t -> t.value();
            case Coll c -> {
                var list = new ArrayList<>(c.value().size());
                for (ExprValue v : c.value()) list.add(v.toObject());
                yield list;
            }
            case Dict d -> {
                var map = new LinkedHashMap<String, Object>(d.value().size());
                for (var e : d.value().entrySet()) map.put(e.getKey(), e.getValue().toObject());
                yield map;
            }
            case Opaque o -> o.value();
        };
    }
}
