package net.minestom.web.internal.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.item.ItemStack;

import static net.minestom.web.internal.codec.WebCodecs.OPTIONAL_ITEM_STACK_LIST;
import static net.minestom.web.internal.codec.WebCodecs.itemStackList;
import static net.minestom.web.internal.codec.WebCodecs.nullIfAir;
import net.minestom.web.PlayerState;
import net.minestom.web.Provenance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Wire snapshot of [PlayerState], split across nested [StructCodec]s (Minestom caps structs
/// at 19 fields — a full player snapshot is far larger).
final class PlayerSnapshot {

    private PlayerSnapshot() {}

    static JsonObject toJson(PlayerState p, Transcoder<JsonElement> coder) {
        return WebJson.encodeAsObject(Snapshot.CODEC, Snapshot.from(p), coder);
    }

    private record Snapshot(
            Core core,
            PlayerState.Traffic traffic,
            World world,
            Vitals vitals,
            Abilities abilities,
            Inventory inventory,
            Hud hud,
            MetaFeed feed,
            MetaSync sync,
            MetaProvenance provenance
    ) {
        static Snapshot from(PlayerState p) {
            return new Snapshot(
                    Core.from(p),
                    p.traffic,
                    World.from(p),
                    Vitals.from(p),
                    Abilities.from(p),
                    Inventory.from(p),
                    Hud.from(p),
                    MetaFeed.from(p),
                    MetaSync.from(p),
                    MetaProvenance.from(p));
        }

        static final StructCodec<Snapshot> CODEC = StructCodec.struct(
                StructCodec.INLINE, Core.CODEC, Snapshot::core,
                "traffic", WebCodecs.TRAFFIC, Snapshot::traffic,
                StructCodec.INLINE, World.CODEC, Snapshot::world,
                StructCodec.INLINE, Vitals.CODEC, Snapshot::vitals,
                StructCodec.INLINE, Abilities.CODEC, Snapshot::abilities,
                StructCodec.INLINE, Inventory.CODEC, Snapshot::inventory,
                StructCodec.INLINE, Hud.CODEC, Snapshot::hud,
                StructCodec.INLINE, MetaFeed.CODEC, Snapshot::feed,
                StructCodec.INLINE, MetaSync.CODEC, Snapshot::sync,
                StructCodec.INLINE, MetaProvenance.CODEC, Snapshot::provenance,
                Snapshot::new);
    }

    private record Core(
            String uuid,
            String connectionId,
            String journeyId,
            String username,
            String address,
            String backendAddress,
            int protocolVersion,
            String clientBrand,
            String serverBrand,
            String locale,
            long connectedAt,
            long disconnectedAt,
            String serverConnectionState,
            String clientConnectionState
    ) {
        static Core from(PlayerState p) {
            return new Core(
                    p.uuid == null ? null : p.uuid.toString(),
                    p.connectionId == null ? null : p.connectionId.toString(),
                    p.journeyId == null ? null : p.journeyId.toString(),
                    p.username,
                    String.valueOf(p.address),
                    p.backendAddress,
                    p.protocolVersion,
                    p.clientBrand,
                    p.serverBrand,
                    p.locale,
                    p.connectedAt,
                    p.disconnectedAt,
                    String.valueOf(p.serverConnectionState),
                    String.valueOf(p.clientConnectionState));
        }

        static final StructCodec<Core> CODEC = StructCodec.struct(
                "uuid", Codec.STRING.optional(), Core::uuid,
                "connectionId", Codec.STRING.optional(), Core::connectionId,
                "journeyId", Codec.STRING.optional(), Core::journeyId,
                "username", Codec.STRING.optional(), Core::username,
                "address", Codec.STRING, Core::address,
                "backendAddress", Codec.STRING.optional(), Core::backendAddress,
                "protocolVersion", Codec.INT, Core::protocolVersion,
                "clientBrand", Codec.STRING.optional(), Core::clientBrand,
                "serverBrand", Codec.STRING.optional(), Core::serverBrand,
                "locale", Codec.STRING.optional(), Core::locale,
                "connectedAt", Codec.LONG, Core::connectedAt,
                "disconnectedAt", Codec.LONG, Core::disconnectedAt,
                "serverConnectionState", Codec.STRING, Core::serverConnectionState,
                "clientConnectionState", Codec.STRING.optional(), Core::clientConnectionState,
                Core::new);
    }

    private record World(
            String dimension,
            String gamemode,
            boolean hardcore,
            double posX,
            double posY,
            double posZ,
            float yaw,
            float pitch,
            boolean onGround
    ) {
        static World from(PlayerState p) {
            return new World(p.dimension, p.gamemode, p.hardcore, p.posX, p.posY, p.posZ, p.yaw, p.pitch, p.onGround);
        }

        static final StructCodec<World> CODEC = StructCodec.struct(
                "dimension", Codec.STRING.optional(), World::dimension,
                "gamemode", Codec.STRING.optional(), World::gamemode,
                "hardcore", Codec.BOOLEAN, World::hardcore,
                "posX", Codec.DOUBLE, World::posX,
                "posY", Codec.DOUBLE, World::posY,
                "posZ", Codec.DOUBLE, World::posZ,
                "yaw", Codec.FLOAT, World::yaw,
                "pitch", Codec.FLOAT, World::pitch,
                "onGround", Codec.BOOLEAN, World::onGround,
                World::new);
    }

    private record Vitals(float health, float maxHealth, int food, float saturation, int xpLevel, float xpBar) {
        static Vitals from(PlayerState p) {
            return new Vitals(p.health, p.maxHealth, p.food, p.saturation, p.xpLevel, p.xpBar);
        }

        static final StructCodec<Vitals> CODEC = StructCodec.struct(
                "health", Codec.FLOAT, Vitals::health,
                "maxHealth", Codec.FLOAT, Vitals::maxHealth,
                "food", Codec.INT, Vitals::food,
                "saturation", Codec.FLOAT, Vitals::saturation,
                "xpLevel", Codec.INT, Vitals::xpLevel,
                "xpBar", Codec.FLOAT, Vitals::xpBar,
                Vitals::new);
    }

    private record Abilities(
            boolean invulnerable,
            boolean flying,
            boolean allowFlying,
            boolean instantBreak,
            float flySpeed,
            float walkSpeed
    ) {
        static Abilities from(PlayerState p) {
            return new Abilities(p.invulnerable, p.flying, p.allowFlying, p.instantBreak, p.flySpeed, p.walkSpeed);
        }

        static final StructCodec<Abilities> CODEC = StructCodec.struct(
                "invulnerable", Codec.BOOLEAN, Abilities::invulnerable,
                "flying", Codec.BOOLEAN, Abilities::flying,
                "allowFlying", Codec.BOOLEAN, Abilities::allowFlying,
                "instantBreak", Codec.BOOLEAN, Abilities::instantBreak,
                "flySpeed", Codec.FLOAT, Abilities::flySpeed,
                "walkSpeed", Codec.FLOAT, Abilities::walkSpeed,
                Abilities::new);
    }

    private record Inventory(
            int selectedHotbar,
            List<ItemStack> hotbar,
            List<ItemStack> mainInventory,
            List<ItemStack> armor,
            ItemStack offHand,
            ItemStack cursor,
            PlayerState.OpenedWindow openedWindow,
            List<PlayerState.ClickEvent> recentClicks
    ) {
        static Inventory from(PlayerState p) {
            return new Inventory(
                    p.selectedHotbar,
                    itemStackList(p.hotbar),
                    itemStackList(p.mainInventory),
                    itemStackList(p.armor),
                    nullIfAir(p.offHand),
                    nullIfAir(p.cursor),
                    p.openedWindow,
                    new ArrayList<>(p.recentClicks));
        }

        static final StructCodec<Inventory> CODEC = StructCodec.struct(
                "selectedHotbar", Codec.INT, Inventory::selectedHotbar,
                "hotbar", OPTIONAL_ITEM_STACK_LIST, Inventory::hotbar,
                "mainInventory", OPTIONAL_ITEM_STACK_LIST, Inventory::mainInventory,
                "armor", OPTIONAL_ITEM_STACK_LIST, Inventory::armor,
                "offHand", ItemStack.CODEC.optional(), Inventory::offHand,
                "cursor", ItemStack.CODEC.optional(), Inventory::cursor,
                "openedWindow", WebCodecs.OPENED_WINDOW.optional(), Inventory::openedWindow,
                "recentClicks", WebCodecs.CLICK_EVENT.list(), Inventory::recentClicks,
                Inventory::new);
    }

    private record Hud(
            Map<String, PlayerState.ActiveEffect> activeEffects,
            Map<String, Double> attributes,
            PlayerState.ScoreboardSnapshot scoreboard,
            Map<String, PlayerState.BossBarSnapshot> bossBars,
            PlayerState.TabListSnapshot tabList,
            Component lastActionBar
    ) {
        static Hud from(PlayerState p) {
            return new Hud(
                    new LinkedHashMap<>(p.activeEffects),
                    new LinkedHashMap<>(p.attributes),
                    p.scoreboard,
                    PatchValue.bossBars(p),
                    p.tabList,
                    p.lastActionBar);
        }

        static final StructCodec<Hud> CODEC = StructCodec.struct(
                "activeEffects", Codec.STRING.mapValue(WebCodecs.ACTIVE_EFFECT), Hud::activeEffects,
                "attributes", Codec.STRING.mapValue(Codec.DOUBLE), Hud::attributes,
                "scoreboard", WebCodecs.SCOREBOARD.optional(), Hud::scoreboard,
                "bossBars", Codec.STRING.mapValue(WebCodecs.BOSS_BAR), Hud::bossBars,
                "tabList", WebCodecs.TAB_LIST, Hud::tabList,
                "lastActionBar", Codec.COMPONENT.optional(), Hud::lastActionBar,
                Hud::new);
    }

    private record MetaFeed(
            List<PlayerState.ChatLine> recentChat,
            List<PlayerState.SentChatLine> sentChat,
            Map<String, Object> custom
    ) {
        static MetaFeed from(PlayerState p) {
            return new MetaFeed(tail(p.chatReceived, 24), tail(p.chatSent, 64), new LinkedHashMap<>(p.custom));
        }

        static final StructCodec<MetaFeed> CODEC = StructCodec.struct(
                "recentChat", WebCodecs.CHAT_LINE.list(), MetaFeed::recentChat,
                "sentChat", WebCodecs.SENT_CHAT_LINE.list(), MetaFeed::sentChat,
                "custom", PatchValue.STRING_MAP, MetaFeed::custom,
                MetaFeed::new);
    }

    private record MetaSync(
            long serverDataUpdatedAt,
            net.kyori.adventure.nbt.BinaryTag serverData,
            List<PlayerState.VisibleEntityShort> visibleEntities,
            long statePatchSeq
    ) {
        static MetaSync from(PlayerState p) {
            return new MetaSync(
                    p.serverDataUpdatedAt,
                    p.serverData,
                    PatchValue.visibleEntities(p),
                    p.patchSeq);
        }

        static final StructCodec<MetaSync> CODEC = StructCodec.struct(
                "serverDataUpdatedAt", Codec.LONG, MetaSync::serverDataUpdatedAt,
                "serverData", Codec.NBT, MetaSync::serverData,
                "visibleEntities", WebCodecs.VISIBLE_ENTITY_SHORT.list(), MetaSync::visibleEntities,
                "statePatchSeq", Codec.LONG, MetaSync::statePatchSeq,
                MetaSync::new);
    }

    private record MetaProvenance(Map<String, Provenance> provenance) {
        static MetaProvenance from(PlayerState p) {
            return new MetaProvenance(new LinkedHashMap<>(p.provenance));
        }

        static final StructCodec<MetaProvenance> CODEC = StructCodec.struct(
                "provenance", Codec.STRING.mapValue(WebCodecs.PROVENANCE), MetaProvenance::provenance,
                MetaProvenance::new);
    }

    private static <T> List<T> tail(List<T> source, int n) {
        int size = source.size();
        if (size <= n) return new ArrayList<>(source);
        return new ArrayList<>(source.subList(size - n, size));
    }

}
