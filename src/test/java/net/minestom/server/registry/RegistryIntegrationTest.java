package net.minestom.server.registry;

import net.kyori.adventure.key.Key;
import net.minestom.server.gamedata.DataPack;
import net.minestom.server.network.packet.server.configuration.RegistryDataPacket;
import net.minestom.server.world.DimensionType;
import net.minestom.server.world.biome.Biome;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;


@EnvTest
public class RegistryIntegrationTest {

    @Test
    void testUnnamedPack(Env env) {
        DynamicRegistry<DimensionType> dimensionRegistry = env.process().dimensionType();
        DimensionType dimensionType = DimensionType.builder()
                .ambientLight(2f)
                .build();
        var registryKey = dimensionRegistry.register(Key.key("toocool:fortests"), dimensionType, DataPack.MINESTOM_UNNAMED);
        assertEquals(dimensionType, dimensionRegistry.get(registryKey));
        assertEquals(DataPack.MINESTOM_UNNAMED, dimensionRegistry.getPack(registryKey));
        assertDoesNotThrow(() -> {
            dimensionRegistry.registryDataPacket(env.process(), false);
        }, "Registry data packet should not throw for null pack");
    }

    @Test
    void testDifferentPacksInterlaced(Env env) {
        DynamicRegistry<DimensionType> dimensionRegistry = env.process().dimensionType();
        DimensionType dimensionType = DimensionType.builder()
                .ambientLight(2f)
                .build();
        assertDoesNotThrow(() -> dimensionRegistry.register(Key.key("toocool:fortests"), dimensionType, DataPack.MINESTOM_UNNAMED));
        assertDoesNotThrow(() -> dimensionRegistry.register(Key.key("toocool:fortests2"), dimensionType, DataPack.MINECRAFT_CORE));
    }

    @Test
    void registryDataPacketReplacesWireOrder() {
        Registries registries = Registries.vanilla();
        Registries.applyRegistryDataPacket(registries, new RegistryDataPacket("minecraft:worldgen/biome", List.of(
                new RegistryDataPacket.Entry("example:first", null),
                new RegistryDataPacket.Entry("minecraft:plains", null),
                new RegistryDataPacket.Entry("example:last", null)
        )));

        Registry<Biome> biomes = registries.biome();
        RegistryKey<Biome> first = biomes.getKey(0);
        RegistryKey<Biome> plains = biomes.getKey(1);
        RegistryKey<Biome> last = biomes.getKey(2);

        assertNotNull(first);
        assertNotNull(plains);
        assertNotNull(last);
        assertEquals("example:first", first.key().asString());
        assertEquals("minecraft:plains", plains.key().asString());
        assertEquals("example:last", last.key().asString());
        assertEquals(0, biomes.getId(first));
        assertEquals(1, biomes.getId(plains));
        assertEquals(2, biomes.getId(last));
    }
}
