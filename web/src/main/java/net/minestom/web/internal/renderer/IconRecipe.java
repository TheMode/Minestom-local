package net.minestom.web.internal.renderer;

import org.jetbrains.annotations.Nullable;

/// Resolved render strategy for a single material id.
record IconRecipe(Kind kind, @Nullable String a, @Nullable String b, @Nullable String c) {
    public enum Kind {
        FLAT,
        CUBE,
        ENTITY_SPRITE,
        BANNER,
        BED,
        CHEST,
        HEAD,
        SHULKER_BOX,
        DECORATED_POT,
        COPPER_GOLEM_STATUE,
    }

    public static IconRecipe flatItem(String itemTexture) {
        return new IconRecipe(Kind.FLAT, itemTexture, null, null);
    }

    public static IconRecipe cube(String top, String left, String right) {
        return new IconRecipe(Kind.CUBE, top, left, right);
    }

    public static IconRecipe entitySprite(String entityPath) {
        return new IconRecipe(Kind.ENTITY_SPRITE, entityPath, null, null);
    }

    public static IconRecipe banner(String color) {
        return new IconRecipe(Kind.BANNER, color, null, null);
    }

    public static IconRecipe bed(String color) {
        return new IconRecipe(Kind.BED, color, null, null);
    }

    public static IconRecipe chest(String texture) {
        return new IconRecipe(Kind.CHEST, texture, null, null);
    }

    public static IconRecipe head(String texture) {
        return new IconRecipe(Kind.HEAD, texture, null, null);
    }

    public static IconRecipe shulkerBox(String texture) {
        return new IconRecipe(Kind.SHULKER_BOX, texture, null, null);
    }

    public static IconRecipe decoratedPot() {
        return new IconRecipe(Kind.DECORATED_POT, null, null, null);
    }

    public static IconRecipe copperGolemStatue(String texture) {
        return new IconRecipe(Kind.COPPER_GOLEM_STATUE, texture, null, null);
    }
}
