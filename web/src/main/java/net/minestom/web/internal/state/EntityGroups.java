package net.minestom.web.internal.state;

import net.minestom.server.entity.EntityType;

import java.util.Set;

/// Coarse minimap classification for an entity type. Mirrors the filter-chip buckets in the
/// frontend. The unmatched fallthrough is `passive`, which keeps friendly mobs, NPCs, and new
/// vanilla entities readable on the dashboard until they're classified explicitly.
public final class EntityGroups {

    public static final String PLAYERS = "players";
    public static final String ITEMS = "items";
    public static final String PROJECTILES = "projectiles";
    public static final String VEHICLES = "vehicles";
    public static final String HOSTILE = "hostile";
    public static final String PASSIVE = "passive";
    public static final String OTHER = "other";

    private static final Set<EntityType> ITEM_TYPES = Set.of(
            EntityType.ITEM, EntityType.EXPERIENCE_ORB,
            EntityType.ITEM_FRAME, EntityType.GLOW_ITEM_FRAME);

    private static final Set<EntityType> PROJECTILE_TYPES = Set.of(
            EntityType.ARROW, EntityType.SPECTRAL_ARROW, EntityType.TRIDENT,
            EntityType.FIREBALL, EntityType.SMALL_FIREBALL, EntityType.DRAGON_FIREBALL,
            EntityType.SNOWBALL, EntityType.EGG,
            EntityType.SPLASH_POTION, EntityType.LINGERING_POTION,
            EntityType.SHULKER_BULLET, EntityType.LLAMA_SPIT, EntityType.WITHER_SKULL,
            EntityType.FISHING_BOBBER, EntityType.EYE_OF_ENDER, EntityType.ENDER_PEARL,
            EntityType.FIREWORK_ROCKET);

    private static final Set<EntityType> VEHICLE_TYPES = Set.of(
            EntityType.OAK_BOAT, EntityType.SPRUCE_BOAT, EntityType.BIRCH_BOAT,
            EntityType.JUNGLE_BOAT, EntityType.ACACIA_BOAT, EntityType.DARK_OAK_BOAT,
            EntityType.MANGROVE_BOAT, EntityType.CHERRY_BOAT, EntityType.PALE_OAK_BOAT,
            EntityType.OAK_CHEST_BOAT, EntityType.SPRUCE_CHEST_BOAT, EntityType.BIRCH_CHEST_BOAT,
            EntityType.JUNGLE_CHEST_BOAT, EntityType.ACACIA_CHEST_BOAT, EntityType.DARK_OAK_CHEST_BOAT,
            EntityType.MANGROVE_CHEST_BOAT, EntityType.CHERRY_CHEST_BOAT, EntityType.PALE_OAK_CHEST_BOAT,
            EntityType.MINECART, EntityType.CHEST_MINECART, EntityType.FURNACE_MINECART,
            EntityType.HOPPER_MINECART, EntityType.TNT_MINECART, EntityType.SPAWNER_MINECART,
            EntityType.COMMAND_BLOCK_MINECART);

    /// Hostile mobs as of 1.21. Anything not matched by an earlier rule falls into `passive`.
    private static final Set<EntityType> HOSTILE_TYPES = Set.of(
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
            EntityType.ZOMBIFIED_PIGLIN, EntityType.ZOGLIN,
            EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON, EntityType.BOGGED,
            EntityType.SPIDER, EntityType.CAVE_SPIDER,
            EntityType.CREEPER, EntityType.ENDERMAN, EntityType.ENDERMITE, EntityType.WITCH,
            EntityType.BLAZE, EntityType.GHAST, EntityType.MAGMA_CUBE, EntityType.SLIME,
            EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER, EntityType.VEX,
            EntityType.RAVAGER, EntityType.ILLUSIONER,
            EntityType.GUARDIAN, EntityType.ELDER_GUARDIAN, EntityType.PHANTOM,
            EntityType.HOGLIN, EntityType.PIGLIN, EntityType.PIGLIN_BRUTE,
            EntityType.SHULKER, EntityType.WARDEN, EntityType.WITHER, EntityType.ENDER_DRAGON,
            EntityType.SILVERFISH, EntityType.BREEZE);

    private EntityGroups() {
    }

    public static String classify(EntityType type) {
        if (type == null) return OTHER;
        if (type == EntityType.PLAYER) return PLAYERS;
        if (ITEM_TYPES.contains(type)) return ITEMS;
        if (PROJECTILE_TYPES.contains(type)) return PROJECTILES;
        if (VEHICLE_TYPES.contains(type)) return VEHICLES;
        if (HOSTILE_TYPES.contains(type)) return HOSTILE;
        return PASSIVE;
    }
}
