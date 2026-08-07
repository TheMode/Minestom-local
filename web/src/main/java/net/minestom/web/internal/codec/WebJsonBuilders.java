package net.minestom.web.internal.codec;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minestom.server.codec.Transcoder;
import net.minestom.server.registry.DynamicRegistry;
import net.minestom.server.registry.Registries;
import net.minestom.server.registry.RegistryKey;
import net.minestom.web.PacketEvent;
import net.minestom.web.PacketRecord;
import net.minestom.web.PlayerState;
import net.minestom.web.Provenance;
import net.minestom.web.internal.codec.WebPayloads.RegistriesPayload;
import net.minestom.web.internal.codec.WebPayloads.RegistryDto;
import net.minestom.web.internal.codec.WebPayloads.RegistryEntryDto;
import net.minestom.web.internal.codec.WebPayloads.VisibleEntityDetail;
import net.minestom.web.internal.http.PacketCatalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// Procedural JSON builders for dashboard payloads that don't map cleanly onto a single
/// [net.minestom.server.codec.StructCodec] (per-field filtering, registry walks, etc.). The DTO
/// records live in [WebPayloads]; the codec constants live in [WebCodecs].
public final class WebJsonBuilders {

    private static final List<Map.Entry<String, Function<Registries, DynamicRegistry<?>>>> CLIENT_REGISTRIES = List.of(
            Map.entry("minecraft:chat_type", Registries::chatType),
            Map.entry("minecraft:worldgen/biome", Registries::biome),
            Map.entry("minecraft:dialog", Registries::dialog),
            Map.entry("minecraft:damage_type", Registries::damageType),
            Map.entry("minecraft:trim_material", Registries::trimMaterial),
            Map.entry("minecraft:trim_pattern", Registries::trimPattern),
            Map.entry("minecraft:banner_pattern", Registries::bannerPattern),
            Map.entry("minecraft:enchantment", Registries::enchantment),
            Map.entry("minecraft:painting_variant", Registries::paintingVariant),
            Map.entry("minecraft:jukebox_song", Registries::jukeboxSong),
            Map.entry("minecraft:instrument", Registries::instrument),
            Map.entry("minecraft:wolf_variant", Registries::wolfVariant),
            Map.entry("minecraft:wolf_sound_variant", Registries::wolfSoundVariant),
            Map.entry("minecraft:cat_variant", Registries::catVariant),
            Map.entry("minecraft:cat_sound_variant", Registries::catSoundVariant),
            Map.entry("minecraft:chicken_variant", Registries::chickenVariant),
            Map.entry("minecraft:chicken_sound_variant", Registries::chickenSoundVariant),
            Map.entry("minecraft:cow_variant", Registries::cowVariant),
            Map.entry("minecraft:cow_sound_variant", Registries::cowSoundVariant),
            Map.entry("minecraft:frog_variant", Registries::frogVariant),
            Map.entry("minecraft:pig_variant", Registries::pigVariant),
            Map.entry("minecraft:pig_sound_variant", Registries::pigSoundVariant),
            Map.entry("minecraft:zombie_nautilus_variant", Registries::zombieNautilusVariant),
            Map.entry("minecraft:world_clock", Registries::worldClock),
            Map.entry("minecraft:timeline", Registries::timeline),
            Map.entry("minecraft:dimension_type", Registries::dimensionType)
    );

    private static final Registries VANILLA = Registries.vanilla();

    private WebJsonBuilders() {}

    public static JsonObject packetRecordJson(PacketRecord record, PacketCatalog.Subject subject) {
        return WebJson.encodeAsObject(WebCodecs.PACKET_EVENT, new PacketEvent(
                record.seq(), record.ts(), record.direction(), record.state(),
                record.className(), record.sizeBytes(),
                subject.id(), subject.label(), subject.groupId(), 0L));
    }

    public static JsonObject playerStateJson(PlayerState player, Transcoder<JsonElement> coder) {
        return PlayerSnapshot.toJson(player, coder);
    }

    public static JsonObject provenanceHistoryJson(PlayerState player, String field) {
        Map<String, List<Provenance.Entry>> out = new LinkedHashMap<>();
        for (var entry : player.provenanceHistory.entrySet()) {
            if (field != null && !field.equals(entry.getKey())) continue;
            out.put(entry.getKey(), new ArrayList<>(entry.getValue()));
        }
        return WebJson.encode(WebCodecs.PROVENANCE_HISTORY, out).getAsJsonObject();
    }

    public static JsonObject visibleEntityJson(PlayerState player, int entityId) {
        PlayerState.VisibleEntity entity = player.visibleEntities.get(entityId);
        if (entity == null) return null;
        return WebJson.encodeAsObject(WebCodecs.VISIBLE_ENTITY_DETAIL, VisibleEntityDetail.from(entity));
    }

    public static JsonObject registriesJson(Registries registries) {
        List<RegistryDto> rows = new ArrayList<>(CLIENT_REGISTRIES.size());
        for (var entry : CLIENT_REGISTRIES) {
            String registryId = entry.getKey();
            DynamicRegistry<?> reg = entry.getValue().apply(registries);
            DynamicRegistry<?> vanillaReg = entry.getValue().apply(VANILLA);
            List<RegistryEntryDto> entries = new ArrayList<>();
            for (RegistryKey<?> key : reg.keys()) {
                String entryId = key.key().asString();
                boolean vanilla = vanillaReg.getKey(key.key()) != null;
                entries.add(new RegistryEntryDto(entryId, vanilla));
            }
            rows.add(new RegistryDto(registryId, entries));
        }
        return WebJson.encodeAsObject(WebCodecs.REGISTRIES, new RegistriesPayload(rows));
    }
}
