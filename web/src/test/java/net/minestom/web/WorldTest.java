package net.minestom.web;

import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.CoordConversion;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.PlayerHand;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.block.BlockFace;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.AcknowledgeBlockChangePacket;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.ChunkDataPacket;
import net.minestom.server.network.packet.server.play.JoinGamePacket;
import net.minestom.server.network.packet.server.play.MultiBlockChangePacket;
import net.minestom.server.network.packet.server.play.UnloadChunkPacket;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import net.minestom.server.network.packet.server.play.data.LightData;
import net.minestom.web.internal.state.StateApplier;
import org.junit.jupiter.api.Test;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static net.minestom.server.coordinate.CoordConversion.globalToChunk;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Per-packet behavioural tests for [PlayerWorld] and [net.minestom.web.internal.state.WorldUpdaters].
class WorldTest {

    private final PlayerState state = new PlayerState();

    WorldTest() {
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new JoinGamePacket(0, false, List.of("minecraft:overworld"), 20, 8, 8, false, true, true,
                        0, "minecraft:overworld", 0L, GameMode.SURVIVAL, null, false, false, null, 0, 63, false));
    }

    @Test
    void chunkDataSeedsColumnHeights() {
        final long[] encoded = encodeHeightmap(world -> {
            for (int z = 0; z < 16; z++) world[(z << 4) | 5] = 100;
        });
        final Map<Heightmap.Type, long[]> heightmaps = new LinkedHashMap<>();
        heightmaps.put(Heightmap.Type.WORLD_SURFACE, encoded);
        final ChunkData chunkData = new ChunkData(heightmaps, new byte[0], Map.of());
        final LightData light = new LightData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of());
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new ChunkDataPacket(3, -7, chunkData, light));

        final PlayerWorld.Chunk chunk = state.world.getChunk(3, -7);
        assertNotNull(chunk);
        assertEquals(100, chunk.heights[(0 << 4) | 5]);
        assertEquals(100, chunk.heights[(15 << 4) | 5]);
        assertEquals(PlayerWorld.UNKNOWN, chunk.heights[(0 << 4) | 0]);
        assertTrue(state.world.dirtyChunks.contains(CoordConversion.chunkIndex(3, -7)));
    }

    @Test
    void blockChangeRaisesColumn() {
        seedFlatChunk(0, 0, 64);
        state.world.dirtyChunks.clear();

        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new BlockChangePacket(new BlockVec(7, 80, 4), Block.STONE.stateId()));

        final PlayerWorld.Chunk chunk = state.world.getChunk(0, 0);
        assertEquals(80, chunk.heights[(4 << 4) | 7]);
        assertTrue(state.world.dirtyChunks.contains(CoordConversion.chunkIndex(0, 0)));
    }

    @Test
    void blockChangeToAirLowersColumnOnlyIfAtTop() {
        seedFlatChunk(0, 0, 64);
        state.world.dirtyChunks.clear();

        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new BlockChangePacket(new BlockVec(0, 30, 0), 0));
        assertEquals(64, state.world.getChunk(0, 0).heights[0]);

        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new BlockChangePacket(new BlockVec(0, 64, 0), 0));
        assertEquals(63, state.world.getChunk(0, 0).heights[0]);
    }

    @Test
    void multiBlockChangeAppliesAllEntries() {
        seedFlatChunk(2, 5, 64);
        state.world.dirtyChunks.clear();

        final long entry = ((long) 100 << 12) | ((3 & 0xF) << 8) | ((11 & 0xF) << 4) | (8 & 0xF);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new MultiBlockChangePacket(2, 4, 5, new long[]{entry}));

        assertEquals(72, state.world.getChunk(2, 5).heights[(11 << 4) | 3]);
    }

    @Test
    void unloadDropsTheChunk() {
        seedFlatChunk(1, 1, 64);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new UnloadChunkPacket(1, 1));
        assertNull(state.world.getChunk(1, 1));
        assertTrue(state.world.unloadedChunks.contains(CoordConversion.chunkIndex(1, 1)));
    }

    @Test
    void placementSequencePendingUntilAcknowledged() {
        final ClientPlayerBlockPlacementPacket place = new ClientPlayerBlockPlacementPacket(
                PlayerHand.MAIN, new BlockVec(5, 80, 5), BlockFace.TOP,
                0f, 0f, 0f, false, false, 7);
        StateApplier.applyPacket(state, Direction.SERVERBOUND, ConnectionState.PLAY, place);
        assertNotNull(state.world.pendingChanges.get(7));
        assertEquals(PlayerWorld.PredictedBlockChange.Kind.PLACE,
                state.world.pendingChanges.get(7).kind());

        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new AcknowledgeBlockChangePacket(9));
        assertFalse(state.world.pendingChanges.containsKey(7));
    }

    @Test
    void diggingSequencePendingUntilAcknowledged() {
        final ClientPlayerActionPacket dig = new ClientPlayerActionPacket(
                ClientPlayerActionPacket.Status.FINISHED_DIGGING, new BlockVec(2, 64, 2),
                BlockFace.TOP, 11);
        StateApplier.applyPacket(state, Direction.SERVERBOUND, ConnectionState.PLAY, dig);
        assertEquals(PlayerWorld.PredictedBlockChange.Kind.BREAK,
                state.world.pendingChanges.get(11).kind());

        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new AcknowledgeBlockChangePacket(11));
        assertFalse(state.world.pendingChanges.containsKey(11));
    }

    @Test
    void getBlockStateIdWhenChunkNotLoaded() {
        assertEquals(-1, state.world.getBlockStateId(0, 64, 0));
    }

    private void seedFlatChunk(int chunkX, int chunkZ, int y) {
        final short[] heights = new short[PlayerWorld.COLUMNS_PER_CHUNK];
        for (int i = 0; i < heights.length; i++) heights[i] = (short) y;
        state.world.putChunk(PlayerWorld.Chunk.heightsOnly(
                chunkX, chunkZ, globalToChunk(state.world.dimensionMinY), heights));
    }

    private long[] encodeHeightmap(java.util.function.Consumer<short[]> shaper) {
        final short[] heights = new short[256];
        for (int i = 0; i < 256; i++) heights[i] = -1;
        shaper.accept(heights);
        final int minOffset = -64 - 1;
        final int bits = 32 - Integer.numberOfLeadingZeros(384);
        final int entriesPerLong = 64 / bits;
        final long[] out = new long[(256 + entriesPerLong - 1) / entriesPerLong];
        int container = 0;
        for (int i = 0; i < 256; i++) {
            final int idx = i % entriesPerLong;
            final long stored = heights[i] < 0 ? 0L : (heights[i] - minOffset);
            out[container] |= (stored & ((1L << bits) - 1)) << (idx * bits);
            if (idx == entriesPerLong - 1) container++;
        }
        return out;
    }
}
