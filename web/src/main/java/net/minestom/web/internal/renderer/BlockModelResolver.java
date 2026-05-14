package net.minestom.web.internal.renderer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/// Resolves `minecraft:block/<id>` models into top / left / right face texture names.
final class BlockModelResolver {
    private static final String MODELS = "/web/assets/models/block/";
    private static final int MAX_PARENT_DEPTH = 12;

    private final Map<String, JsonObject> cache = new HashMap<>();

    @Nullable IconRecipe resolve(String blockId) {
        JsonObject model = load(blockId);
        if (model == null) return null;
        Map<String, String> textures = new HashMap<>();
        mergeModel(model, textures, new HashSet<>(), 0);
        if (textures.isEmpty()) return null;
        String top = face(textures, "up", "top", "all", "particle");
        String left = face(textures, "west", "side", "north", "all", "particle");
        String right = face(textures, "east", "side", "south", "all", "particle");
        if (top == null && left == null && right == null) return null;
        if (top == null) top = left != null ? left : right;
        if (left == null) left = top;
        if (right == null) right = left;
        return IconRecipe.cube(top, left, right);
    }

    private void mergeModel(JsonObject model, Map<String, String> out, Set<String> visiting, int depth) {
        if (depth > MAX_PARENT_DEPTH) return;
        String parent = IconResourceIds.stringOrNull(model.get("parent"));
        if (parent != null) {
            String parentPath = IconResourceIds.modelPath(parent);
            if (parentPath != null && visiting.add(parentPath)) {
                JsonObject parentModel = load(parentPath);
                if (parentModel != null) mergeModel(parentModel, out, visiting, depth + 1);
                visiting.remove(parentPath);
            }
        }
        JsonObject tex = model.getAsJsonObject("textures");
        if (tex != null) {
            for (Map.Entry<String, JsonElement> e : tex.entrySet()) {
                String resolved = resolveTextureRef(e.getValue().getAsString(), out);
                if (resolved != null) out.put(e.getKey(), resolved);
            }
        }
    }

    private static String face(Map<String, String> textures, String... keys) {
        for (String key : keys) {
            String v = textures.get(key);
            if (v != null) return IconResourceIds.bareTexture(v);
        }
        return null;
    }

    private static @Nullable String resolveTextureRef(String raw, Map<String, String> ctx) {
        if (raw.startsWith("#")) {
            return ctx.get(raw.substring(1));
        }
        return IconResourceIds.bareTexture(raw);
    }

    private @Nullable JsonObject load(String path) {
        return cache.computeIfAbsent(path, p -> {
            try (InputStream in = BlockModelResolver.class.getResourceAsStream(MODELS + p + ".json")) {
                if (in == null) return null;
                return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (Exception e) {
                return null;
            }
        });
    }
}
