package net.minestom.web.internal.http;

import net.minestom.server.codec.Codec;
import net.minestom.server.codec.StructCodec;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.PacketParser;
import net.minestom.server.network.packet.PacketVanilla;
import net.minestom.server.network.packet.client.ClientPacket;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.client.common.ClientPluginMessagePacket;
import net.minestom.server.network.packet.client.common.ClientPongPacket;
import net.minestom.server.network.packet.client.common.ClientSettingsPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.play.*;
import net.minestom.server.network.packet.server.ServerPacket;
import net.minestom.server.network.packet.server.common.DisconnectPacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.server.network.packet.server.common.PingPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.web.Direction;
import net.minestom.web.PacketRecord;

import java.util.*;
import java.util.function.Function;

/// Static directory of every Minestom packet class plus the *subject* it mutates (Self,
/// Entities, World, HUD, Windows, Chat, Network). Used by:
///   - the dashboard packet picker (entries, name resolution, direction lookup),
///   - the routine action runner (resolve a short name → fully-qualified class),
///   - the packet stream view in the UI (per-row subject chip, drilldown filtering).
///
/// One file because the two halves share `Packet`-class lookups. Classification is driven by a
/// single class→subject table ([#SUBJECT_BY_CLASS]) so a packet is registered exactly once.
public final class PacketCatalog {

    // --------------------------------------------------------------- catalog (every packet)

    public record Entry(String simple, String full, String side, String state) {
        public static final Codec<Entry> CODEC = StructCodec.struct(
                "simple", Codec.STRING, Entry::simple,
                "full", Codec.STRING, Entry::full,
                "side", Codec.STRING, Entry::side,
                "state", Codec.STRING, Entry::state,
                Entry::new);

        public static final Codec<List<Entry>> LIST_CODEC = CODEC.list();
    }

    private static final List<Entry> ENTRIES = buildEntries();
    private static final List<Entry> ANALYZABLE;
    private static final Map<String, String> SIMPLE_TO_FULL = new HashMap<>();

    static {
        // Last write wins; conflicts are extraordinarily rare since vanilla simple names are unique.
        for (Entry e : ENTRIES) SIMPLE_TO_FULL.put(e.simple.toLowerCase(), e.full);
        List<Entry> analyzable = new ArrayList<>();
        for (Entry e : ENTRIES) {
            // Catch Throwable so an individual packet whose class init throws
            // (LinkageError, NoClassDefFoundError, ExceptionInInitializerError) only gets
            // dropped from the analyzable subset rather than failing the whole catalog.
            try {
                if (PacketSchema.isAnalyzable(Class.forName(e.full))) analyzable.add(e);
            } catch (Throwable ignored) {
            }
        }
        ANALYZABLE = List.copyOf(analyzable);
    }

    public static List<Entry> entries() {
        return ENTRIES;
    }

    /// Subset of [#entries] whose record components fully resolve to known widget kinds.
    /// The dashboard packet picker uses this when `?analyzable=true`; name resolution /
    /// classification / direction lookups stay on the full catalog so non-analyzable
    /// packets keep working everywhere else (kick, OnPacket triggers, replay).
    public static List<Entry> entriesAnalyzable() {
        return ANALYZABLE;
    }

    /// Resolve a user-typed simple name (case-insensitive) to its fully-qualified class name.
    /// Returns the input untouched if it already contains a `.` or no match is found.
    public static String resolve(String name) {
        if (name == null || name.isBlank() || name.indexOf('.') >= 0) return name;
        return SIMPLE_TO_FULL.getOrDefault(name.toLowerCase(), name);
    }

    @SuppressWarnings("unchecked")
    public static Class<? extends Packet> packetClass(String classNameOrSimple) throws ClassNotFoundException {
        Class<?> cls = Class.forName(resolve(classNameOrSimple));
        if (Packet.class.isAssignableFrom(cls)) return (Class<? extends Packet>) cls;
        throw new IllegalArgumentException("Not a known packet class: " + cls.getName());
    }

