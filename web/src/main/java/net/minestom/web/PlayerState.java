package net.minestom.web;

import net.kyori.adventure.nbt.CompoundBinaryTag;
import net.kyori.adventure.text.Component;
import net.minestom.server.item.ItemStack;
import net.minestom.server.network.ConnectionState;

import java.util.*;

/// Per-connection observed state.
///
/// **Owner-thread contract.** All access — read and write — must run on the owning session
/// worker virtual thread. Fields are plain Java primitives / collections on purpose: no
/// `volatile`, no atomics, no `Concurrent*`, and no object-monitor synchronization.
///
/// **Patch model.** Every traceable mutation routes through [#set] (atomic value) or [#append]
/// (ring buffer). Each call records two things: the per-field provenance (for the dashboard's
/// dotted-underline affordance) and a pending entry on the patch accumulator. The observer
/// calls [#drainPatch] on a fixed cadence to ship the coalesced delta over WebSocket — clients
/// only ever receive what actually changed since the last drain.
///
/// **Path scheme.** All path strings mirror the JSON layout exposed by
/// [net.minestom.web.internal.codec.WebJsonBuilders#playerStateJson] (top-level scalars,
/// nested objects/maps joined with `.`). The same path is used for both patch keys and
/// provenance — the frontend resolves it with one generic walker.
public final class PlayerState {

    // identity / session
    public UUID connectionId;
    public UUID uuid;
    public String username;
    /// Remote socket address, formatted as `host/ip:port`. String so both live (from a
    /// `SocketAddress.toString()`) and replay (from the SQLite `connections.address` column)
    /// can populate it without bringing transport types into the engine.
    public String address;
    public int protocolVersion;
    public String clientBrand;
    public String serverBrand;
    public String locale;
    /// `host:port` of the upstream this connection is currently bridged to. Set by the proxy
    /// on connection open; carried across journey-stitched reconnects via the transfer cookie.
    public String backendAddress;
    /// Journey id — stable for the duration of a player's run through the proxy, even across
    /// backend hops. `null` only on the brief window between TCP accept and the first packet
    /// that reveals a player UUID.
    public UUID journeyId;
    public ConnectionState clientConnectionState = ConnectionState.HANDSHAKE;
    public ConnectionState serverConnectionState = ConnectionState.HANDSHAKE;
    public final long connectedAt = System.currentTimeMillis();
    /// 0 while the session is live; ms timestamp once the socket has closed. The session stays
    /// in the registry for a while after disconnect so the dashboard can show profile + history
    /// for players who already left.
    public long disconnectedAt;
    public final Traffic traffic = new Traffic();

    // world / position
    public String dimension;
    public String gamemode;
    public boolean hardcore;
    public double posX, posY, posZ;
    public float yaw, pitch;
    public boolean onGround;

    // vitals
    public float health = 20f;
    public float maxHealth = 20f;
    public int food = 20;
    public float saturation;
    public int xpLevel;
    public float xpBar;

    // abilities
    public boolean invulnerable;
    public boolean flying;
    public boolean allowFlying;
    public boolean instantBreak;
    public float flySpeed = 0.05f;
    public float walkSpeed = 0.1f;

    // attributes / effects
    public final Map<String, Double> attributes = new LinkedHashMap<>();
    public final Map<String, ActiveEffect> activeEffects = new LinkedHashMap<>();

    // inventory. `null` means "empty slot" — air stacks are normalised to null on the way in
    // so the JSON output can omit them (the frontend treats falsy entries as empty).
    public final ItemStack[] hotbar = new ItemStack[9];
    public final ItemStack[] mainInventory = new ItemStack[27];
    public final ItemStack[] armor = new ItemStack[4];
    public ItemStack offHand;
    public ItemStack cursor;
    public int selectedHotbar;
    public OpenedWindow openedWindow;

