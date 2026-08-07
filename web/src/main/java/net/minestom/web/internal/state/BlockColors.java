package net.minestom.web.internal.state;

import net.minestom.server.instance.block.Block;
import net.minestom.server.map.MapColors;

/// Block → top-down minimap colour. Resolved from the block's registry `mapColorId`, which
/// indexes into [MapColors] — the same table vanilla maps use, so every block already carries
/// the right colour without per-block branching.
public final class BlockColors {

    public static final int UNKNOWN = rgb(120, 120, 120);
    public static final int VOID    = rgb(16, 20, 24);

    private static final int[] RGB_BY_ID;

    static {
        final MapColors[] values = MapColors.values();
        RGB_BY_ID = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            final MapColors c = values[i];
            RGB_BY_ID[i] = rgb(c.red(), c.green(), c.blue());
        }
    }

    private BlockColors() {}

    public static int colorOf(Block block) {
        if (block == null) return VOID;
        final int id = block.registry().mapColorId();
        if (id <= 0 || id >= RGB_BY_ID.length) return VOID;
        return RGB_BY_ID[id];
    }

    private static int rgb(int r, int g, int b) {
        return ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
