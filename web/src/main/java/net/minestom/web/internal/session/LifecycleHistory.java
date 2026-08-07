package net.minestom.web.internal.session;

import com.google.gson.JsonElement;
import net.minestom.web.LifecycleEvent;

import java.util.ArrayList;
import java.util.List;

/// Per-connection append-only log of [LifecycleEvent]s. Pure storage — emitters of events
/// (SessionRegistry for CONNECT/DISCONNECT, StateApplier for protocol-phase milestones) call
/// `record(...)` and then publish a [net.minestom.web.internal.session.SessionEvent.Lifecycle]
/// on the session stream. There is no listener registry here.
public final class LifecycleHistory {
    /// Hard cap so a misbehaving session can't grow this without bound. Way above the realistic
    /// upper end of ~20 events per connection.
    private static final int CAPACITY = 256;

    private final List<LifecycleEvent> events = new ArrayList<>();
    private long nextSeq = 1;

    public synchronized LifecycleEvent record(LifecycleEvent.Kind kind, long packetSeq, JsonElement data) {
        final LifecycleEvent e = new LifecycleEvent(
                nextSeq++, System.currentTimeMillis(), packetSeq, kind, data);
        if (events.size() >= CAPACITY) events.removeFirst();
        events.add(e);
        return e;
    }

    public synchronized List<LifecycleEvent> snapshot() {
        return List.copyOf(events);
    }
}
