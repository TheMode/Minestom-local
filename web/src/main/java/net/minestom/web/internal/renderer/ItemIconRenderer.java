package net.minestom.web.internal.renderer;

import net.minestom.server.item.Material;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/// Renders 32×32 isometric PNG icons for any [Material]. Block-entity items (beds, chests,
/// shulker boxes, heads, decorated pots, copper-golem statues) get bespoke quad projections;
/// everything else either reads a flat sprite or composes a 3-face cube via [IconCanvas].
public final class ItemIconRenderer {
    private static final Logger LOGGER = LoggerFactory.getLogger(ItemIconRenderer.class);
    private static final byte[] MISSING = new byte[0];
    private static final java.util.regex.Pattern SAFE_ID = java.util.regex.Pattern.compile("[a-z0-9_]+");

    private final ConcurrentHashMap<String, byte[]> cache = new ConcurrentHashMap<>();
    private final IconCatalog catalog;

    public ItemIconRenderer() {
        this.catalog = IconCatalog.load();
    }

    public byte[] iconFor(String id) {
        if (id == null) return null;
        final String bare = bareId(id);
        if (!SAFE_ID.matcher(bare).matches()) return null;
        final byte[] cached = cache.computeIfAbsent(bare, this::render);
        return cached.length == 0 ? null : cached;
    }

    public void warm() {
        long t0 = System.currentTimeMillis();
        int n = 0;
        for (Material m : Material.values()) {
            try {
                iconFor(m.key().value());
                n++;
            } catch (Exception _) {
            }
        }
        LOGGER.info("Item icons warmed: {} materials in {} ms", n, System.currentTimeMillis() - t0);
    }

    private byte[] render(String bare) {
        try {
            byte[] flat = TextureResources.readBytes(TextureResources.ROOT + "/item/" + bare + ".png");
            if (flat != null) return flat;

            IconRecipe recipe = catalog.recipe(bare);
            if (recipe != null) {
                byte[] fromRecipe = renderRecipe(recipe);
                if (fromRecipe != null) return fromRecipe;
            }

            BufferedImage top = loadBlockFace(bare, "top", "up", "end", "front");
            BufferedImage side = loadBlockFace(bare, "side", "north", "west");
            BufferedImage all = TextureResources.load("block/" + bare);
            if (top == null) top = all != null ? all : side;
            if (side == null) side = all != null ? all : top;
            if (top == null) top = coloredWoolFallback(bare);
            if (top == null) return MISSING;
            if (side == null) side = top;
            return IconCanvas.cube(top, side, side);
        } catch (Exception e) {
            LOGGER.debug("Icon render failed for {}: {}", bare, e.toString());
            return MISSING;
        }
    }

    private byte @Nullable [] renderRecipe(IconRecipe recipe) throws IOException {
        return switch (recipe.kind()) {
            case FLAT -> TextureResources.readBytes(TextureResources.ROOT + "/item/" + recipe.a() + ".png");
            case CUBE -> {
                BufferedImage top = TextureResources.load("block/" + recipe.a());
                BufferedImage left = TextureResources.load("block/" + recipe.b());
                BufferedImage right = TextureResources.load("block/" + recipe.c());
                if (top == null && left == null && right == null) yield null;
                if (top == null) top = left != null ? left : right;
                if (left == null) left = top;
                if (right == null) right = left;
                yield IconCanvas.cube(top, left, right);
            }
            case ENTITY_SPRITE -> {
                BufferedImage img = TextureResources.load(recipe.a());
                yield img == null ? null : SpriteIcons.scale(img);
            }
            case BANNER -> TextureResources.readBytes(TextureResources.ROOT + "/map/decorations/" + recipe.a() + "_banner.png");
            case BED -> {
                BufferedImage img = TextureResources.load("entity/bed/" + recipe.a());
                yield img == null ? null : renderBed(img);
            }
            case CHEST -> {
                BufferedImage img = TextureResources.load(recipe.a());
                yield img == null ? null : renderChest(img);
            }
            case HEAD -> {
                BufferedImage img = TextureResources.load(recipe.a());
                yield img == null ? null : renderHead(img);
            }
            case SHULKER_BOX -> {
                BufferedImage img = TextureResources.load(recipe.a());
                yield img == null ? null : renderShulkerBox(img);
            }
            case DECORATED_POT -> {
                BufferedImage tex = TextureResources.load("block/terracotta");
                yield tex == null ? null : renderDecoratedPot(tex);
            }
            case COPPER_GOLEM_STATUE -> {
                BufferedImage img = TextureResources.load(recipe.a());
                yield img == null ? null : renderCopperGolemStatue(img);
            }
        };
    }

