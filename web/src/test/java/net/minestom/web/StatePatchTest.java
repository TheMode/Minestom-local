package net.minestom.web;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.client.common.ClientKeepAlivePacket;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.web.internal.state.StateApplier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Behavioural tests for the patch accumulator on [PlayerState]. Covers the contract every
/// updater relies on: set() coalesces under one path, no-ops skip the wire, ring appends batch,
/// markDirty resolves through the computer map at drain time, and the accumulator clears
/// cleanly between drains.
class StatePatchTest {

    private final PlayerState s = new PlayerState();

    private void provenance(long seq) {
        s.currentProvenance = new Provenance(seq, System.currentTimeMillis(), "TestPacket", Direction.SERVERBOUND);
    }

    @Test
    void setRecordsChangedValueAndProvenance() {
        provenance(1);
        s.health = s.set("health", s.health, 18.0f);

        StatePatch patch = s.drainPatch(p -> null);
        assertNotNull(patch);
        assertEquals(18.0f, patch.values().get("health"));
        assertEquals(1, patch.provenance().get("health").seq());
        assertEquals(1L, patch.seq());
    }

    @Test
    void setNoOpDoesNothing() {
        // First write changes the default — emits a patch, baseline laid down.
        provenance(1);
        s.health = s.set("health", s.health, 18f);
        assertNotNull(s.drainPatch(p -> null));

        // Redundant write at the same value: drain returns null, provenance still points at
        // the last *meaningful* change. Skipping the map.put on no-ops is a hot-path win —
        // position packets that haven't moved fire 20Hz × 3 axes per player.
        provenance(2);
        s.health = s.set("health", s.health, 18f);

        assertNull(s.drainPatch(p -> null), "no-op writes must not trigger a publish");
        assertEquals(1, s.provenance.get("health").seq(),
                "no-op writes leave the long-lived source pointer on the last meaningful change");
    }

    @Test
    void setCoalescesMultipleWritesUnderOnePath() {
        provenance(1);
        s.health = s.set("health", s.health, 19f);
        s.health = s.set("health", s.health, 17f);
        s.health = s.set("health", s.health, 15f);

        StatePatch patch = s.drainPatch(p -> null);
        assertEquals(15f, patch.values().get("health"), "latest write wins");
        assertEquals(1, patch.values().size(), "single coalesced entry");
    }

    @Test
    void primitivesAreBoxedConsistently() {
        provenance(1);
        s.xpLevel = s.set("xpLevel", s.xpLevel, 7);
        s.xpBar = s.set("xpBar", s.xpBar, 0.5f);
        s.posX = s.set("posX", s.posX, 12.5);
        s.flying = s.set("flying", s.flying, true);
        s.traffic.pingMs = s.set("traffic.pingMs", s.traffic.pingMs, 42L);

        StatePatch patch = s.drainPatch(p -> null);
        assertEquals(7, patch.values().get("xpLevel"));
        assertEquals(0.5f, patch.values().get("xpBar"));
        assertEquals(12.5, patch.values().get("posX"));
        assertEquals(true, patch.values().get("flying"));
        assertEquals(42L, patch.values().get("traffic.pingMs"));
    }

    @Test
    void appendBatchesElementsAndCarriesMax() {
        provenance(1);
        final List<String> log = new ArrayList<>();
        s.append("recentChat", log, "hello", 200);
        s.append("recentChat", log, "world", 200);

        StatePatch patch = s.drainPatch(p -> null);
        StatePatch.Append op = patch.appends().get("recentChat");
        assertNotNull(op);
        assertEquals(List.of("hello", "world"), op.elements());
        assertEquals(200, op.max());
        assertEquals(2, log.size(), "underlying list mutated in lockstep");
    }

