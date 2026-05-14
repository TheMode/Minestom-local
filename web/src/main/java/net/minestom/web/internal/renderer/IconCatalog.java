package net.minestom.web.internal.renderer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/// Builds a material-id → [IconRecipe] map from extracted vanilla `items/*.json` definitions.
final class IconCatalog {
    private static final Logger LOGGER = LoggerFactory.getLogger(IconCatalog.class);
    private static final String ITEMS = "/web/assets/items/";
    private static final String ITEM_MODELS = "/web/assets/models/item/";

    private final Map<String, IconRecipe> recipes = new HashMap<>();
    private final BlockModelResolver blockModels = new BlockModelResolver();

    private IconCatalog() {}

    static IconCatalog load() {
        IconCatalog catalog = new IconCatalog();
        catalog.scanItems();
        LOGGER.info("Icon catalog: {} recipes from item definitions", catalog.recipes.size());
        return catalog;
    }

    @Nullable IconRecipe recipe(String bareId) {
        return recipes.get(bareId);
    }

    private void scanItems() {
        try {
            URL itemsRoot = IconCatalog.class.getResource(ITEMS);
            if (itemsRoot == null) return;
            if ("jar".equals(itemsRoot.getProtocol())) {
                scanJarItems(itemsRoot);
            } else {
                scanFileItems(itemsRoot);
            }
        } catch (Exception e) {
            LOGGER.warn("Icon catalog: failed to scan item definitions: {}", e.toString());
        }
    }

