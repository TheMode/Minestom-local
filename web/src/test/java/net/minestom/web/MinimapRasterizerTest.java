package net.minestom.web;

import net.minestom.web.internal.renderer.MinimapRasterizer;
import org.junit.jupiter.api.Test;

import static net.minestom.web.PlayerWorld.COLUMNS_PER_CHUNK;
import static net.minestom.web.PlayerWorld.UNKNOWN;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MinimapRasterizerTest {

    @Test
    void rasterizesColumnColorsWithoutNeighborShading() {
        short[] heights = new short[COLUMNS_PER_CHUNK];
        int[] colors = new int[COLUMNS_PER_CHUNK];
        for (int i = 0; i < COLUMNS_PER_CHUNK; i++) {
            heights[i] = 64;
            colors[i] = 0x3ec27a;
        }
        heights[(0 << 4) | 1] = 68;

        byte[] tile = MinimapRasterizer.rasterize(heights, colors);
        assertEquals(16 * 16 * 4, tile.length);

        final int idx1 = ((0 << 4) | 1) * 4;
        final int idx0 = 0;
        assertEquals(tile[idx0], tile[idx1]);
        assertEquals(tile[idx0 + 1], tile[idx1 + 1]);
        assertEquals(tile[idx0 + 2], tile[idx1 + 2]);
    }

    @Test
    void unknownHeightUsesVoidColor() {
        short[] heights = new short[COLUMNS_PER_CHUNK];
        heights[0] = UNKNOWN;
        byte[] tile = MinimapRasterizer.rasterize(heights, null);
        assertEquals(16, tile[0] & 0xFF);
        assertEquals(20, tile[1] & 0xFF);
        assertEquals(24, tile[2] & 0xFF);
    }
}
