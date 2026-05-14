package net.minestom.web.internal.renderer;

import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/// Shared high-resolution nearest-neighbour canvas for item and block-entity icon renderers.
/// Quads rasterize via barycentric interpolation (p0→uv00, p1→uv10, p2→uv11, p3→uv01).
final class IconCanvas {
    static final int OUT = 32;
    static final int RENDER = 128;

    private final int[] pixels = new int[RENDER * RENDER];

    void quad(BufferedImage texture,
              double x0, double y0, double x1, double y1, double x2, double y2, double x3, double y3,
              int u0, int v0, int u1, int v1, float brightness) {
        int minX = Math.max(0, (int) Math.floor(Math.min(Math.min(x0, x1), Math.min(x2, x3))));
        int maxX = Math.min(RENDER - 1, (int) Math.ceil(Math.max(Math.max(x0, x1), Math.max(x2, x3))));
        int minY = Math.max(0, (int) Math.floor(Math.min(Math.min(y0, y1), Math.min(y2, y3))));
        int maxY = Math.min(RENDER - 1, (int) Math.ceil(Math.max(Math.max(y0, y1), Math.max(y2, y3))));

        int tw = texture.getWidth(), th = texture.getHeight();
        float uScale = (u1 - u0) / (float) tw;
        float vScale = (v1 - v0) / (float) th;
        float uOff = u0 / (float) tw;
        float vOff = v0 / (float) th;

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                double[] uv = barycentric(x + 0.5, y + 0.5, x0, y0, x1, y1, x2, y2, x3, y3);
                if (uv == null) continue;
                int tx = Math.clamp((int) ((uOff + uv[0] * uScale) * tw), 0, tw - 1);
                int ty = Math.clamp((int) ((vOff + uv[1] * vScale) * th), 0, th - 1);
                int argb = texture.getRGB(tx, ty);
                if (((argb >>> 24) & 0xFF) == 0) continue;
                int i = y * RENDER + x;
                pixels[i] = brightness >= 0.999f ? argb : shade(argb, brightness, pixels[i]);
            }
        }
    }

    static byte[] cube(BufferedImage top, BufferedImage left, BufferedImage right) throws IOException {
        IconCanvas c = new IconCanvas();
        c.quad(top, 8, 40, 64, 8, 120, 40, 64, 72,
                0, 0, top.getWidth(), top.getHeight(), 0.74f);
        c.quad(left, 8, 40, 64, 72, 64, 128, 8, 96,
                0, 0, left.getWidth(), left.getHeight(), 0.52f);
        c.quad(right, 64, 72, 120, 40, 120, 96, 64, 128,
                0, 0, right.getWidth(), right.getHeight(), 0.64f);
        return c.png();
    }

    byte[] png() throws IOException {
        BufferedImage hi = new BufferedImage(RENDER, RENDER, BufferedImage.TYPE_INT_ARGB);
        hi.setRGB(0, 0, RENDER, RENDER, pixels, 0, RENDER);

        BufferedImage out = new BufferedImage(OUT, OUT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < OUT; y++) {
            for (int x = 0; x < OUT; x++) {
                out.setRGB(x, y, hi.getRGB(x * RENDER / OUT, y * RENDER / OUT));
            }
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
        ImageIO.write(out, "png", baos);
        return baos.toByteArray();
    }

    private static int shade(int argb, float brightness, int under) {
        int a = (argb >>> 24) & 0xFF;
        if (a == 0) return under;
        int r = (int) (((argb >>> 16) & 0xFF) * brightness);
        int g = (int) (((argb >>> 8) & 0xFF) * brightness);
        int b = (int) ((argb & 0xFF) * brightness);
        int out = (a << 24) | (r << 16) | (g << 8) | b;
        if (under == 0) return out;
        int ua = (under >>> 24) & 0xFF;
        if (ua == 0) return out;
        int inv = 255 - a;
        int or = (under >>> 16) & 0xFF, og = (under >>> 8) & 0xFF, ob = under & 0xFF;
        return (Math.min(255, a + inv * ua / 255) << 24)
                | ((r * a + or * inv) / 255 << 16)
                | ((g * a + og * inv) / 255 << 8)
                | ((b * a + ob * inv) / 255);
    }

    private static double @Nullable [] barycentric(double px, double py,
                                                   double x0, double y0, double x1, double y1,
                                                   double x2, double y2, double x3, double y3) {
        double[] uv = tri(px, py, x0, y0, x1, y1, x3, y3);
        if (uv != null) return uv;
        double d = (y2 - y3) * (x1 - x3) + (x3 - x2) * (y1 - y3);
        if (Math.abs(d) < 1e-6) return null;
        double w0 = ((y2 - y3) * (px - x3) + (x3 - x2) * (py - y3)) / d;
        double w1 = ((y3 - y1) * (px - x3) + (x1 - x3) * (py - y3)) / d;
        double w2 = 1.0 - w0 - w1;
        if (w0 < -0.001 || w1 < -0.001 || w2 < -0.001) return null;
        return new double[]{w0 + w1, w1 + w2};
    }

    private static double @Nullable [] tri(double px, double py,
                                           double x0, double y0, double x1, double y1, double x2, double y2) {
        double d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2);
        if (Math.abs(d) < 1e-6) return null;
        double w0 = ((y1 - y2) * (px - x2) + (x2 - x1) * (py - y2)) / d;
        double w1 = ((y2 - y0) * (px - x2) + (x0 - x2) * (py - y2)) / d;
        double w2 = 1.0 - w0 - w1;
        if (w0 < -0.001 || w1 < -0.001 || w2 < -0.001) return null;
        return new double[]{w1, w2};
    }
}