    /// Derive the injection direction from a packet class:
    /// {@link ClientPacket}s are sent by the client (serverbound),
    /// {@link ServerPacket}s are sent by the server (clientbound).
    public static Direction directionFor(String classNameOrSimple) throws ClassNotFoundException {
        Class<?> cls = packetClass(classNameOrSimple);
        if (ServerPacket.class.isAssignableFrom(cls)) return Direction.CLIENTBOUND;
        if (ClientPacket.class.isAssignableFrom(cls)) return Direction.SERVERBOUND;
        throw new IllegalArgumentException("Not a known packet class: " + cls.getName());
    }

    private static List<Entry> buildEntries() {
        List<Entry> out = new ArrayList<>();
        for (ConnectionState st : ConnectionState.values()) {
            collect(out, PacketVanilla.CLIENT_PACKET_PARSER, st, "client");
            collect(out, PacketVanilla.SERVER_PACKET_PARSER, st, "server");
        }
        out.sort((a, b) -> a.simple.compareToIgnoreCase(b.simple));
        return List.copyOf(out);
    }

    private static <T> void collect(List<Entry> out, PacketParser<T> parser, ConnectionState st, String side) {
        for (var info : parser.stateRegistry(st).packets()) {
            Class<?> cls = info.packetClass();
            out.add(new Entry(cls.getSimpleName(), cls.getName(), side, st.name()));
        }
    }

    // --------------------------------------------------------------- subject classification

    public enum Group {
        SELF, ENT, WORLD, HUD, WIN, CHAT, NET;

        public String id() {
            return name().toLowerCase();
        }
    }

    public record Subject(String id, String label, Group group) {
        public String groupId() {
            return group.id();
        }
    }

    /// Reusable subjects so [#classify] is allocation-free on the common path.
    private static final Subject SUBJ_ENT_ALL = new Subject("ent.all", "Entities", Group.ENT);
    private static final Subject SUBJ_WIN_INV = new Subject("win.inv", "Inventory", Group.WIN);
    private static final Subject SUBJ_HUD_SCOREBOARD = new Subject("hud.scoreboard", "Scoreboard", Group.HUD);
    private static final Subject SUBJ_HUD_TAB = new Subject("hud.tab", "Tab list", Group.HUD);
    private static final Subject SUBJ_HUD_ACTIONBAR = new Subject("hud.actionbar", "Action bar", Group.HUD);
    private static final Subject SUBJ_HUD_TITLE = new Subject("hud.title", "Title", Group.HUD);
    private static final Subject SUBJ_HUD_MISC = new Subject("hud.misc", "HUD", Group.HUD);
    private static final Subject SUBJ_CHAT = new Subject("chat.system", "Chat", Group.CHAT);
    private static final Subject SUBJ_NET = new Subject("net.io", "Network", Group.NET);

    private static final Subject SUBJ_SELF_VITALS = new Subject("self.vitals", "vitals", Group.SELF);
    private static final Subject SUBJ_SELF_XP = new Subject("self.xp", "xp", Group.SELF);
    private static final Subject SUBJ_SELF_ABILITIES = new Subject("self.abilities", "abilities", Group.SELF);
    private static final Subject SUBJ_SELF_POSITION = new Subject("self.position", "position", Group.SELF);
    private static final Subject SUBJ_SELF_EFFECTS = new Subject("self.effects", "effects", Group.SELF);
    private static final Subject SUBJ_SELF_ATTRIBUTES = new Subject("self.attributes", "attributes", Group.SELF);
    private static final Subject SUBJ_SELF_COMBAT = new Subject("self.combat", "combat", Group.SELF);
    private static final Subject SUBJ_SELF_SESSION = new Subject("self.session", "session", Group.SELF);
    private static final Subject SUBJ_SELF_MISC = new Subject("self.self", "self", Group.SELF);

    private static final Subject SUBJ_WORLD_CHUNK = new Subject("world.chunk", "chunk", Group.WORLD);
    private static final Subject SUBJ_WORLD_BLOCK = new Subject("world.block", "block", Group.WORLD);
    private static final Subject SUBJ_WORLD_LIGHTING = new Subject("world.lighting", "lighting", Group.WORLD);
    private static final Subject SUBJ_WORLD_TIME = new Subject("world.time", "time", Group.WORLD);
    private static final Subject SUBJ_WORLD_VIEWPORT = new Subject("world.viewport", "viewport", Group.WORLD);
    private static final Subject SUBJ_WORLD_MISC = new Subject("world.world", "world", Group.WORLD);

