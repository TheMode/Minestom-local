package net.minestom.web.internal.state;

import net.minestom.server.coordinate.Point;
import net.minestom.server.network.packet.Packet;
import net.minestom.server.network.packet.server.play.*;
import net.minestom.web.PlayerState;
import net.minestom.web.PlayerState.VisibleEntity;

import java.util.Map;

import static net.minestom.web.internal.state.StateApplier.entry;
import static net.minestom.web.internal.state.StateApplier.listeners;

/// Visible entities (spawn / move / rotate / destroy). Each mutation flags `visibleEntities`
/// dirty so the next [PlayerState#drainPatch] reships the bucket — the entities collection
/// has no stable per-field path, so it's a "computed" patch field (resolved in
/// [net.minestom.web.internal.session.Session]'s cadence drain). The mark is cheap on the
/// repeat path: [PlayerState#markDirty] early-returns once the bucket is already pending.
///
/// Per-entity provenance + change log are recorded inside [VisibleEntity#set] and surfaced
/// on demand by the entity drilldown REST endpoint — they don't ship in every patch.
final class EntityUpdaters {

    private static final String DIRTY = "visibleEntities";

    static final Map<Class<? extends Packet>, StateApplier.Updater<?>> LISTENERS = listeners(
            entry(SpawnEntityPacket.class, (s, _, _, p) -> spawn(s, p)),
            entry(EntityPositionPacket.class, (s, _, _, p) ->
                    moveDelta(s, p.entityId(), p.deltaX(), p.deltaY(), p.deltaZ(), Float.NaN)),
            entry(EntityPositionAndRotationPacket.class, (s, _, _, p) ->
                    moveDelta(s, p.entityId(), p.deltaX(), p.deltaY(), p.deltaZ(), p.yaw())),
            entry(EntityRotationPacket.class, (s, _, _, p) -> {
                final VisibleEntity e = s.visibleEntities.get(p.entityId());
                if (e == null) return;
                e.yaw = e.set(s.currentProvenance, "yaw", e.yaw, p.yaw());
                s.markDirty(DIRTY);
            }),
            entry(EntityPositionSyncPacket.class, (s, _, _, p) ->
                    moveAbs(s, p.entityId(), p.position(), p.yaw())),
            entry(EntityTeleportPacket.class, (s, _, _, p) ->
                    moveAbs(s, p.entityId(), p.position(), p.position().yaw())),
            entry(DestroyEntitiesPacket.class, (s, _, _, p) -> {
                if (p.entityIds().isEmpty()) return;
                for (Integer id : p.entityIds()) s.visibleEntities.remove(id);
                s.markDirty(DIRTY);
            }));

    private EntityUpdaters() {
    }

    private static void spawn(PlayerState s, SpawnEntityPacket p) {
        final VisibleEntity e = new VisibleEntity();
        e.id = p.entityId();
        e.uuid = p.uuid();
        e.type = e.set(s.currentProvenance, "type", null, p.type().key().asString());
        e.group = EntityGroups.classify(p.type());
        e.x = e.set(s.currentProvenance, "x", 0.0, p.position().x());
        e.y = e.set(s.currentProvenance, "y", 0.0, p.position().y());
        e.z = e.set(s.currentProvenance, "z", 0.0, p.position().z());
        e.yaw = e.set(s.currentProvenance, "yaw", 0f, p.position().yaw());
        e.spawnSeq = s.currentProvenance != null ? s.currentProvenance.seq() : 0;
        s.visibleEntities.put(e.id, e);
        s.markDirty(DIRTY);
    }

    /// NaN yaw means "rotation unchanged" — the position-only variant.
    private static void moveDelta(PlayerState s, int entityId, short dx, short dy, short dz, float yaw) {
        final VisibleEntity e = s.visibleEntities.get(entityId);
        if (e == null) return;
        e.x = e.set(s.currentProvenance, "x", e.x, e.x + dx / 4096.0);
        e.y = e.set(s.currentProvenance, "y", e.y, e.y + dy / 4096.0);
        e.z = e.set(s.currentProvenance, "z", e.z, e.z + dz / 4096.0);
        if (!Float.isNaN(yaw)) e.yaw = e.set(s.currentProvenance, "yaw", e.yaw, yaw);
        s.markDirty(DIRTY);
    }

    private static void moveAbs(PlayerState s, int entityId, Point pos, float yaw) {
        final VisibleEntity e = s.visibleEntities.get(entityId);
        if (e == null) return;
        e.x = e.set(s.currentProvenance, "x", e.x, pos.x());
        e.y = e.set(s.currentProvenance, "y", e.y, pos.y());
        e.z = e.set(s.currentProvenance, "z", e.z, pos.z());
        e.yaw = e.set(s.currentProvenance, "yaw", e.yaw, yaw);
        s.markDirty(DIRTY);
    }
}
