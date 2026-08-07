package net.minestom.web;

import net.minestom.web.internal.renderer.ItemIconRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemIconRendererTest {
    @Test
    void rendersSpecialBlocks() {
        ItemIconRenderer icons = new ItemIconRenderer();
        assertPng(icons.iconFor("purple_bed"), "purple_bed");
        assertPng(icons.iconFor("red_banner"), "red_banner");
        assertPng(icons.iconFor("grass_block"), "grass_block");
        assertPng(icons.iconFor("chest"), "chest");
        assertPng(icons.iconFor("stone"), "stone");
    }

    private static void assertPng(byte[] png, String id) {
        assertNotNull(png, id + " should render");
        assertTrue(png.length > 8, id + " png too small");
        assertTrue(png[0] == (byte) 0x89 && png[1] == 'P', id + " not a png");
    }
}