    private static final Map<Class<? extends Packet>, Function<Packet, Subject>> SUBJECT_BY_CLASS = buildSubjectMap();

    /// Static subjects keyed by id, for rehydrating a [Subject] from a persisted id string —
    /// dynamic ids (`ent.42`, `win.5`, `hud.boss.xxxx`) fall through to [#subjectById]'s
    /// prefix-derived path.
    private static final Map<String, Subject> STATIC_SUBJECTS_BY_ID = Map.ofEntries(
            Map.entry(SUBJ_ENT_ALL.id(), SUBJ_ENT_ALL),
            Map.entry(SUBJ_WIN_INV.id(), SUBJ_WIN_INV),
            Map.entry(SUBJ_HUD_SCOREBOARD.id(), SUBJ_HUD_SCOREBOARD),
            Map.entry(SUBJ_HUD_TAB.id(), SUBJ_HUD_TAB),
            Map.entry(SUBJ_HUD_ACTIONBAR.id(), SUBJ_HUD_ACTIONBAR),
            Map.entry(SUBJ_HUD_TITLE.id(), SUBJ_HUD_TITLE),
            Map.entry(SUBJ_HUD_MISC.id(), SUBJ_HUD_MISC),
            Map.entry(SUBJ_CHAT.id(), SUBJ_CHAT),
            Map.entry(SUBJ_NET.id(), SUBJ_NET),
            Map.entry(SUBJ_SELF_VITALS.id(), SUBJ_SELF_VITALS),
            Map.entry(SUBJ_SELF_XP.id(), SUBJ_SELF_XP),
            Map.entry(SUBJ_SELF_ABILITIES.id(), SUBJ_SELF_ABILITIES),
            Map.entry(SUBJ_SELF_POSITION.id(), SUBJ_SELF_POSITION),
            Map.entry(SUBJ_SELF_EFFECTS.id(), SUBJ_SELF_EFFECTS),
            Map.entry(SUBJ_SELF_ATTRIBUTES.id(), SUBJ_SELF_ATTRIBUTES),
            Map.entry(SUBJ_SELF_COMBAT.id(), SUBJ_SELF_COMBAT),
            Map.entry(SUBJ_SELF_SESSION.id(), SUBJ_SELF_SESSION),
            Map.entry(SUBJ_SELF_MISC.id(), SUBJ_SELF_MISC),
            Map.entry(SUBJ_WORLD_CHUNK.id(), SUBJ_WORLD_CHUNK),
            Map.entry(SUBJ_WORLD_BLOCK.id(), SUBJ_WORLD_BLOCK),
            Map.entry(SUBJ_WORLD_LIGHTING.id(), SUBJ_WORLD_LIGHTING),
            Map.entry(SUBJ_WORLD_TIME.id(), SUBJ_WORLD_TIME),
            Map.entry(SUBJ_WORLD_VIEWPORT.id(), SUBJ_WORLD_VIEWPORT),
            Map.entry(SUBJ_WORLD_MISC.id(), SUBJ_WORLD_MISC));

    /// Rehydrate a [Subject] from a persisted id, so [net.minestom.web.internal.persist.PersistentHistory]
    /// can drop `subject_label`/`subject_group` columns and derive them on read. Static subjects
    /// hit the map directly; dynamic ids (`ent.N`, `win.N`, `hud.boss.UUID`) reconstruct a
    /// synthetic label and group from the prefix.
    public static Subject subjectById(String id) {
        if (id == null || id.isEmpty()) return SUBJ_NET;
        final Subject hit = STATIC_SUBJECTS_BY_ID.get(id);
        if (hit != null) return hit;
        final int dot = id.indexOf('.');
        final String prefix = dot < 0 ? id : id.substring(0, dot);
        final Group group = switch (prefix) {
            case "ent" -> Group.ENT;
            case "win" -> Group.WIN;
            case "hud" -> Group.HUD;
            case "self" -> Group.SELF;
            case "world" -> Group.WORLD;
            case "chat" -> Group.CHAT;
            default -> Group.NET;
        };
        final String label = switch (group) {
            case ENT -> "Entity #" + id.substring(dot + 1);
            case WIN -> "Window #" + id.substring(dot + 1);
            case HUD -> id.startsWith("hud.boss.")
                    ? "BossBar " + id.substring(9, Math.min(17, id.length()))
                    : id;
            default -> id;
        };
        return new Subject(id, label, group);
    }