    /// Ring buffer of slot clicks captured from `ClientClickWindowPacket`. Drives the
    /// inventory tab's transient highlight animation — the frontend pulses the addressed slot
    /// whenever a new entry lands here. Buffer is bounded; oldest entries fall off the front.
    public final List<ClickEvent> recentClicks = new ArrayList<>();

    public final List<ChatLine> chatReceived = new ArrayList<>();
    public final List<SentChatLine> chatSent = new ArrayList<>();
    public Component lastActionBar;

    // HUD
    public final Map<UUID, BossBarSnapshot> bossBars = new LinkedHashMap<>();
    public ScoreboardSnapshot scoreboard;
    public TabListSnapshot tabList = new TabListSnapshot(null, null);
    /// Live `TeamsPacket` registry, read only to compose sidebar row displays.
    public final Map<String, TeamSnapshot> teams = new LinkedHashMap<>();
    /// Reverse lookup `entityName → teamName` derived from [#teams] members.
    public final Map<String, String> teamByMember = new HashMap<>();

    // combat
    public DamageEvent lastDamage;

    // out-of-band
    public CompoundBinaryTag serverData = CompoundBinaryTag.empty();
    public long serverDataUpdatedAt;

    // world mirror — chunk palettes, block entities, minimap columns; drained per-push by the dashboard.
    public final PlayerWorld world = new PlayerWorld();

    /// Entities currently in this player's view, keyed on the wire `entityId`. Maintained by
    /// the spawn / position / destroy packet handlers.
    public final Map<Integer, VisibleEntity> visibleEntities = new LinkedHashMap<>();

    // user extensions
    public final Map<String, Object> custom = new HashMap<>();

    // --- provenance ---------------------------------------------------------------------------

    /// The provenance of the packet currently being applied. Set by
    /// [net.minestom.web.internal.state.StateApplier] before each dispatch; updaters read it via
    /// [#set] and need not touch it directly. Transient working state — never part of the JSON
    /// snapshot the dashboard emits.
    public Provenance currentProvenance;

    /// Per-field provenance: `"health"` → packet that last set health. Cleared only on session
    /// start. Serialised as a flat `{ field: {...} }` map under the profile snapshot.
    public final Map<String, Provenance> provenance = new LinkedHashMap<>();

    /// Per-field bounded history. Max [#PROVENANCE_HISTORY_DEPTH] entries; newest last. Each
    /// entry pins the source packet and the before/after values so the popover can show the
    /// `from → to` diff without re-querying the packet ring.
    public final Map<String, Deque<Provenance.Entry>> provenanceHistory = new LinkedHashMap<>();

    public static final int PROVENANCE_HISTORY_DEPTH = 20;

    // --- patch accumulator --------------------------------------------------------------------
    // Drain swaps each map for a fresh empty one and hands off the old reference to the
    // outgoing [StatePatch]: no per-drain copies, no per-entry allocs.

    private Map<String, Object> pendingValues = new LinkedHashMap<>();
    private Map<String, AppendAccumulator> pendingAppends = new LinkedHashMap<>();
    private Map<String, Provenance> pendingProvenance = new LinkedHashMap<>();
    private Set<String> pendingComputed = new LinkedHashSet<>();
    /// Previous totals shadowed by [#flushTrafficCounters] for delta detection.
    private long lastFlushedBytesIn;
    private long lastFlushedBytesOut;
    /// Monotonic patch sequence, written under the lock. Bumped on every non-empty drain so
    /// the frontend can detect gaps (e.g. after a WS reconnect).
    public long patchSeq;

    /// Record + assign in one call; returns `next` so the assignment and the provenance record
    /// can't drift (`s.health = s.set("health", s.health, p.health())`).
    ///
    /// The primitive overloads defer boxing of `prev`/`next` until the value actually changed,
    /// so high-frequency redundant writes (unmoved position packets) never allocate.
    public <T> T set(String field, T prev, T next) {
        if (currentProvenance != null && !Objects.equals(prev, next)) record(field, prev, next);
        return next;
    }

