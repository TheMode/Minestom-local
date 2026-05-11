package net.minestom.server.network;

import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.utils.Either;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.*;

final class NetworkBufferTypeAnalysis {

    sealed interface TypeComponent {
        Class<?> type();

        record FixedField(Class<?> type, int byteSize, String kind) implements TypeComponent {
        }

        record VarField(Class<?> type, int maxBytes, String kind) implements TypeComponent {
        }

        record CollectionField(Class<?> type, String kind, TypeComponent element,
                               int maxSize) implements TypeComponent {
        }

        record MapField(Class<?> type, TypeComponent key, TypeComponent value, int maxSize) implements TypeComponent {
        }

        record ConditionalField(Class<?> type, String kind, TypeComponent... branches) implements TypeComponent {
        }

        record BlackBox(Class<?> type, String description) implements TypeComponent {
        }
    }

    record Result(List<TypeComponent> components, boolean fullyAnalyzable) {
    }

    static Result analyze(NetworkBuffer.Type<?>... types) {
        List<TypeComponent> components = new ArrayList<>();
        for (NetworkBuffer.Type<?> type : types) decompose(type, components);
        boolean fullyAnalyzable = true;
        for (TypeComponent c : components) {
            if (!(c instanceof TypeComponent.BlackBox)) continue;
            fullyAnalyzable = false;
            break;
        }
        return new Result(components, fullyAnalyzable);
    }