    private void scanJarItems(URL jarUrl) throws Exception {
        JarURLConnection conn = (JarURLConnection) jarUrl.openConnection();
        try (JarFile jar = conn.getJarFile()) {
            String prefix = conn.getEntryName();
            if (prefix == null) return;
            if (!prefix.endsWith("/")) prefix += "/";
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.startsWith(prefix) || !name.endsWith(".json")) continue;
                parseItem(name.substring(prefix.length(), name.length() - 5));
            }
        }
    }

    private void scanFileItems(URL dirUrl) throws Exception {
        Path root = Path.of(dirUrl.toURI());
        try (var stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(p -> {
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        parseItem(rel.substring(0, rel.length() - 5));
                    });
        }
    }

    private void parseItem(String bareId) {
        try (InputStream in = IconCatalog.class.getResourceAsStream(ITEMS + bareId + ".json")) {
            if (in == null) return;
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject model = root.getAsJsonObject("model");
            if (model == null) return;
            IconRecipe recipe = resolveModel(model);
            if (recipe != null) recipes.put(bareId, recipe);
        } catch (Exception ignored) {
        }
    }

    private @Nullable IconRecipe resolveModel(JsonObject model) {
        String type = IconResourceIds.stringOrNull(model.get("type"));
        if (type == null) return null;
        return switch (type) {
            case "minecraft:model" -> resolvePathModel(IconResourceIds.stringOrNull(model.get("model")));
            case "minecraft:bed" -> IconRecipe.bed(IconResourceIds.stripNamespace(IconResourceIds.stringOrNull(model.get("texture"))));
            case "minecraft:banner" -> IconRecipe.banner(IconResourceIds.stringOrNull(model.get("color")));
            case "minecraft:chest" -> chestRecipe(IconResourceIds.stripNamespace(IconResourceIds.stringOrNull(model.get("texture"))));
            case "minecraft:shulker_box" -> shulkerRecipe(IconResourceIds.stripNamespace(IconResourceIds.stringOrNull(model.get("texture"))));
            case "minecraft:copper_golem_statue" -> IconRecipe.copperGolemStatue(texturePath(IconResourceIds.stringOrNull(model.get("texture"))));
            case "minecraft:boat", "minecraft:chest_boat" -> boatRecipe(type, IconResourceIds.stripNamespace(IconResourceIds.stringOrNull(model.get("texture"))));
            case "minecraft:head" -> headRecipe(model);
            case "minecraft:player_head" -> resolvePathModel(IconResourceIds.stringOrNull(model.get("base")));
            case "minecraft:shield" -> IconRecipe.entitySprite("entity/shield/shield_base_nopattern");
            case "minecraft:conduit" -> IconRecipe.entitySprite("entity/conduit/wind");
            case "minecraft:decorated_pot" -> IconRecipe.decoratedPot();
            case "minecraft:bell" -> IconRecipe.entitySprite("entity/bell/bell_body");
            case "minecraft:composite" -> resolveComposite(model.getAsJsonArray("models"));
            case "minecraft:select" -> resolveModel(model.getAsJsonObject("fallback"));
            case "minecraft:condition" -> {
                JsonObject whenTrue = model.getAsJsonObject("on_true");
                yield whenTrue != null ? resolveModel(whenTrue) : resolveModel(model.getAsJsonObject("on_false"));
            }
            case "minecraft:range_dispatch" -> resolveModel(model.getAsJsonObject("fallback"));
            case "minecraft:constant" -> resolveModel(model.getAsJsonObject("value"));
            case "minecraft:dye", "minecraft:grass", "minecraft:map_color", "minecraft:potion", "minecraft:trident" ->
                    resolvePathModel(IconResourceIds.stringOrNull(model.get("base")));
            case "minecraft:special" -> {
                JsonObject inner = model.getAsJsonObject("model");
                yield inner != null ? resolveModel(inner) : resolvePathModel(IconResourceIds.stringOrNull(model.get("base")));
            }
            default -> null;
        };
    }

    private @Nullable IconRecipe resolveComposite(@Nullable JsonArray models) {
        if (models == null) return null;
        for (JsonElement el : models) {
            if (!el.isJsonObject()) continue;
            IconRecipe r = resolveModel(el.getAsJsonObject());
            if (r != null) return r;
        }
        return null;
    }

    private @Nullable IconRecipe resolvePathModel(@Nullable String path) {
        if (path == null) return null;
        String p = path.startsWith("minecraft:") ? path.substring("minecraft:".length()) : path;
        if (p.startsWith("block/")) {
            String blockId = p.substring("block/".length());
            IconRecipe fromModel = blockModels.resolve(blockId);
            if (fromModel != null) return fromModel;
            return IconRecipe.cube(blockId, blockId, blockId);
        }
        if (p.startsWith("item/")) {
            return resolveItemModel(p.substring("item/".length()));
        }
        return null;
    }

    private @Nullable IconRecipe resolveItemModel(String itemModelId) {
        try (InputStream in = IconCatalog.class.getResourceAsStream(ITEM_MODELS + itemModelId + ".json")) {
            if (in == null) return null;
            JsonObject model = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject textures = model.getAsJsonObject("textures");
            if (textures != null) {
                String layer0 = IconResourceIds.stringOrNull(textures.get("layer0"));
                if (layer0 != null) {
                    String tex = IconResourceIds.bareTexture(layer0);
                    if (tex != null && IconCatalog.class.getResource("/web/assets/textures/item/" + tex + ".png") != null) {
                        return IconRecipe.flatItem(tex);
                    }
                }
            }
            String parent = IconResourceIds.stringOrNull(model.get("parent"));
            if (parent != null && parent.contains("template_bed")) {
                String color = colorFromId(itemModelId);
                if (color != null) return IconRecipe.bed(color);
            }
            if (parent != null && parent.contains("template_banner")) {
                String color = colorFromId(itemModelId);
                if (color != null) return IconRecipe.banner(color);
            }
            if (parent != null) return resolvePathModel(parent);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static @Nullable IconRecipe chestRecipe(@Nullable String variant) {
        if (variant == null) variant = "normal";
        return IconRecipe.chest("entity/chest/" + variant);
    }

    private static @Nullable IconRecipe shulkerRecipe(@Nullable String color) {
        if (color == null || color.equals("shulker")) {
            return IconRecipe.shulkerBox("entity/shulker/shulker");
        }
        return IconRecipe.shulkerBox("entity/shulker/" + color);
    }

    private static @Nullable IconRecipe boatRecipe(String type, @Nullable String wood) {
        if (wood == null) return null;
        String path = type.equals("minecraft:chest_boat")
                ? "entity/chest_boat/" + wood
                : "entity/boat/" + wood;
        return IconRecipe.entitySprite(path);
    }

    private static @Nullable IconRecipe headRecipe(JsonObject model) {
        String kind = IconResourceIds.stringOrNull(model.get("kind"));
        return switch (kind == null ? "skeleton" : kind) {
            case "skeleton" -> IconRecipe.head("entity/skeleton/skeleton");
            case "wither_skeleton" -> IconRecipe.head("entity/skeleton/wither_skeleton");
            case "zombie" -> IconRecipe.head("entity/zombie/zombie");
            case "creeper" -> IconRecipe.head("entity/creeper/creeper");
            case "piglin" -> IconRecipe.head("entity/piglin/piglin");
            case "dragon" -> IconRecipe.head("entity/enderdragon/dragon");
            default -> IconRecipe.head("entity/skeleton/skeleton");
        };
    }

    private static @Nullable String colorFromId(String itemModelId) {
        for (String c : IconConstants.COLOURS) {
            if (itemModelId.startsWith(c + "_")) return c;
        }
        return null;
    }

    private static @Nullable String texturePath(@Nullable String raw) {
        String s = IconResourceIds.stripNamespace(raw);
        if (s == null) return null;
        if (s.startsWith("textures/")) s = s.substring("textures/".length());
        if (s.endsWith(".png")) s = s.substring(0, s.length() - 4);
        return s;
    }
}