    /// The single source of truth for classification: each packet class maps to the function that
    /// produces its [Subject]. Static subjects use [#constant]; entity/window/bossbar subjects carry
    /// per-packet ids so they resolve against the packet instance. Anything unlisted is [#SUBJ_NET].
    private static Map<Class<? extends Packet>, Function<Packet, Subject>> buildSubjectMap() {
        Map<Class<? extends Packet>, Function<Packet, Subject>> m = new HashMap<>();
        // self — vitals / xp / abilities / position / effects / attributes / combat / session
        put(m, constant(SUBJ_SELF_VITALS), UpdateHealthPacket.class);
        put(m, constant(SUBJ_SELF_XP), SetExperiencePacket.class);
        put(m, constant(SUBJ_SELF_ABILITIES), PlayerAbilitiesPacket.class);
        put(m, constant(SUBJ_SELF_POSITION), ClientPlayerPositionPacket.class,
                ClientPlayerPositionAndRotationPacket.class, ClientPlayerRotationPacket.class,
                PlayerPositionAndLookPacket.class);
        put(m, constant(SUBJ_SELF_EFFECTS), EntityEffectPacket.class, RemoveEntityEffectPacket.class);
        put(m, constant(SUBJ_SELF_ATTRIBUTES), EntityAttributesPacket.class);
        put(m, constant(SUBJ_SELF_COMBAT), DamageEventPacket.class);
        put(m, constant(SUBJ_SELF_SESSION), JoinGamePacket.class, RespawnPacket.class, ChangeGameStatePacket.class);
        // entity-scoped — id/label drilldown via entitySubject
        put(m, PacketCatalog::entitySubject,
                SpawnEntityPacket.class, EntityPositionPacket.class, EntityPositionAndRotationPacket.class,
                EntityRotationPacket.class, EntityPositionSyncPacket.class, EntityTeleportPacket.class,
                EntityMetaDataPacket.class, EntityHeadLookPacket.class, EntityVelocityPacket.class,
                EntityAnimationPacket.class, EntityStatusPacket.class, EntityEquipmentPacket.class,
                DestroyEntitiesPacket.class);
        // world — chunks, blocks, lighting, time, viewport
        put(m, constant(SUBJ_WORLD_CHUNK), ChunkDataPacket.class, UnloadChunkPacket.class);
        put(m, constant(SUBJ_WORLD_BLOCK), BlockChangePacket.class, MultiBlockChangePacket.class,
                BlockBreakAnimationPacket.class, BlockEntityDataPacket.class);
        put(m, constant(SUBJ_WORLD_LIGHTING), UpdateLightPacket.class);
        put(m, constant(SUBJ_WORLD_TIME), SetTimePacket.class);
        put(m, constant(SUBJ_WORLD_VIEWPORT), UpdateViewPositionPacket.class, UpdateViewDistancePacket.class);
        put(m, constant(SUBJ_WORLD_MISC), ServerDifficultyPacket.class);
        // HUD — dynamic bossbar + scoreboard / tab / action bar / title
        put(m, PacketCatalog::bossSubject, BossBarPacket.class);
        put(m, constant(SUBJ_HUD_SCOREBOARD), DisplayScoreboardPacket.class,
                ScoreboardObjectivePacket.class, UpdateScorePacket.class);
        put(m, constant(SUBJ_HUD_TAB), PlayerListHeaderAndFooterPacket.class,
                PlayerInfoUpdatePacket.class, PlayerInfoRemovePacket.class);
        put(m, constant(SUBJ_HUD_ACTIONBAR), ActionBarPacket.class);
        put(m, constant(SUBJ_HUD_TITLE), SetTitleTextPacket.class, SetTitleSubTitlePacket.class,
                SetTitleTimePacket.class, ClearTitlesPacket.class);
        // windows / inventory — id/label drilldown via windowSubject
        put(m, PacketCatalog::windowSubject,
                OpenWindowPacket.class, CloseWindowPacket.class, SetSlotPacket.class,
                SetPlayerInventorySlotPacket.class, WindowItemsPacket.class, SetCursorItemPacket.class,
                HeldItemChangePacket.class, ClientHeldItemChangePacket.class, WindowPropertyPacket.class);
        // chat
        put(m, constant(SUBJ_CHAT), SystemChatPacket.class, PlayerChatMessagePacket.class,
                ClientChatMessagePacket.class, ClientCommandChatPacket.class, ClientSignedCommandChatPacket.class);
        // network / common
        put(m, constant(SUBJ_NET), KeepAlivePacket.class, ClientKeepAlivePacket.class, PingPacket.class,
                ClientPongPacket.class, PluginMessagePacket.class, ClientPluginMessagePacket.class,
                SetCompressionPacket.class, LoginSuccessPacket.class, ClientLoginStartPacket.class,
                ClientHandshakePacket.class, ClientSettingsPacket.class, DisconnectPacket.class);
        return Map.copyOf(m);
    }