    private static void decompose(NetworkBuffer.Type<?> type, List<TypeComponent> components) {
        switch (type) {
            case NetworkBufferTypeImpl.TransformType(var parent, _, _) -> decompose(parent, components);
            case NetworkBufferTypeImpl.LazyType<?> t -> decompose(t.resolvedType(), components);
            case NetworkBufferTypeImpl.ListType(var parent, int maxSize) ->
                    components.add(new TypeComponent.CollectionField(
                            List.class, "list", analyzeSingle(parent), maxSize));
            case NetworkBufferTypeImpl.SetType(var parent, int maxSize) ->
                    components.add(new TypeComponent.CollectionField(
                            Set.class, "set", analyzeSingle(parent), maxSize));
            case NetworkBufferTypeImpl.MapType(var key, var value, int maxSize) ->
                    components.add(new TypeComponent.MapField(
                            Map.class, analyzeSingle(key), analyzeSingle(value), maxSize));
            case NetworkBufferTypeImpl.OptionalType(var parent) -> {
                var inner = analyzeSingle(parent);
                components.add(new TypeComponent.ConditionalField(inner.type(), "optional", inner));
            }
            case NetworkBufferTypeImpl.EitherType(var left, var right) ->
                    components.add(new TypeComponent.ConditionalField(
                            Either.class, "either", analyzeSingle(left), analyzeSingle(right)));
            case NetworkBufferTypeImpl.UnitType _ -> components.add(fixed(void.class, 0, "unit"));
            case NetworkBufferTypeImpl.BooleanType _ -> components.add(fixed(boolean.class, 1, "boolean"));
            case NetworkBufferTypeImpl.ByteType _ -> components.add(fixed(byte.class, 1, "byte"));
            case NetworkBufferTypeImpl.UnsignedByteType _ -> components.add(fixed(short.class, 1, "unsigned_byte"));
            case NetworkBufferTypeImpl.ShortType _ -> components.add(fixed(short.class, 2, "short"));
            case NetworkBufferTypeImpl.UnsignedShortType _ -> components.add(fixed(int.class, 2, "unsigned_short"));
            case NetworkBufferTypeImpl.IntType _ -> components.add(fixed(int.class, 4, "int"));
            case NetworkBufferTypeImpl.UnsignedIntType _ -> components.add(fixed(long.class, 4, "unsigned_int"));
            case NetworkBufferTypeImpl.LongType _ -> components.add(fixed(long.class, 8, "long"));
            case NetworkBufferTypeImpl.FloatType _ -> components.add(fixed(float.class, 4, "float"));
            case NetworkBufferTypeImpl.DoubleType _ -> components.add(fixed(double.class, 8, "double"));
            case NetworkBufferTypeImpl.VarIntType _ -> components.add(variable(int.class, 5, "var_int"));
            case NetworkBufferTypeImpl.OptionalVarIntType _ ->
                    components.add(variable(Integer.class, 5, "optional_var_int"));
            case NetworkBufferTypeImpl.VarInt3Type _ -> components.add(fixed(int.class, 3, "var_int_3"));
            case NetworkBufferTypeImpl.VarLongType _ -> components.add(variable(long.class, 10, "var_long"));
            case NetworkBufferTypeImpl.RawBytesType(int length) ->
                    components.add(blackBox(byte[].class, "raw_bytes(length=" + length + ")"));
            case NetworkBufferTypeImpl.StringType _ -> components.add(blackBox(String.class, "string"));
            case NetworkBufferTypeImpl.StringTerminatedType _ ->
                    components.add(blackBox(String.class, "string_terminated"));
            case NetworkBufferTypeImpl.IOUTF8StringType _ -> components.add(blackBox(String.class, "string_io_utf8"));
            case NetworkBufferTypeImpl.NbtType _ -> components.add(blackBox(BinaryTag.class, "nbt"));
            case NetworkBufferTypeImpl.BlockPositionType _ -> components.add(fixed(long.class, 8, "block_position"));
            case NetworkBufferTypeImpl.UUIDType _ -> {
                components.add(fixed(long.class, 8, "uuid_high"));
                components.add(fixed(long.class, 8, "uuid_low"));
            }
            case NetworkBufferTypeImpl.PosType _ -> {
                components.add(fixed(double.class, 8, "pos_x"));
                components.add(fixed(double.class, 8, "pos_y"));
                components.add(fixed(double.class, 8, "pos_z"));
                components.add(fixed(float.class, 4, "pos_yaw"));
                components.add(fixed(float.class, 4, "pos_pitch"));
            }
            case NetworkBufferTypeImpl.ByteArrayType _ -> components.add(blackBox(byte[].class, "byte_array"));
            case NetworkBufferTypeImpl.LongArrayType _ -> components.add(blackBox(long[].class, "long_array"));
            case NetworkBufferTypeImpl.VarIntArrayType _ -> components.add(blackBox(int[].class, "var_int_array"));
            case NetworkBufferTypeImpl.VarLongArrayType _ -> components.add(blackBox(long[].class, "var_long_array"));
            case NetworkBufferTypeImpl.Vector3Type _ -> {
                components.add(fixed(float.class, 4, "vec_x"));
                components.add(fixed(float.class, 4, "vec_y"));
                components.add(fixed(float.class, 4, "vec_z"));
            }
            case NetworkBufferTypeImpl.Vector3DType _ -> {
                components.add(fixed(double.class, 8, "vec_x"));
                components.add(fixed(double.class, 8, "vec_y"));
                components.add(fixed(double.class, 8, "vec_z"));
            }
            case NetworkBufferTypeImpl.Vector3IType _ -> {
                components.add(variable(int.class, 5, "vec_x"));
                components.add(variable(int.class, 5, "vec_y"));
                components.add(variable(int.class, 5, "vec_z"));
            }
            case NetworkBufferTypeImpl.Vector3BType _ -> {
                components.add(fixed(byte.class, 1, "vec_x"));
                components.add(fixed(byte.class, 1, "vec_y"));
                components.add(fixed(byte.class, 1, "vec_z"));
            }
            case NetworkBufferTypeImpl.LpVector3Type _ -> components.add(blackBox(Vec.class, "lp_vector3"));
            case NetworkBufferTypeImpl.QuaternionType _ -> {
                components.add(fixed(float.class, 4, "quat_x"));
                components.add(fixed(float.class, 4, "quat_y"));
                components.add(fixed(float.class, 4, "quat_z"));
                components.add(fixed(float.class, 4, "quat_w"));
            }
            case NetworkBufferTypeImpl.EnumSetType<?> _ -> components.add(blackBox(EnumSet.class, "enum_set"));
            case NetworkBufferTypeImpl.FixedBitSetType(int length) ->
                    components.add(blackBox(BitSet.class, "fixed_bitset=" + length));
            case NetworkBufferTypeImpl.TypedNbtType<?> _ -> components.add(blackBox(Object.class, "typed_nbt"));
            case NetworkBufferTypeImpl.UnionType<?, ?, ?> _ -> components.add(blackBox(Object.class, "union"));
            case NetworkBufferTypeImpl.LengthPrefixedType<?> _ ->
                    components.add(blackBox(Object.class, "length_prefixed"));
            case NetworkBufferTypeImpl.JsonComponentType _ ->
                    components.add(blackBox(Component.class, "json_component"));
            case ComponentNetworkBufferTypeImpl _ -> components.add(blackBox(Component.class, "component"));
            default -> {
                var templateSubTypes = extractTemplateSubTypes(type);
                if (templateSubTypes != null) {
                    for (var subType : templateSubTypes) {
                        decompose(subType, components);
                    }
                } else {
                    components.add(blackBox(type.getClass(), "unknown(type=" + type + ")"));
                }
            }
        }
    }

    private static TypeComponent analyzeSingle(NetworkBuffer.Type<?> type) {
        List<TypeComponent> list = new ArrayList<>();
        decompose(type, list);
        return list.getFirst();
    }

    private static NetworkBuffer.Type<?> @Nullable [] extractTemplateSubTypes(NetworkBuffer.Type<?> type) {
        Class<?> clazz = type.getClass();
        if (!clazz.isAnonymousClass()) return null;

        Field[] fields = clazz.getDeclaredFields();
        List<NetworkBuffer.Type<?>> typeFields = new ArrayList<>();

        for (Field field : fields) {
            if (NetworkBuffer.Type.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(type);
                    if (value instanceof NetworkBuffer.Type<?> subType) {
                        typeFields.add(subType);
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }

        return typeFields.isEmpty() ? null : typeFields.toArray(new NetworkBuffer.Type<?>[0]);
    }

    private static TypeComponent fixed(Class<?> type, int byteSize, String kind) {
        return new TypeComponent.FixedField(type, byteSize, kind);
    }

    private static TypeComponent variable(Class<?> type, int maxBytes, String kind) {
        return new TypeComponent.VarField(type, maxBytes, kind);
    }

    private static TypeComponent blackBox(Class<?> type, String desc) {
        return new TypeComponent.BlackBox(type, desc);
    }
}
