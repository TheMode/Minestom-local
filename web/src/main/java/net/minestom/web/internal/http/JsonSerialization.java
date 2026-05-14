package net.minestom.web.internal.http;

import com.google.gson.*;
import net.kyori.adventure.nbt.BinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.item.ItemStack;
import net.minestom.web.Action;
import net.minestom.web.Routine.Trigger;
import net.minestom.web.internal.codec.RoutineCodecs;
import net.minestom.web.internal.codec.WebJson;

/// Shared [Gson] instance for types that still round-trip through Gson trees (decoded packets,
/// routine editor payloads, generic REST maps). Dashboard wire shapes are defined in
/// `WebCodecs` / `RoutineCodecs` and encoded via `WebJson`.
public final class JsonSerialization {

    public static final Gson GSON = new GsonBuilder()
            .serializeNulls()
            .registerTypeHierarchyAdapter(BinaryTag.class,
                    (JsonSerializer<BinaryTag>) (src, _, _) -> WebJson.encode(Codec.NBT, src))
            .registerTypeHierarchyAdapter(Component.class,
                    (JsonSerializer<Component>) (src, _, _) -> WebJson.encode(Codec.COMPONENT, src))
            .registerTypeHierarchyAdapter(Component.class,
                    (JsonDeserializer<Component>) (json, _, _) -> WebJson.decode(Codec.COMPONENT, json))
            .registerTypeHierarchyAdapter(ItemStack.class,
                    (JsonSerializer<ItemStack>) (src, _, _) -> WebJson.encode(ItemStack.CODEC, src))
            .registerTypeHierarchyAdapter(Action.class,
                    (JsonSerializer<Action>) (src, _, _) -> WebJson.encodeAsObject(RoutineCodecs.ACTION, src))
            .registerTypeHierarchyAdapter(Trigger.class,
                    (JsonSerializer<Trigger>) (src, _, _) ->
                            WebJson.encodeAsObject(RoutineCodecs.TRIGGER, src))
            .create();

    private JsonSerialization() {}
}
