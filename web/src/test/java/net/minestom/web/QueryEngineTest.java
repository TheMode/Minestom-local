package net.minestom.web;

import net.minestom.server.instance.block.Block;
import net.minestom.server.instance.palette.Palette;
import net.minestom.web.internal.expression.ExprValue;
import net.minestom.web.internal.expression.ExpressionEngine;
import net.minestom.web.internal.expression.QueryEngine;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static net.minestom.server.coordinate.CoordConversion.globalToChunk;
import static net.minestom.server.coordinate.CoordConversion.globalToSectionRelative;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryEngineTest {

    @Test
    void simpleComparison() {
        QueryEngine eng = new QueryEngine(new ExpressionEngine(null));
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();
        p.username = "alice";
        p.gamemode = "survival";
        p.health = 5;
        p.dimension = "minecraft:overworld";

        assertTrue(eng.compile("health < 6").matches(p));
        assertTrue(eng.compile("gamemode = \"survival\"").matches(p));
        assertTrue(eng.compile("name = \"alice\" and health < 6").matches(p));
        assertFalse(eng.compile("health > 10").matches(p));
        assertTrue(eng.compile("not (health > 10)").matches(p));
    }

    @Test
    void serverDataAlias() {
        QueryEngine eng = new QueryEngine(new ExpressionEngine(null));
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();
        p.username = "alice";
        // serverData is empty by default; this should not throw.
        assertFalse(eng.compile("server.rank = \"vip\"").matches(p));
    }

    @Test
    void caseInsensitiveTextSearch() {
        QueryEngine eng = new QueryEngine(new ExpressionEngine(null));
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();
        p.username = "SteveMcSteveFace";

        assertTrue(eng.compile("name ~ \"steve\"").matches(p));
        assertTrue(eng.compile("name ~ \"STEVE\"").matches(p));
        assertTrue(eng.compile("name ~ \"face\"").matches(p));
        assertFalse(eng.compile("name ~ \"alice\"").matches(p));
        // Composes with the rest of the grammar.
        assertTrue(eng.compile("name ~ \"steve\" and not (name ~ \"alice\")").matches(p));
    }

    @Test
    void trafficFieldsAreNested() {
        QueryEngine eng = new QueryEngine(new ExpressionEngine(null));
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();
        p.traffic.bytesIn = 128;
        p.traffic.pingMs = 45;

        assertTrue(eng.compile("traffic.bytesIn > 100").matches(p));
        assertTrue(eng.compile("ping = 45").matches(p));
    }

    @Test
    void blockIdAtCoordinate() {
        ExpressionEngine ex = new ExpressionEngine(null);
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();

        assertInstanceOf(ExprValue.Null.class, ex.compile("blockId(0, 64, 0)").eval(p));

        final int sectionY = globalToChunk(64);
        final Palette palette = Palette.blocks();
        palette.set(7, globalToSectionRelative(64), 4, Block.STONE.stateId());
        final short[] heights = new short[PlayerWorld.COLUMNS_PER_CHUNK];
        p.world.putChunk(new PlayerWorld.Chunk(0, 0, sectionY,
                new Palette[]{palette}, java.util.Map.of(), heights, null));

        assertEquals(new ExprValue.Num(Block.STONE.stateId()), ex.compile("blockId(7, 64, 4)").eval(p));
        assertTrue(new QueryEngine(ex).compile("blockId(7, 64, 4) = " + Block.STONE.stateId()).matches(p));
    }

    @Test
    void unaryAsFunctionOrPipe() {
        ExpressionEngine ex = new ExpressionEngine(null);
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();
        p.username = "alice";

        assertEquals(new ExprValue.Str("ALICE"), ex.compile("upper(name)").eval(p));
        assertEquals(new ExprValue.Str("ALICE"), ex.compile("name | upper").eval(p));
        assertEquals(new ExprValue.Str("alice"), ex.compile("lower(name)").eval(p));
        assertEquals(new ExprValue.Str("alice"), ex.compile("name | lower").eval(p));
    }

    @Test
    void pipeBlockKeyTransform() {
        ExpressionEngine ex = new ExpressionEngine(null);
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();

        final int sectionY = globalToChunk(64);
        final Palette palette = Palette.blocks();
        palette.set(7, globalToSectionRelative(64), 4, Block.STONE.stateId());
        final short[] heights = new short[PlayerWorld.COLUMNS_PER_CHUNK];
        p.world.putChunk(new PlayerWorld.Chunk(0, 0, sectionY,
                new Palette[]{palette}, java.util.Map.of(), heights, null));

        assertEquals(new ExprValue.Str(Block.STONE.key().asString()),
                ex.compile("blockId(7, 64, 4) | blockKey").eval(p));
        assertTrue(new QueryEngine(ex).compile(
                "blockId(7, 64, 4) | blockKey = \"" + Block.STONE.key().asString() + "\"").matches(p));
    }

    @Test
    void hasAndContains() {
        QueryEngine eng = new QueryEngine(new ExpressionEngine(null));
        PlayerState p = new PlayerState();
        p.uuid = UUID.randomUUID();
        p.activeEffects.put("minecraft:poison", new PlayerState.ActiveEffect("minecraft:poison", 0, 200, false, true));

        // accessor for activeEffects isn't registered by default; use reflection via dotted path.
        // The accessor falls back to the PlayerState public field 'activeEffects' for the root.
        assertTrue(eng.compile("activeEffects has \"minecraft:poison\"").matches(p));
    }
}
