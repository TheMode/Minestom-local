package net.minestom.web.internal.renderer;

import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

final class TextureResources {
    static final String ROOT = "/web/assets/textures";

    private TextureResources() {}

    @Nullable
    static BufferedImage load(String path) {
        try (InputStream in = TextureResources.class.getResourceAsStream(ROOT + "/" + path + ".png")) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException e) {
            return null;
        }
    }

    static byte @Nullable [] readBytes(String path) {
        try (InputStream in = TextureResources.class.getResourceAsStream(path)) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }
}