    @Test
    void appendEnforcesRingBound() {
        provenance(1);
        final List<Integer> ring = new ArrayList<>();
        for (int i = 0; i < 5; i++) s.append("traffic.pingHistory", ring, i, 3);

        assertEquals(3, ring.size(), "list bounded to max");
        assertEquals(List.of(2, 3, 4), ring);

        StatePatch patch = s.drainPatch(p -> null);
        assertEquals(5, patch.appends().get("traffic.pingHistory").elements().size(),
                "patch carries every append in the window (even the ones that fell off the front)");
        assertEquals(3, patch.appends().get("traffic.pingHistory").max());
    }

    @Test
    void markDirtyResolvesThroughComputer() {
        provenance(1);
        s.markDirty("visibleEntities");

        StatePatch patch = s.drainPatch(path -> {
            if ("visibleEntities".equals(path)) return List.of("e1", "e2");
            return null;
        });
        assertEquals(List.of("e1", "e2"), patch.values().get("visibleEntities"));
        assertEquals(1, patch.provenance().get("visibleEntities").seq());
    }

    @Test
    void markDirtyWithNoComputerOmitsEntry() {
        provenance(1);
        s.markDirty("unknown");

        StatePatch patch = s.drainPatch(p -> null);
        assertNotNull(patch);
        assertFalse(patch.values().containsKey("unknown"));
        // Provenance is recorded so the dashboard's source-pointer still tracks even when the
        // observer hasn't wired a serializer for the path yet.
        assertEquals(1, patch.provenance().get("unknown").seq());
    }

    @Test
    void drainClearsAccumulatorBetweenWindows() {
        provenance(1);
        s.health = s.set("health", s.health, 10f);
        s.append("recentChat", new ArrayList<String>(), "first", 200);
        s.drainPatch(p -> null);

        StatePatch empty = s.drainPatch(p -> null);
        assertNull(empty, "drain on empty accumulator returns null");

        provenance(2);
        s.health = s.set("health", s.health, 12f);
        StatePatch next = s.drainPatch(p -> null);
        assertEquals(12f, next.values().get("health"));
        assertFalse(next.appends().containsKey("recentChat"), "previous-window appends do not leak");
        assertEquals(2L, next.seq(), "patch seq is monotonic across drains");
    }

    @Test
    void hasPendingMirrorsAccumulatorState() {
        assertFalse(s.hasPending());

        provenance(1);
        s.health = s.set("health", s.health, 19f);
        assertTrue(s.hasPending());

        s.drainPatch(p -> null);
        assertFalse(s.hasPending());
    }

    @Test
    void setOutsidePacketDispatchIsSilent() {
        // No `currentProvenance` set — replay seeding, REST mutation, etc. should not record.
        s.health = s.set("health", s.health, 5f);
        assertFalse(s.hasPending(), "set without provenance must not leak onto the patch");
        assertEquals(5f, s.health, "but the field is still assigned (return value is `next`)");
    }

    @Test
    void emptyFactoryIsEmpty() {
        assertTrue(StatePatch.empty(0).isEmpty());
    }

    @Test
    void keepAliveRoundTripUpdatesPing() throws InterruptedException {
        StateApplier.applyPacket(s, Direction.CLIENTBOUND, ConnectionState.PLAY, new KeepAlivePacket(1L));
        Thread.sleep(50);
        StateApplier.applyPacket(s, Direction.SERVERBOUND, ConnectionState.PLAY, new ClientKeepAlivePacket(1L));
        assertTrue(s.traffic.pingMs >= 40, "pingMs=" + s.traffic.pingMs);
        assertEquals(1, s.traffic.pingHistory.size());
    }

    @Test
    void keepAliveMismatchedIdIgnored() {
        StateApplier.applyPacket(s, Direction.CLIENTBOUND, ConnectionState.PLAY, new KeepAlivePacket(1L));
        StateApplier.applyPacket(s, Direction.SERVERBOUND, ConnectionState.PLAY, new ClientKeepAlivePacket(2L));
        assertEquals(0L, s.traffic.pingMs);
    }
}