    public double set(String field, double prev, double next) {
        if (currentProvenance != null && Double.compare(prev, next) != 0) record(field, prev, next);
        return next;
    }

    public float set(String field, float prev, float next) {
        if (currentProvenance != null && Float.compare(prev, next) != 0) record(field, prev, next);
        return next;
    }

    public int set(String field, int prev, int next) {
        if (currentProvenance != null && prev != next) record(field, prev, next);
        return next;
    }

    public long set(String field, long prev, long next) {
        if (currentProvenance != null && prev != next) record(field, prev, next);
        return next;
    }

    public boolean set(String field, boolean prev, boolean next) {
        if (currentProvenance != null && prev != next) record(field, prev, next);
        return next;
    }

    /// Apply one *changed* field: stash the value, stamp the source, append a `(prev, next)`
    /// history entry. Only reached on a confirmed change, so the boxing the primitive overloads
    /// deferred happens here and never on the no-op path.
    private void record(String field, Object prev, Object next) {
        stampProvenance(field);
        pendingValues.put(field, next);
        Deque<Provenance.Entry> deque = provenanceHistory.computeIfAbsent(field, k -> new ArrayDeque<>(PROVENANCE_HISTORY_DEPTH + 1));
        deque.addLast(new Provenance.Entry(currentProvenance, prev, next));
        while (deque.size() > PROVENANCE_HISTORY_DEPTH) deque.removeFirst();
    }

    /// Append one element to a caller-owned ring buffer and mirror it onto the patch. `max`
    /// bounds the list and rides along in the patch so the frontend evicts in lock-step.
    /// Recorded even without `currentProvenance` (replay seeds chat before any packet
    /// provenance exists) — provenance is best-effort.
    public <T> void append(String path, List<T> list, T item, int max) {
        list.add(item);
        while (list.size() > max) list.removeFirst();
        AppendAccumulator acc = pendingAppends.get(path);
        if (acc == null) pendingAppends.put(path, acc = new AppendAccumulator());
        acc.add(item, max);
        stampProvenance(path);
    }

    /// Mark a path dirty without supplying the value; the drain looks up a computer and
    /// serializes current state. Used for collections whose shape isn't a single value
    /// (`visibleEntities`, `bossBars`, `attributes`, `hotbar`). Once `path` is already pending
    /// subsequent calls early-return before touching the provenance maps — entity-move packets
    /// hit this hundreds of times per drain window.
    public void markDirty(String path) {
        if (pendingComputed.add(path)) stampProvenance(path);
    }

    /// Refresh the long-lived per-field provenance and the patch's provenance entry for `path`.
    /// Only reached from the *changed* branch of [#set] / [#append] / [#markDirty], so the
    /// per-field source pointer tracks the last *meaningful* change, not redundant touches.
    private void stampProvenance(String path) {
        if (currentProvenance == null) return;
        provenance.put(path, currentProvenance);
        pendingProvenance.put(path, currentProvenance);
    }

    /// Push `traffic.bytesIn`/`bytesOut` onto the next patch if they've drifted. Provenance-less:
    /// these are TCP totals, not packet-derived, and `ConnectionWorker` bumps them per read/write
    /// — too noisy for `set`. Called from the cadence ticker before [#drainPatch].
    public void flushTrafficCounters() {
        if (traffic.bytesIn != lastFlushedBytesIn) {
            pendingValues.put("traffic.bytesIn", traffic.bytesIn);
            lastFlushedBytesIn = traffic.bytesIn;
        }
        if (traffic.bytesOut != lastFlushedBytesOut) {
            pendingValues.put("traffic.bytesOut", traffic.bytesOut);
            lastFlushedBytesOut = traffic.bytesOut;
        }
    }