    @SafeVarargs
    private static void put(Map<Class<? extends Packet>, Function<Packet, Subject>> m,
                            Function<Packet, Subject> subject, Class<? extends Packet>... classes) {
        for (Class<? extends Packet> c : classes) m.put(c, subject);
    }

    private static Function<Packet, Subject> constant(Subject subject) {
        return packet -> subject;
    }

    public static Subject classify(PacketRecord record) {
        return classify(record.record());
    }

    public static Subject classify(Packet packet) {
        if (packet == null) return SUBJ_NET;
        final Function<Packet, Subject> fn = SUBJECT_BY_CLASS.get(packet.getClass());
        return fn == null ? SUBJ_NET : fn.apply(packet);
    }

    private static Subject entitySubject(Packet packet) {
        final Integer id = switch (packet) {
            case SpawnEntityPacket p -> p.entityId();
            case EntityPositionPacket p -> p.entityId();
            case EntityPositionAndRotationPacket p -> p.entityId();
            case EntityRotationPacket p -> p.entityId();
            case EntityPositionSyncPacket p -> p.entityId();
            case EntityTeleportPacket p -> p.entityId();
            case EntityMetaDataPacket p -> p.entityId();
            case EntityHeadLookPacket p -> p.entityId();
            case EntityVelocityPacket p -> p.entityId();
            case EntityAnimationPacket p -> p.entityId();
            case EntityStatusPacket p -> p.entityId();
            case EntityEquipmentPacket p -> p.entityId();
            case DestroyEntitiesPacket p -> p.entityIds().size() == 1 ? p.entityIds().getFirst() : null;
            default -> null;
        };
        return id == null ? SUBJ_ENT_ALL : new Subject("ent." + id, "Entity #" + id, Group.ENT);
    }

    private static Subject windowSubject(Packet packet) {
        final int id = switch (packet) {
            case OpenWindowPacket p -> p.windowId();
            case CloseWindowPacket p -> p.windowId();
            case SetSlotPacket p -> p.windowId();
            case WindowItemsPacket p -> p.windowId();
            case WindowPropertyPacket p -> p.windowId();
            // always the player inventory
            case HeldItemChangePacket _, ClientHeldItemChangePacket _,
                 SetPlayerInventorySlotPacket _, SetCursorItemPacket _ -> 0;
            default -> -1;
        };
        if (id == 0 || id == -1) return SUBJ_WIN_INV;
        return new Subject("win." + id, "Window #" + id, Group.WIN);
    }

    private static Subject bossSubject(Packet packet) {
        final String s = ((BossBarPacket) packet).uuid().toString();
        return new Subject("hud.boss." + s, "BossBar " + s.substring(0, Math.min(8, s.length())), Group.HUD);
    }

    private PacketCatalog() {}
}
