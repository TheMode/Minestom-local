package net.minestom.web.internal.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minestom.web.Action;
import net.minestom.web.LifecycleEvent;
import net.minestom.web.PlayerState;
import net.minestom.web.Query;
import net.minestom.web.RegisteredAction;
import net.minestom.web.RegisteredRoutine;
import net.minestom.web.Routine;
import net.minestom.web.internal.codec.RoutineCodecs;
import net.minestom.web.internal.proxy.JourneyTracker;
import net.minestom.web.internal.expression.QueryEngine;
import net.minestom.web.internal.state.StateApplier;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/// Live sessions + retained snapshots + the routine/action catalogue. Routine CRUD broadcasts
/// a [SessionMessage.SetRoutines] to every session so per-session evaluators stay in sync.
public final class SessionRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionRegistry.class);

    private final int decodedPacketCacheSize;
    private final @Nullable QueryEngine queries;
    private volatile @Nullable JourneyTracker journeys;
    private final StateApplier applier = new StateApplier(this);
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Session> liveByPlayerUuid = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerView.Retained> retainedByPlayerUuid = new ConcurrentHashMap<>();

    private final Map<UUID, RegisteredRoutine> routines = new ConcurrentHashMap<>();
    private final Map<UUID, RegisteredAction> actions = new ConcurrentHashMap<>();
    private volatile List<RegisteredRoutine> routineSnapshot = List.of();
    /// Late-bound to break the proxy↔registry construction cycle.
    private volatile @Nullable ActionRunner actionRunner;

    private final List<Consumer<Session>> openListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<Session>> closeListeners = new CopyOnWriteArrayList<>();
    private final List<Consumer<PlayerView.Retained>> evictListeners = new CopyOnWriteArrayList<>();

    /// Convenience for tests / replay paths that never compile routine queries.
    public SessionRegistry(int decodedPacketCacheSize) {
        this(decodedPacketCacheSize, null);
    }

    public SessionRegistry(int decodedPacketCacheSize, @Nullable QueryEngine queries) {
        this.decodedPacketCacheSize = decodedPacketCacheSize;
        this.queries = queries;
    }

    public StateApplier applier() { return applier; }

    public void attachJourneyTracker(@Nullable JourneyTracker tracker) { this.journeys = tracker; }

    public void attachActionRunner(ActionRunner runner) { this.actionRunner = runner; }

    public @Nullable ActionRunner actionRunner() { return actionRunner; }

    public void onSessionOpen(Consumer<Session> listener)              { openListeners.add(listener); }
    public void onSessionClose(Consumer<Session> listener)             { closeListeners.add(listener); }
    public void onSessionEvict(Consumer<PlayerView.Retained> listener) { evictListeners.add(listener); }

    /// Index `session` by its player UUID so `/api/players/{uuid}` and `inject(uuid, …)` can
    /// find it without scanning. Called by [StateApplier] once the UUID is revealed.
    public void markLive(Session session) {
        final UUID uuid = session.playerUuid();
        if (uuid == null) return;
        liveByPlayerUuid.put(uuid, session);
        final JourneyTracker tracker = journeys;
        if (tracker != null && session.journeyId() != null && session.backendAddress() != null) {
            tracker.recordAssignment(uuid, session.backendAddress());
        }
    }

    public Collection<PlayerView> players() {
        final Map<UUID, PlayerView> players = new LinkedHashMap<>();
        for (PlayerView.Retained snapshot : retainedByPlayerUuid.values()) {
            players.put(snapshot.uuid(), snapshot);
        }
        for (Session session : livePlayerSessions()) {
            UUID uuid = session.playerUuid();
            if (uuid != null) players.put(uuid, new PlayerView.Live(session));
        }
        return new ArrayList<>(players.values());
    }

    public Collection<Session> livePlayerSessions() {
        final Map<UUID, Session> live = new LinkedHashMap<>();
        for (Session session : sessions.values()) {
            final UUID uuid = session.playerUuid();
            if (uuid == null || session.disconnectedAt() != 0) continue;
            final Session existing = live.get(uuid);
            if (existing == null || session.connectedAt >= existing.connectedAt) live.put(uuid, session);
        }
        return new ArrayList<>(live.values());
    }

    public Collection<Session> sessionsMatching(Query query) {
        Objects.requireNonNull(query, "query");
        final ArrayList<Session> matches = new ArrayList<>();
        for (Session session : livePlayerSessions()) {
            if (playerMatches(query, session)) matches.add(session);
        }
        return matches;
    }

    public boolean playerMatches(Query query, Session session) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(session, "session");
        // Bounded read: a single wedged owner thread must not pin the HTTP request thread while
        // sessionsMatching scans every session. A busy/slow owner just counts as non-matching.
        try {
            return session.tryReadState(query::matches, Session.HTTP_READ_TIMEOUT_MS);
        } catch (Exception e) {
            return false;
        }
    }

    public Collection<Session> sessions() {
        return sessions.values();
    }

    public Session sessionFor(UUID uuid) {
        Session indexed = liveByPlayerUuid.get(uuid);
        if (indexed != null) return indexed;
        Session latest = null;
        for (Session session : sessions.values()) {
            if (!uuid.equals(session.playerUuid()) || session.disconnectedAt() != 0) continue;
            if (latest == null || session.connectedAt >= latest.connectedAt) latest = session;
        }
        return latest;
    }

    public PlayerView player(UUID uuid) {
        final Session session = sessionFor(uuid);
        if (session != null) return new PlayerView.Live(session);
        return retainedByPlayerUuid.get(uuid);
    }

    public Session sessionById(UUID sessionId) {
        return sessions.get(sessionId);
    }

    public Session openSession(String address) {
        return openSession(UUID.randomUUID(), address);
    }

    public Session openSession(UUID id, String address) {
        final Session session = createSession(id, address);
        notifyOpened(session);
        return session;
    }

    /// Create a session and register it, but defer firing open listeners until
    /// [#notifyOpened] is called. The proxy uses this to stamp routing data (backend address,
    /// journey id) on the session *before* listeners see it — otherwise persistence rows are
    /// written with null routing columns.
    public Session createSession(UUID id, String address) {
        final Session session = new Session(id, decodedPacketCacheSize);
        session.initAddress(address);
        sessions.put(session.id, session);
        closeOnSessionClose(session);
        wireRoutines(session);
        final JsonObject data = new JsonObject();
        data.addProperty("address", address == null ? "?" : address);
        session.lifecycle.record(LifecycleEvent.Kind.CONNECT, -1, data);
        return session;
    }

    public void notifyOpened(Session session) {
        fire(openListeners, session);
    }

    private void closeOnSessionClose(Session session) {
        session.onClosed(() -> {
            final LifecycleEvent disconnect = session.lifecycle.record(LifecycleEvent.Kind.DISCONNECT, -1, new JsonObject());
            session.publish(new SessionEvent.Lifecycle(disconnect));
            fire(closeListeners, session);
            retain(session);
        });
    }

    private void retain(Session session) {
        final PlayerView.Retained snapshot = session.readState(player -> {
            if (player.uuid == null) return null;
            return PlayerView.Retained.from(session, player);
        });
        if (snapshot == null) {
            // Never-identified session (e.g. failed/STATUS connection): nothing to retain, and
            // nothing will ever evict() it, so drop it from the live map here.
            sessions.remove(session.id);
            return;
        }
        liveByPlayerUuid.remove(snapshot.uuid(), session);
        retainedByPlayerUuid.merge(snapshot.uuid(), snapshot, (existing, candidate) ->
                candidate.connectedAt() >= existing.connectedAt() ? candidate : existing);
    }

    public void evict(PlayerView.Retained snapshot) {
        retainedByPlayerUuid.remove(snapshot.uuid(), snapshot);
        sessions.remove(snapshot.sessionId());
        fire(evictListeners, snapshot);
    }

    public void closeAll() {
        for (Session s : sessions.values()) s.close();
        sessions.clear();
        liveByPlayerUuid.clear();
        retainedByPlayerUuid.clear();
    }

    // ---- routines / actions -------------------------------------------------------------

    public Collection<RegisteredRoutine> listRoutines() {
        return List.copyOf(routines.values());
    }

    public Collection<Routine> routines() {
        return routines.values().stream().map(RegisteredRoutine::routine).toList();
    }

    public Routine removeRoutine(UUID id) {
        final RegisteredRoutine removed = routines.remove(id);
        broadcastRoutines();
        return removed == null ? null : removed.routine();
    }

    public @Nullable RegisteredRoutine setRoutineEnabled(UUID id, boolean enabled) {
        final RegisteredRoutine current = routines.get(id);
        if (current == null) return null;
        final RegisteredRoutine next = new RegisteredRoutine(current.routine(), enabled);
        routines.put(id, next);
        broadcastRoutines();
        return next;
    }

    public RegisteredRoutine upsertRoutine(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        UUID id = obj.has("id") ? UUID.fromString(obj.get("id").getAsString()) : UUID.randomUUID();
        String name = obj.has("name") ? obj.get("name").getAsString() : "routine-" + id;
        String ql = obj.has("ql") && !obj.get("ql").isJsonNull() ? obj.get("ql").getAsString() : null;
        Routine.Trigger trigger = RoutineCodecs.decodeTrigger(obj.getAsJsonObject("trigger"));
        Action action = resolveAction(obj.getAsJsonObject("action"));
        long debounceMs = obj.has("debounceMs") ? obj.get("debounceMs").getAsLong() : 0;
        RegisteredRoutine previous = routines.get(id);
        boolean enabled = previous == null || previous.enabled();
        Routine r = new Routine(id, name, compileQuery(ql), trigger, action, debounceMs);
        RegisteredRoutine registered = new RegisteredRoutine(r, enabled);
        routines.put(id, registered);
        broadcastRoutines();
        return registered;
    }

    public Collection<RegisteredAction> listActions() { return actions.values(); }

    public RegisteredAction upsertAction(String json) {
        JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
        UUID id = obj.has("id") ? UUID.fromString(obj.get("id").getAsString()) : UUID.randomUUID();
        String name = obj.has("name") ? obj.get("name").getAsString() : "action-" + id;
        RegisteredAction ra = new RegisteredAction(id, name, resolveAction(obj.getAsJsonObject("action")));
        actions.put(id, ra);
        return ra;
    }

    public RegisteredAction removeAction(UUID id) { return actions.remove(id); }

    /// Resolve inline action JSON or `{"type":"ref","id":"<uuid>"}`.
    public Action resolveAction(JsonObject obj) {
        return RoutineCodecs.decodeAction(obj, refId -> {
            RegisteredAction ra = actions.get(refId);
            if (ra == null) throw new IllegalArgumentException("unknown action ref: " + refId);
            return ra.action();
        });
    }

    private void wireRoutines(Session session) {
        session.setActionExecutor((action, player) -> {
            final ActionRunner runner = actionRunner;
            if (runner != null) runner.execute(action, player);
        });
        session.send(new SessionMessage.SetRoutines(routineSnapshot));
    }

    private void broadcastRoutines() {
        routineSnapshot = List.copyOf(routines.values());
        for (Session session : sessions.values()) {
            session.send(new SessionMessage.SetRoutines(routineSnapshot));
        }
    }

    private Query compileQuery(@Nullable String ql) {
        if (queries == null) throw new IllegalStateException("registry has no QueryEngine; cannot compile routines");
        try { return queries.compile(ql); }
        catch (Exception e) {
            LOGGER.warn("query compile failed for `{}`: {}", ql, e.toString());
            final String source = ql == null ? "" : ql;
            return new Query() {
                @Override public String source() { return source; }
                @Override public boolean matches(PlayerState state) { return false; }
            };
        }
    }

    private static <T> void fire(List<Consumer<T>> listeners, T value) {
        for (Consumer<T> listener : listeners) {
            try { listener.accept(value); }
            catch (Throwable _) { /* subscribers defend themselves */ }
        }
    }
}