    /// True iff something has been recorded since the last drain. Cheap fast-path for the
    /// observer so it can skip the lock entirely on quiet ticks.
    public boolean hasPending() {
        return !pendingValues.isEmpty() || !pendingAppends.isEmpty() || !pendingComputed.isEmpty();
    }

    /// Hand off the accumulator as an immutable [StatePatch] and rotate to fresh empty maps.
    /// Returns `null` when nothing has changed (the observer will skip publishing).
    ///
    /// `computers` resolves the value for [#markDirty]ed paths: `path → serialized value`. The
    /// observer owns this map because the JSON shape lives there, not in PlayerState.
    public StatePatch drainPatch(java.util.function.Function<String, Object> computers) {
        if (!hasPending()) return null;
        final long seq = ++patchSeq;
        final Map<String, Object> values = pendingValues;
        final Map<String, AppendAccumulator> appendAcc = pendingAppends;
        final Map<String, Provenance> prov = pendingProvenance;
        final Set<String> computed = pendingComputed;
        pendingValues = new LinkedHashMap<>();
        pendingAppends = new LinkedHashMap<>();
        pendingProvenance = new LinkedHashMap<>();
        pendingComputed = new LinkedHashSet<>();

        for (String path : computed) {
            final Object computedValue = computers.apply(path);
            if (computedValue != null) values.put(path, computedValue);
        }
        final Map<String, StatePatch.Append> appends;
        if (appendAcc.isEmpty()) {
            appends = Map.of();
        } else {
            appends = new LinkedHashMap<>(appendAcc.size());
            for (Map.Entry<String, AppendAccumulator> e : appendAcc.entrySet()) {
                appends.put(e.getKey(), e.getValue().toAppend());
            }
        }
        return new StatePatch(seq, System.currentTimeMillis(), values, appends, prov);
    }

    /// Mutable holder collapsing the per-path append batch + ring bound into one entry so the
    /// accumulator only carries one map. Handed off whole to [StatePatch.Append] on drain.
    private static final class AppendAccumulator {
        final List<Object> elements = new ArrayList<>();
        int max;
        void add(Object item, int max) {
            this.elements.add(item);
            this.max = max;
        }
        StatePatch.Append toAppend() {
            return new StatePatch.Append(elements, max);
        }
    }

    public record ActiveEffect(String id, int amplifier, int durationTicks, boolean ambient, boolean particles) {
    }

    public record OpenedWindow(int id, String type, Component title, ItemStack[] slots, Map<String, Integer> properties) {
    }

    /// One slot-level interaction captured from `ClientClickWindowPacket`. `kind`/`localSlot`
    /// resolves the wire slot index to its logical home (`hotbar`/`main`/`armor`/`offhand`/
    /// `container`/`craftingGrid`/`crafting`) so the inventory tab can target the matching slot
    /// in the rendered grid without re-implementing the protocol mapping. `windowId` is the
    /// window the click targeted — 0 for the player inventory, non-zero for an opened container.
    public record ClickEvent(long seq, long ts, int windowId, int rawSlot,
                             String kind, int localSlot, int button, String clickType) {
    }

    public record ChatLine(long ts, String sender, Component content, String style) {}
    public record SentChatLine(long ts, String kind, String text) {}

    public record BossBarSnapshot(Component title, float progress, String color, String division, int flags) {
    }

    public record ScoreboardSnapshot(String objectiveName, Component displayName, String slot,
                                     Map<String, ScoreboardRow> rows) {
    }

    /// `display` is pre-composed by [net.minestom.web.internal.state] so the frontend renders
    /// it directly — no per-row team lookup needed on the wire.
    public record ScoreboardRow(int score, Component display, NumberFormat numberFormat) {
    }

    public record NumberFormat(String format, Component content) {
    }

    public record TeamSnapshot(Component prefix, Component suffix, String teamColor) {
    }

    public record TabListSnapshot(Component header, Component footer) {
    }

    public record DamageEvent(long ts, double amount, String source, Integer attackerId) {
    }

