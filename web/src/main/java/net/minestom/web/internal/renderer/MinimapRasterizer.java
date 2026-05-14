package net.minestom.web.internal.renderer;

import net.minestom.web.internal.state.BlockColors;

import static net.minestom.web.PlayerWorld.COLUMNS_PER_CHUNK;
import static net.minestom.web.PlayerWorld.UNKNOWN;
import static net.minestom.web.PlayerWorld.UNKNOWN_COLOR;

/// Rasterizes a chunk column bundle into a 16×16 RGBA tile (one pixel per block column).
public final class MinimapRasterizer {
    static final int TILE = 16;
    static final int BYTES = TILE * TILE * 4;

    private MinimapRasterizer() {
    }

    public static byte[] rasterize(short[] heights, int[] colors) {
        final byte[] out = new byte[BYTES];
        if (heights == null) return out;
        for (int z = 0; z < TILE; z++) {
            for (int x = 0; x < TILE; x++) {
                final int idx = (z << 4) | x;
                final int o = idx * 4;
                final short h = heights[idx];
                if (h == UNKNOWN) {
                    out[o] = 16;
                    out[o + 1] = 20;
                    out[o + 2] = 24;
                    out[o + 3] = (byte) 255;
                    continue;
                }
                final int packed = colors == null || colors[idx] == UNKNOWN_COLOR ? BlockColors.UNKNOWN : colors[idx];
                final int r = (packed >> 16) & 0xFF;
                final int g = (packed >> 8) & 0xFF;
                final int b = packed & 0xFF;
                out[o] = (byte) r;
                out[o + 1] = (byte) g;
                out[o + 2] = (byte) b;
                out[o + 3] = (byte) 255;
            }
        }
        return out;
    }
}
