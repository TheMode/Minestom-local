package net.minestom.web.internal.state;

import net.minestom.server.coordinate.Point;
import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.heightmap.Heightmap;
import net.minestom.server.instance.palette.Palette;
import net.minestom.server.network.NetworkBuffer;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.client.play.ClientPlayerActionPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerBlockPlacementPacket;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.server.network.packet.server.play.data.ChunkData;
import net.minestom.web.PlayerState;
import net.minestom.web.PlayerWorld;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;

import static net.minestom.server.coordinate.CoordConversion.*;
import static net.minestom.web.PlayerWorld.*;
import static net.minestom.web.internal.state.StateApplier.entry;
import static net.minestom.web.internal.state.StateApplier.listeners;

/// World state mutators. Mirrors the chunk / block packet stream the vanilla client follows:
/// chunk data primes palettes and columns, block / section updates patch them, unload drops
/// them. Serverbound place / dig sequences land in [PlayerWorld#pendingChanges] until
/// [AcknowledgeBlockChangePacket] clears them.
final class WorldUpdaters {

    private static final NetworkBuffer.Type<ChunkData.Section> SECTION_SERIALIZER = ChunkData.Section.networkType(64);

    static final Map<Class<? extends Packet>, StateApplier.Updater<?>> LISTENERS = listeners(
            entry(ChunkDataPacket.class, (s, _, _, p) -> applyChunkData(s, p)),
            entry(UnloadChunkPacket.class, (s, _, _, p) -> applyUnload(s, p)),
            entry(BlockChangePacket.class, (s, _, _, p) -> applyBlock(s,
                    p.blockPosition().blockX(), p.blockPosition().blockY(), p.blockPosition().blockZ(),
                    p.blockStateId())),
            entry(MultiBlockChangePacket.class, (s, _, _, p) -> applyMultiBlockChange(s, p)),
            entry(BlockEntityDataPacket.class, (s, _, _, p) -> applyBlockEntityData(s, p)),
            entry(ClientPlayerBlockPlacementPacket.class, (s, _, _, p) ->
                    record(s, p.sequence(), p.blockPosition(), PredictedBlockChange.Kind.PLACE)),
            entry(ClientPlayerActionPacket.class, (s, _, _, p) -> {
                if (p.status() == ClientPlayerActionPacket.Status.FINISHED_DIGGING) {
                    record(s, p.sequence(), p.blockPosition(), PredictedBlockChange.Kind.BREAK);
                }
            }),
            entry(AcknowledgeBlockChangePacket.class, (s, _, _, p) ->
                    s.world.pendingChanges.entrySet().removeIf(e -> e.getKey() <= p.sequence())));

    private WorldUpdaters() {
    }

    private static short[] decodeHeightmap(long[] data, int dimensionHeight, int dimensionMinY) {
        if (data == null || data.length == 0) return null;
        final int bitsPerEntry = 32 - Integer.numberOfLeadingZeros(Math.max(1, dimensionHeight));
        if (bitsPerEntry < 1 || bitsPerEntry > 31) return null;
        final int entriesPerLong = 64 / bitsPerEntry;
        final long mask = (1L << bitsPerEntry) - 1;
        final int absOffset = dimensionMinY - 1;
        final short[] out = new short[COLUMNS_PER_CHUNK];
        int containerIndex = 0;
        for (int i = 0; i < COLUMNS_PER_CHUNK; i++) {
            final int indexInContainer = i % entriesPerLong;
            if (containerIndex >= data.length) {
                out[i] = UNKNOWN;
                continue;
            }
            final long entry = (data[containerIndex] >>> (indexInContainer * bitsPerEntry)) & mask;
            out[i] = entry == 0 ? UNKNOWN : (short) (entry + absOffset);
            if (indexInContainer == entriesPerLong - 1) containerIndex++;
        }
        return out;
    }

