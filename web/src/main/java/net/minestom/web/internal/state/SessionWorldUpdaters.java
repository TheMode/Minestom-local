package net.minestom.web.internal.state;

import net.minestom.server.entity.GameMode;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.common.ClientPluginMessagePacket;
import net.minestom.server.network.packet.client.common.ClientSettingsPacket;
import net.minestom.server.network.packet.client.handshake.ClientHandshakePacket;
import net.minestom.server.network.packet.client.login.ClientLoginStartPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionAndRotationPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerRotationPacket;
import net.minestom.server.network.packet.server.common.PluginMessagePacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.login.SetCompressionPacket;
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket;
import net.minestom.server.network.packet.server.play.JoinGamePacket;
import net.minestom.server.network.packet.server.play.RespawnPacket;
import net.minestom.server.world.DimensionType;
import net.minestom.web.PlayerState;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static net.minestom.web.internal.state.StateApplier.entry;
import static net.minestom.web.internal.state.StateApplier.listeners;

/// Identity, world/dimension, gamemode, and player position/rotation. The "session shell" —
/// everything that frames a player's place in the world before vitals or inventory.
final class SessionWorldUpdaters {

    /// Plugin-message channel for the server/client brand exchange — same constant the vanilla
    /// `PluginMessagePacket.brandPacket` factory writes.
    private static final String BRAND_CHANNEL = "minecraft:brand";
    /// Dimension keys whose `min_y` is 0 and height is 256 (no overworld-style negative-Y).
    private static final String NETHER = DimensionType.THE_NETHER.name();
    private static final String END = DimensionType.THE_END.name();

    static final Map<Class<? extends Packet>, StateApplier.Updater<?>> LISTENERS = listeners(
            entry(ClientHandshakePacket.class, (s, _, _, p) ->
                    s.protocolVersion = s.set("protocolVersion", s.protocolVersion, p.protocolVersion())),
            entry(ClientLoginStartPacket.class, (s, _, _, p) -> {
                s.username = s.set("username", s.username, p.username());
                s.uuid = s.set("uuid", s.uuid, p.profileId());
            }),
            entry(LoginSuccessPacket.class, (s, _, _, p) -> {
                s.username = s.set("username", s.username, p.gameProfile().name());
                s.uuid = s.set("uuid", s.uuid, p.gameProfile().uuid());
            }),
            entry(SetCompressionPacket.class, (s, _, _, p) ->
                    s.traffic.compressionThreshold = s.set("traffic.compressionThreshold",
                            s.traffic.compressionThreshold, p.threshold())),
            entry(ClientSettingsPacket.class, (s, _, _, p) ->
                    s.locale = s.set("locale", s.locale, p.settings().locale().toLanguageTag())),
            entry(PluginMessagePacket.class, (s, _, _, p) -> {
                if (BRAND_CHANNEL.equals(p.channel()))
                    s.serverBrand = s.set("serverBrand", s.serverBrand, new String(p.data(), StandardCharsets.UTF_8));
            }),
            entry(ClientPluginMessagePacket.class, (s, _, _, p) -> {
                if (BRAND_CHANNEL.equals(p.channel()))
                    s.clientBrand = s.set("clientBrand", s.clientBrand, new String(p.data(), StandardCharsets.UTF_8));
            }),
            entry(JoinGamePacket.class, (s, _, _, p) -> {
                s.dimension = s.set("dimension", s.dimension, p.world());
                s.hardcore = s.set("hardcore", s.hardcore, p.isHardcore());
                s.gamemode = s.set("gamemode", s.gamemode, p.gameMode().name());
                resetForDimension(s, p.world());
            }),
            entry(RespawnPacket.class, (s, _, _, p) -> {
                s.dimension = s.set("dimension", s.dimension, p.worldName());
                s.gamemode = s.set("gamemode", s.gamemode, p.gameMode().name());
                resetForDimension(s, p.worldName());
            }),
            entry(ChangeGameStatePacket.class, (s, _, _, p) -> {
                if (p.reason() != ChangeGameStatePacket.Reason.CHANGE_GAMEMODE) return;
                int ord = (int) p.value();
                GameMode[] modes = GameMode.values();
                if (ord >= 0 && ord < modes.length)
                    s.gamemode = s.set("gamemode", s.gamemode, modes[ord].name());
            }),
            entry(ClientPlayerPositionPacket.class, (s, _, _, p) -> {
                setPos(s, p.position());
                s.onGround = s.set("onGround", s.onGround, p.onGround());
            }),
            entry(ClientPlayerPositionAndRotationPacket.class, (s, _, _, p) -> {
                setPos(s, p.position());
                setRot(s, p.position().yaw(), p.position().pitch());
                s.onGround = s.set("onGround", s.onGround, p.onGround());
            }),
            entry(ClientPlayerRotationPacket.class, (s, _, _, p) -> {
                setRot(s, p.yaw(), p.pitch());
                s.onGround = s.set("onGround", s.onGround, p.onGround());
            }));

    private SessionWorldUpdaters() {
    }

    private static void setPos(PlayerState s, net.minestom.server.coordinate.Point pos) {
        s.posX = s.set("posX", s.posX, pos.x());
        s.posY = s.set("posY", s.posY, pos.y());
        s.posZ = s.set("posZ", s.posZ, pos.z());
    }

    private static void setRot(PlayerState s, float yaw, float pitch) {
        s.yaw = s.set("yaw", s.yaw, yaw);
        s.pitch = s.set("pitch", s.pitch, pitch);
    }

    /// Wipe per-dimension state and re-seed minY/height so the next chunk-data decode picks the
    /// right bits-per-entry. We can't read [net.minestom.server.world.DimensionType] from a
    /// packet, so we follow vanilla defaults; custom dimensions decode into the right shape with
    /// absolute Y slightly off until corrected by the next chunk. Visible entities are cleared
    /// too — vanilla doesn't re-send `DestroyEntitiesPacket` across dimensions.
    private static void resetForDimension(PlayerState s, String worldName) {
        s.world.clear();
        s.visibleEntities.clear();
        s.markDirty("visibleEntities");
        final boolean tall = worldName == null || !(NETHER.equals(worldName) || END.equals(worldName));
        s.world.dimensionMinY = tall ? -64 : 0;
        s.world.dimensionHeight = tall ? 384 : 256;
    }
}
