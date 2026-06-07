package net.minestom.web.internal.renderer;

import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;

/// Shared id/texture-reference cleanup helpers for the icon resolvers ([IconCatalog],
/// [BlockModelResolver]): strip the `minecraft:` namespace and `block/`/`item/` prefixes off
/// resource ids and read string JSON values defensively.
final class IconResourceIds {

    private IconResourceIds() {}

    /// Strip `minecraft:` and a leading `block/` or `item/` segment off a texture reference.
    static @Nullable String bareTexture(String raw) {
        return strip(raw, "block/", "item/");
    }

    /// Strip the `minecraft:` namespace off an id, leaving any path prefix intact.
    static @Nullable String stripNamespace(@Nullable String raw) {
        if (raw == null) return null;
        return raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
    }

    /// Strip `minecraft:` and a leading `block/` segment off a model id (model paths keep `item/`).
    static @Nullable String modelPath(String id) {
        return strip(id, "block/");
    }

    /// Strip the `minecraft:` namespace, then each of `prefixes` (in order) once if present.
    private static @Nullable String strip(String raw, String... prefixes) {
        String s = raw.startsWith("minecraft:") ? raw.substring("minecraft:".length()) : raw;
        for (String prefix : prefixes) if (s.startsWith(prefix)) s = s.substring(prefix.length());
        return s.isEmpty() ? null : s;
    }

    static @Nullable String stringOrNull(@Nullable JsonElement el) {
        return el == null || el.isJsonNull() ? null : el.getAsString();
    }
}