    private static void applyChunkData(PlayerState s, ChunkDataPacket p) {
        final Map<Heightmap.Type, long[]> map = p.chunkData().heightmaps();
        if (map.isEmpty()) return;
        long[] longs = map.get(Heightmap.Type.WORLD_SURFACE);
        if (longs == null) longs = map.get(Heightmap.Type.MOTION_BLOCKING);
        if (longs == null) return;
        final short[] heights = decodeHeightmap(longs, s.world.dimensionHeight, s.world.dimensionMinY);
        if (heights == null) return;

        final int minSection = globalToChunk(s.world.dimensionMinY);
        final int maxSection = minSection + (s.world.dimensionHeight / SECTION_SIZE);
        final Palette[] palettes = parseSections(p.chunkData().data(), minSection, maxSection);

        int[] colors = null;
        if (palettes != null) {
            colors = readColumnColors(palettes, heights, minSection, maxSection);
        }
        if (colors == null) {
            colors = new int[COLUMNS_PER_CHUNK];
            Arrays.fill(colors, UNKNOWN_COLOR);
        }

        final long key = chunkIndex(p.chunkX(), p.chunkZ());
        final PlayerWorld.Chunk chunk = new PlayerWorld.Chunk(
                p.chunkX(), p.chunkZ(), minSection,
                palettes,
                p.chunkData().blockEntities(),
                heights, colors);
        s.world.putChunk(chunk);
        s.world.dirtyChunks.add(key);
        s.world.unloadedChunks.remove(key);
    }

    private static Palette[] parseSections(byte[] data, int minSection, int maxSection) {
        if (data == null || data.length == 0) return null;
        final int sectionCount = maxSection - minSection;
        if (sectionCount <= 0) return null;
        final NetworkBuffer buffer = NetworkBuffer.wrap(data, 0, data.length);
        final Palette[] palettes = new Palette[sectionCount];
        try {
            for (int s = 0; s < sectionCount; s++) {
                ChunkData.Section section = SECTION_SERIALIZER.read(buffer);
                palettes[s] = section.blockStates();
            }
        } catch (Throwable t) {
            return null;
        }
        return palettes;
    }

    private static int[] readColumnColors(Palette[] palettes, short[] heights, int minSection, int maxSection) {
        if (palettes == null || heights == null) return null;
        final int sectionCount = maxSection - minSection;
        if (sectionCount <= 0 || palettes.length != sectionCount) return null;

        final int[] out = new int[COLUMNS_PER_CHUNK];
        for (int z = 0; z < SECTION_SIZE; z++) {
            for (int x = 0; x < SECTION_SIZE; x++) {
                final int idx = (z << 4) | x;
                final short worldY = heights[idx];
                if (worldY == UNKNOWN) {
                    out[idx] = BlockColors.VOID;
                    continue;
                }
                final int relIndex = Math.floorDiv(worldY, SECTION_SIZE) - minSection;
                if (relIndex < 0 || relIndex >= palettes.length || palettes[relIndex] == null) {
                    out[idx] = BlockColors.UNKNOWN;
                    continue;
                }
                final Block block = Block.fromStateId(palettes[relIndex].get(
                        x, Math.floorMod(worldY, SECTION_SIZE), z));
                out[idx] = block == null ? BlockColors.UNKNOWN : BlockColors.colorOf(block);
            }
        }
        return out;
    }

    private static void applyUnload(PlayerState s, UnloadChunkPacket p) {
        final long key = chunkIndex(p.chunkX(), p.chunkZ());
        if (s.world.chunks.remove(key) != null) {
            s.world.dirtyChunks.remove(key);
            s.world.unloadedChunks.add(key);
        }
    }

    private static void applyMultiBlockChange(PlayerState s, MultiBlockChangePacket p) {
        final long pos = p.chunkSectionPosition();
        final int chunkX = (int) (pos >> 42);
        final int chunkZ = (int) (pos << 22 >> 42);
        final int sectionY = (int) (pos << 44 >> 44);
        for (long entry : p.blocks()) {
            final int index = (int) (entry & 0xFFF);
            final int stateId = (int) (entry >>> 12);
            final int localX = sectionBlockIndexGetX(index);
            final int localY = sectionBlockIndexGetY(index);
            final int localZ = sectionBlockIndexGetZ(index);
            final Point block = chunkBlockRelativeGetGlobal(
                    localX, sectionY * SECTION_SIZE + localY, localZ, chunkX, chunkZ);
            applyBlock(s, block.blockX(), block.blockY(), block.blockZ(), stateId);
        }
    }