    /// Transport-derived state for this player connection. Mutated on the owning state worker.
    public static final class Traffic {
        public int compressionThreshold = -1;
        public long bytesIn;
        public long bytesOut;
        public long packetsIn;
        public long packetsOut;
        public long pingMs;
        public final List<Long> pingHistory = new ArrayList<>();

        /// Transient bookkeeping for the proxied keep-alive RTT (not serialized). Written by the
        /// keep-alive updaters in [net.minestom.web.internal.state.VitalsUpdaters].
        public long lastKeepAliveOutAt;
        public long lastKeepAliveOutId;

        public Traffic() {
        }

        public Traffic(int compressionThreshold, long pingMs, long bytesIn, long bytesOut,
                       long packetsIn, long packetsOut, List<Long> pingHistory) {
            this.compressionThreshold = compressionThreshold;
            this.pingMs = pingMs;
            this.bytesIn = bytesIn;
            this.bytesOut = bytesOut;
            this.packetsIn = packetsIn;
            this.packetsOut = packetsOut;
            this.pingHistory.addAll(pingHistory);
        }
    }

    /// Short entity row for list snapshots and state patches.
    public record VisibleEntityShort(
            int id,
            UUID uuid,
            String type,
            String group,
            double x,
            double y,
            double z,
            float yaw
    ) {
        public static VisibleEntityShort from(VisibleEntity e) {
            return new VisibleEntityShort(e.id, e.uuid, e.type, e.group, e.x, e.y, e.z, e.yaw);
        }
    }

    /// Single tracked entity. Position is absolute world coords accumulated from spawn + delta
    /// packets; `group` is the minimap UI bucket, fixed at spawn.
    public static final class VisibleEntity {
        public int id;
        public UUID uuid;
        public String type;   // namespaced (e.g. "minecraft:zombie")
        public String group;  // minimap UI bucket
        public double x, y, z;
        public float yaw;
        public long lastUpdate;
        public long spawnSeq;    // seq of the SpawnEntityPacket that introduced this entity
        public long lastSeq;     // seq of the most recent packet that touched this entity
        public int packetCount;  // total packets that touched this entity

        /// Per-field provenance: `"pos"`, `"yaw"`, …
        public final Map<String, Provenance> provenance = new LinkedHashMap<>();

        /// Bounded change log — newest last. Powers the entity drilldown view.
        public final Deque<EntityChange> changeLog = new ArrayDeque<>();

        /// Record + return the new value in one call, mirroring [PlayerState#set]. Caller writes
        /// `e.x = e.set(prov, "pos.x", e.x, p.x())` so the assignment can't drift from the trace.
        public <T> T set(Provenance prov, String field, T prev, T next) {
            if (prov != null) recordEntity(field, prov, prev, next, Objects.equals(prev, next));
            return next;
        }

        public double set(Provenance prov, String field, double prev, double next) {
            if (prov != null) {
                boolean same = Double.compare(prev, next) == 0;
                recordEntity(field, prov, same ? null : prev, same ? null : next, same);
            }
            return next;
        }

        public float set(Provenance prov, String field, float prev, float next) {
            if (prov != null) {
                boolean same = Float.compare(prev, next) == 0;
                recordEntity(field, prov, same ? null : prev, same ? null : next, same);
            }
            return next;
        }

        private void recordEntity(String field, Provenance prov, Object prev, Object next, boolean unchanged) {
            provenance.put(field, prov);
            lastSeq = prov.seq();
            lastUpdate = prov.ts();
            packetCount++;
            if (!unchanged) {
                changeLog.addLast(new EntityChange(prov, field, prev, next));
                while (changeLog.size() > 64) changeLog.removeFirst();
            }
        }
    }

    /// One mutation on a tracked entity — used by the entity drilldown's change log.
    public record EntityChange(Provenance source, String field, Object prev, Object value) {
    }
}
