package net.minestom.web;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.EntityType;
import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.web.internal.state.EntityGroups;
import net.minestom.web.internal.state.StateApplier;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/// Behavioural tests for the per-player entity tracker in `EntityUpdaters`. Spawn → delta →
/// destroy is the hot loop; absolute teleports / position-syncs and pure rotation packets are
/// the special cases that need to land on the same field set without nuking what's already
/// there.
class EntityTrackingTest {

    private final PlayerState state = new PlayerState();

    @Test
    void spawnAddsEntityWithGroup() {
        spawnZombie(42, 10.0, 64.0, 20.0);
        final PlayerState.VisibleEntity e = state.visibleEntities.get(42);
        assertNotNull(e);
        assertEquals("minecraft:zombie", e.type);
        assertEquals("hostile", e.group);
        assertEquals(10.0, e.x);
        assertEquals(20.0, e.z);
    }

    @Test
    void destroyClearsEntries() {
        spawnZombie(1, 0, 0, 0);
        spawnZombie(2, 0, 0, 0);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new DestroyEntitiesPacket(List.of(1)));
        assertNull(state.visibleEntities.get(1));
        assertNotNull(state.visibleEntities.get(2));
    }

    @Test
    void positionDeltaIsFixedPoint4096() {
        spawnZombie(7, 100.0, 64.0, 50.0);
        // Move +1 block in X and -0.5 in Z. The wire-format is `(new - old) * 4096`.
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new EntityPositionPacket(7, (short) 4096, (short) 0, (short) -2048, true));
        final PlayerState.VisibleEntity e = state.visibleEntities.get(7);
        assertEquals(101.0, e.x);
        assertEquals(64.0, e.y);
        assertEquals(49.5, e.z);
    }

    @Test
    void positionAndRotationUpdatesYaw() {
        spawnZombie(7, 0, 0, 0);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new EntityPositionAndRotationPacket(7, (short) 0, (short) 0, (short) 0, 90f, 0f, true));
        // yaw round-trips through a byte (256/360), so allow ~1.5° slop.
        final PlayerState.VisibleEntity e = state.visibleEntities.get(7);
        assertEquals(90f, e.yaw, 1.5);
    }

    @Test
    void rotationPacketLeavesPositionAlone() {
        spawnZombie(7, 33.0, 64.0, 44.0);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new EntityRotationPacket(7, 45f, 0f, true));
        final PlayerState.VisibleEntity e = state.visibleEntities.get(7);
        assertEquals(33.0, e.x);
        assertEquals(44.0, e.z);
        assertEquals(45f, e.yaw, 1.5);
    }

    @Test
    void positionSyncIsAbsolute() {
        spawnZombie(7, 100.0, 64.0, 100.0);
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new EntityPositionSyncPacket(7, new Pos(0, 0, 0), Vec.ZERO, 0f, 0f, true));
        final PlayerState.VisibleEntity e = state.visibleEntities.get(7);
        assertEquals(0.0, e.x);
        assertEquals(0.0, e.z);
    }

    @Test
    void groupClassificationCoversCommonCases() {
        // Players, items, projectiles, vehicles all hit specific buckets — the rest fall
        // through to "passive" so a freshly-spawned cow doesn't end up in "other".
        assertEquals("players", EntityGroups.classify(EntityType.PLAYER));
        assertEquals("items", EntityGroups.classify(EntityType.ITEM));
        assertEquals("projectiles", EntityGroups.classify(EntityType.ARROW));
        assertEquals("vehicles", EntityGroups.classify(EntityType.MINECART));
        assertEquals("hostile", EntityGroups.classify(EntityType.CREEPER));
        assertEquals("passive", EntityGroups.classify(EntityType.COW));
        assertEquals("other", EntityGroups.classify(null));
    }

    private void spawnZombie(int id, double x, double y, double z) {
        StateApplier.applyPacket(state, Direction.CLIENTBOUND, ConnectionState.PLAY,
                new SpawnEntityPacket(id, UUID.randomUUID(), EntityType.ZOMBIE,
                        new Pos(x, y, z, 0f, 0f), 0f, 0, Vec.ZERO));
    }
}
