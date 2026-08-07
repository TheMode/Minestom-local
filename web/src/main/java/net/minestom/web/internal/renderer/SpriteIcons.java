package net.minestom.web.internal.renderer;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/// Scales entity / atlas sprites into square inventory icons.
final class SpriteIcons {
    private static final int OUT = 32;

    private SpriteIcons() {
    }

    static byte[] scale(BufferedImage src) throws IOException {
        BufferedImage crop = tightCrop(src, 0.02f);
        int sw = crop.getWidth(), sh = crop.getHeight();
        if (sw <= 0 || sh <= 0) return new byte[0];
        double scale = Math.min((OUT - 2.0) / sw, (OUT - 2.0) / sh);
        int dw = Math.max(1, (int) Math.round(sw * scale));
        int dh = Math.max(1, (int) Math.round(sh * scale));
        BufferedImage out = new BufferedImage(OUT, OUT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        int ox = (OUT - dw) / 2;
        int oy = (OUT - dh) / 2;
        g.drawImage(crop, ox, oy, ox + dw, oy + dh, 0, 0, sw, sh, null);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        ImageIO.write(out, "png", baos);
        return baos.toByteArray();
    }

    static BufferedImage tightCrop(BufferedImage src, float alphaThreshold) {
        int w = src.getWidth(), h = src.getHeight();
        int minX = w, minY = h, maxX = 0, maxY = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int a = (src.getRGB(x, y) >>> 24) & 0xFF;
                if (a <= (int) (alphaThreshold * 255)) continue;
                minX = Math.min(minX, x);
                minY = Math.min(minY, y);
                maxX = Math.max(maxX, x);
                maxY = Math.max(maxY, y);
            }
        }
        if (maxX < minX || maxY < minY) return src;
        return src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
    }
}
