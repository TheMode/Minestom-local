package net.minestom.web;

import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static net.minestom.server.coordinate.CoordConversion.*;

/// Per-player world mirror built from observed chunk / block packets.
///
/// Holds section block palettes, block entities, and a minimap height column per chunk.
/// Block lookups use the same palette layout as vanilla `ChunkData` — returns {@code null}
/// when the chunk or section is not loaded.
///
/// Single-thread contract: every field is touched only on the owning session worker.
public final class PlayerWorld {
    public static final int COLUMNS_PER_CHUNK = 256;
    public static final short UNKNOWN = Short.MIN_VALUE;
    public static final int UNKNOWN_COLOR = -1;
    public static final int MAX_PENDING = 256;

    public final Map<Long, Chunk> chunks = new HashMap<>();

    public final Set<Long> dirtyChunks = new HashSet<>();
    public final Set<Long> unloadedChunks = new HashSet<>();

    public final Map<Integer, PredictedBlockChange> pendingChanges = new HashMap<>();

    public int dimensionMinY = -64;
    public int dimensionHeight = 384;

    public record PredictedBlockChange(int x, int y, int z, Kind kind) {
        public enum Kind {PLACE, BREAK}
    }

    /// One loaded chunk column bundle.
    public static final class Chunk {
        public final int chunkX;
        public final int chunkZ;
        public final int minSection;
        /// Block palettes per section, indexed by `sectionY - minSection`. {@code null} when chunk
        /// data was not parsed (heightmap-only seed).
        public final Palette[] sections;
        public final Map<Integer, Block> blockEntities;
        public short[] heights;
        public int[] columnColors;

        public Chunk(int chunkX, int chunkZ, int minSection,
                     Palette[] sections, Map<Integer, Block> blockEntities,
                     short[] heights, int[] columnColors) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.minSection = minSection;
            this.sections = sections;
            this.blockEntities = blockEntities == null || blockEntities.isEmpty()
                    ? new HashMap<>() : new HashMap<>(blockEntities);
            this.heights = heights;
            this.columnColors = columnColors;
        }

        /// Heightmap-only chunk (tests or wire payload without section data).
        public static Chunk heightsOnly(int chunkX, int chunkZ, int minSection, short[] heights) {
            return new Chunk(chunkX, chunkZ, minSection, null, Map.of(), heights, null);
        }

        public int columnIndex(int wx, int wz) {
            return (globalToSectionRelative(wz) << 4) | globalToSectionRelative(wx);
        }

        /// @return state id, or {@code -1} if the section is not present in this mirror.
        public int getBlockStateId(int wx, int wy, int wz) {
            if (sections == null) return -1;
            final int sectionY = globalToChunk(wy);
            final int rel = sectionY - minSection;
            if (rel < 0 || rel >= sections.length) return -1;
            final Palette palette = sections[rel];
            if (palette == null) return -1;
            return palette.get(
                    globalToSectionRelative(wx),
                    globalToSectionRelative(wy),
                    globalToSectionRelative(wz));
        }

        public void setBlockState(int wx, int wy, int wz, int stateId) {
            if (sections == null) return;
            final int sectionY = globalToChunk(wy);
            final int rel = sectionY - minSection;
            if (rel < 0 || rel >= sections.length) return;
            final Palette palette = sections[rel];
            if (palette == null) return;
            palette.set(
                    globalToSectionRelative(wx),
                    globalToSectionRelative(wy),
                    globalToSectionRelative(wz),
                    stateId);
        }
    }

    public Chunk getChunk(int chunkX, int chunkZ) {
        return chunks.get(chunkIndex(chunkX, chunkZ));
    }

    public Chunk getChunkAtBlock(int wx, int wz) {
        return chunks.get(chunkIndex(globalToChunk(wx), globalToChunk(wz)));
    }

    public void putChunk(Chunk chunk) {
        chunks.put(chunkIndex(chunk.chunkX, chunk.chunkZ), chunk);
    }

    public int getBlockStateId(int wx, int wy, int wz) {
        final Chunk chunk = getChunkAtBlock(wx, wz);
        return chunk == null ? -1 : chunk.getBlockStateId(wx, wy, wz);
    }

    public void clear() {
        chunks.clear();
        dirtyChunks.clear();
        unloadedChunks.clear();
        pendingChanges.clear();
    }

    /// Dimension switch: drop every chunk but record its key as an unload so the live minimap
    /// frame tells the client to remove the now-stale tiles — vanilla sends no per-chunk
    /// `UnloadChunkPacket` across a dimension change, so without this old tiles linger as ghosts.
    /// A chunk reloaded at the same coords in the new dimension removes itself from
    /// `unloadedChunks` on load, so it is never both unloaded and loaded in the same frame.
    public void clearForDimensionChange() {
        final Set<Long> previous = new HashSet<>(chunks.keySet());
        clear();
        unloadedChunks.addAll(previous);
    }
}