    private @Nullable BufferedImage coloredWoolFallback(String bare) {
        for (String c : IconConstants.COLOURS) {
            if (bare.startsWith(c + "_")) return TextureResources.load("block/" + c + "_wool");
        }
        return null;
    }

    private @Nullable BufferedImage loadBlockFace(String bare, String... suffixes) {
        for (String suffix : suffixes) {
            BufferedImage img = TextureResources.load("block/" + bare + "_" + suffix);
            if (img != null) return img;
        }
        return TextureResources.load("block/" + bare);
    }

    private static String bareId(String id) {
        Objects.requireNonNull(id, "id");
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ---- block-entity quad projections ---------------------------------------------------
    // Compact stand-ins for the vanilla block-entity models — each projects a handful of
    // textured quads into a 128px canvas, then [IconCanvas] downsamples to 32px.

    private static byte[] renderBed(BufferedImage texture) throws IOException {
        IconCanvas c = new IconCanvas();

        c.quad(texture, 12, 58, 68, 88, 68, 104, 12, 74,
                16, 0, 22, 16, 0.55f);
        c.quad(texture, 68, 88, 124, 58, 124, 74, 68, 104,
                16, 22, 22, 38, 0.68f);

        // Mojang bed model pieces: head texOffs(0, 0), foot texOffs(0, 22).
        c.quad(texture, 52, 38, 80, 24, 124, 58, 94, 74,
                0, 22, 16, 38, 0.88f);
        c.quad(texture, 30, 50, 52, 38, 94, 74, 68, 88,
                0, 6, 16, 16, 0.88f);
        c.quad(texture, 12, 58, 30, 50, 68, 88, 48, 99,
                0, 0, 16, 6, 0.98f);

        c.quad(texture, 12, 74, 68, 104, 68, 112, 12, 82,
                16, 0, 22, 16, 0.50f);
        c.quad(texture, 77, 96, 89, 89, 89, 105, 77, 112,
                50, 0, 53, 3, 0.62f);
        c.quad(texture, 109, 72, 121, 66, 121, 82, 109, 88,
                50, 12, 53, 15, 0.66f);

        return c.png();
    }

    private static byte[] renderChest(BufferedImage texture) throws IOException {
        IconCanvas c = new IconCanvas();
        int w = texture.getWidth(), h = texture.getHeight();
        int u0 = w / 4, u1 = w / 2, u2 = Math.min(w, w * 3 / 4);
        int top0 = 0, top1 = Math.max(1, h / 4);
        int side0 = Math.max(1, h * 5 / 16), side1 = Math.max(side0 + 1, h * 9 / 16);
        int front0 = Math.max(1, h * 33 / 64), front1 = Math.max(front0 + 1, h * 45 / 64);

        c.quad(texture, 20, 42, 64, 17, 108, 42, 64, 68, u0, top0, u1, top1, 1f);
        c.quad(texture, 20, 42, 64, 68, 64, 112, 20, 88, 0, side0, u0, side1, 0.74f);
        c.quad(texture, 64, 68, 108, 42, 108, 88, 64, 112, u0, front0, u2, front1, 0.88f);
        c.quad(texture, 58, 68, 71, 61, 71, 77, 58, 84, u1, side0, u2, side1, 0.68f);
        return c.png();
    }

    private static byte[] renderShulkerBox(BufferedImage texture) throws IOException {
        IconCanvas c = new IconCanvas();
        BufferedImage cropped = SpriteIcons.tightCrop(texture, 0.02f);
        box(c, cropped, 24, 25, 104, 105, 1f, 0.72f, 0.86f);
        // Slightly raised lid line, like the in-game model, so shulkers do not read as wool cubes.
        c.quad(cropped, 22, 42, 64, 18, 106, 42, 64, 66,
                0, 0, cropped.getWidth(), Math.max(1, cropped.getHeight() / 3), 1f);
        return c.png();
    }

    private static byte[] renderHead(BufferedImage texture) throws IOException {
        IconCanvas c = new IconCanvas();
        int u0 = Math.min(texture.getWidth() - 1, 8);
        int v0 = Math.min(texture.getHeight() - 1, 8);
        int u1 = Math.min(texture.getWidth(), 16);
        int v1 = Math.min(texture.getHeight(), 16);
        if (texture.getWidth() >= 128) {
            u0 = texture.getWidth() * 3 / 8;
            v0 = texture.getHeight() / 8;
            u1 = texture.getWidth() * 5 / 8;
            v1 = texture.getHeight() * 3 / 8;
        }
        if (u1 <= u0 || v1 <= v0) {
            u0 = v0 = 0;
            u1 = texture.getWidth();
            v1 = texture.getHeight();
        }
        c.quad(texture, 32, 44, 64, 26, 96, 44, 64, 62, u0, v0, u1, v1, 1f);
        c.quad(texture, 32, 44, 64, 62, 64, 96, 32, 78, u0, v0, u1, v1, 0.72f);
        c.quad(texture, 64, 62, 96, 44, 96, 78, 64, 96, u0, v0, u1, v1, 0.86f);
        return c.png();
    }

    private static byte[] renderDecoratedPot(BufferedImage texture) throws IOException {
        IconCanvas c = new IconCanvas();
        c.quad(texture, 37, 47, 64, 32, 91, 47, 64, 63,
                0, 0, texture.getWidth(), texture.getHeight(), 1f);
        c.quad(texture, 31, 52, 64, 71, 64, 112, 31, 92,
                0, 0, texture.getWidth(), texture.getHeight(), 0.74f);
        c.quad(texture, 64, 71, 97, 52, 97, 92, 64, 112,
                0, 0, texture.getWidth(), texture.getHeight(), 0.88f);
        return c.png();
    }

    private static byte[] renderCopperGolemStatue(BufferedImage texture) throws IOException {
        IconCanvas c = new IconCanvas();
        // Body and head use visible texture atlas regions; exact pose animation is irrelevant for
        // inventory but the silhouette follows the block-entity renderer's upright statue framing.
        box(c, texture, 38, 48, 90, 106, 0.96f, 0.70f, 0.84f);
        c.quad(texture, 38, 31, 64, 16, 90, 31, 64, 46, 0, 0, 16, 16, 1f);
        c.quad(texture, 38, 31, 64, 46, 64, 64, 38, 49, 16, 16, 32, 32, 0.72f);
        c.quad(texture, 64, 46, 90, 31, 90, 49, 64, 64, 16, 16, 32, 32, 0.86f);
        return c.png();
    }

    private static void box(IconCanvas c, BufferedImage texture,
                            int left, int top, int right, int bottom,
                            float topBrightness, float leftBrightness, float rightBrightness) {
        int midX = (left + right) / 2;
        int shoulderY = top + (bottom - top) / 4;
        int centerY = top + (bottom - top) / 2;
        int footY = bottom - (bottom - top) / 4;
        int uMax = texture.getWidth();
        int vMax = texture.getHeight();

        c.quad(texture, left, shoulderY, midX, top, right, shoulderY, midX, centerY,
                0, 0, uMax, Math.max(1, vMax / 3), topBrightness);
        c.quad(texture, left, shoulderY, midX, centerY, midX, bottom, left, footY,
                0, vMax / 3, Math.max(1, uMax / 2), vMax, leftBrightness);
        c.quad(texture, midX, centerY, right, shoulderY, right, footY, midX, bottom,
                uMax / 2, vMax / 3, uMax, vMax, rightBrightness);
    }
}