    private static void applyBlockEntityData(PlayerState s, BlockEntityDataPacket p) {
        final int wx = p.blockPosition().blockX();
        final int wy = p.blockPosition().blockY();
        final int wz = p.blockPosition().blockZ();
        final PlayerWorld.Chunk chunk = s.world.getChunkAtBlock(wx, wz);
        if (chunk == null) return;
        final Block base = Block.fromKey(p.type().key());
        if (base == null) return;
        final Block block = p.data() == null ? base : base.withNbt(p.data());
        if (!block.registry().isBlockEntity()) return;
        // Block entities are keyed by chunk-local block index in ChunkData.
        chunk.blockEntities.put(chunkBlockIndex(wx, wy, wz), block);
        s.world.dirtyChunks.add(chunkIndex(chunk.chunkX, chunk.chunkZ));
    }

    /// Lazily allocate the per-column color cache, filled with [#UNKNOWN_COLOR].
    private static void ensureColumnColors(PlayerWorld.Chunk chunk) {
        if (chunk.columnColors == null) {
            chunk.columnColors = new int[COLUMNS_PER_CHUNK];
            Arrays.fill(chunk.columnColors, UNKNOWN_COLOR);
        }
    }

    private static void applyBlock(PlayerState s, int wx, int wy, int wz, int stateId) {
        final long key = chunkIndex(globalToChunk(wx), globalToChunk(wz));
        final PlayerWorld.Chunk chunk = s.world.chunks.get(key);
        if (chunk == null) return;

        chunk.setBlockState(wx, wy, wz, stateId);

        final int i = chunk.columnIndex(wx, wz);
        final short current = chunk.heights[i];
        ensureColumnColors(chunk);
        if (stateId != 0) {
            if (current == UNKNOWN || wy >= current) {
                chunk.heights[i] = (short) wy;
                final Block block = Block.fromStateId(stateId);
                chunk.columnColors[i] = block == null ? BlockColors.UNKNOWN : BlockColors.colorOf(block);
                s.world.dirtyChunks.add(key);
            }
        } else if (current != UNKNOWN && wy == current) {
            rescanColumn(s, chunk, wx, wy - 1, wz);
            s.world.dirtyChunks.add(key);
        }
    }

    /// Walk palettes downward to find the highest non-air block in this column.
    private static void rescanColumn(PlayerState s, PlayerWorld.Chunk chunk, int wx, int maxY, int wz) {
        final int i = chunk.columnIndex(wx, wz);
        ensureColumnColors(chunk);
        if (maxY < s.world.dimensionMinY) {
            chunk.heights[i] = UNKNOWN;
            chunk.columnColors[i] = UNKNOWN_COLOR;
            return;
        }
        if (chunk.sections == null) {
            chunk.heights[i] = (short) maxY;
            chunk.columnColors[i] = UNKNOWN_COLOR;
            return;
        }
        final int lx = globalToSectionRelative(wx);
        final int lz = globalToSectionRelative(wz);
        final int startRel = Math.min(chunk.sections.length - 1, globalToChunk(maxY) - chunk.minSection);
        for (int rel = startRel; rel >= 0; rel--) {
            final var palette = chunk.sections[rel];
            if (palette == null) continue;
            final int sectionY = chunk.minSection + rel;
            final int startLocalY = rel == startRel ? globalToSectionRelative(maxY) : SECTION_SIZE - 1;
            for (int localY = startLocalY; localY >= 0; localY--) {
                final int stateId = palette.get(lx, localY, lz);
                if (stateId == 0) continue;
                final Block block = Block.fromStateId(stateId);
                if (block == null || block.isAir()) continue;
                final int worldY = sectionY * SECTION_SIZE + localY;
                chunk.heights[i] = (short) worldY;
                chunk.columnColors[i] = BlockColors.colorOf(block);
                return;
            }
        }
        chunk.heights[i] = UNKNOWN;
        chunk.columnColors[i] = UNKNOWN_COLOR;
    }

    private static void record(PlayerState s, int sequence, Point pos, PredictedBlockChange.Kind kind) {
        final var pending = s.world.pendingChanges;
        pending.put(sequence, new PredictedBlockChange(
                pos.blockX(), pos.blockY(), pos.blockZ(), kind));
        while (pending.size() > MAX_PENDING) {
            final Iterator<Map.Entry<Integer, PredictedBlockChange>> it = pending.entrySet().iterator();
            if (!it.hasNext()) break;
            it.next();
            it.remove();
        }
    }
}
