package net.minestom.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Delta over [PlayerState]. Built by [PlayerState#drainPatch] under the per-connection lock,
/// shipped on the `player:<uuid>:state` WebSocket topic; the frontend merges it on top of the
/// REST-loaded snapshot.
///
/// Keys follow the dotted path scheme — @see PlayerState.
///
/// **Two kinds of edit.**
///   - [#values] — replace the value at a path. The most recent write in the coalesce window
///     wins; only paths that actually changed appear.
///   - [#appends] — append a batch of elements to a bounded list (ring buffer). Used for
///     `recentChat`, `sentChat`, `traffic.pingHistory`, `recentClicks`.
///
/// [#provenance] is the per-field source-of-truth pointer for paths whose source packet
/// changed in this window. Paths whose value flipped but whose source matches a previous patch
/// (rare) still appear here so the dashboard's provenance affordance stays in sync.
public record StatePatch(long seq, long ts,
                         Map<String, Object> values,
                         Map<String, Append> appends,
                         Map<String, Provenance> provenance) {

    /// Empty when nothing changed — the observer skips publishing in this case.
    public boolean isEmpty() {
        return values.isEmpty() && appends.isEmpty();
    }

    /// A batch of items appended to a ring buffer at `path` during the coalesce window.
    /// [#max] is the bounded size so the frontend can mirror the same eviction.
    public record Append(List<Object> elements, int max) {}

    /// Test-only convenience builder. Production patches always come from [PlayerState#drainPatch].
    public static StatePatch empty(long seq) {
        return new StatePatch(seq, System.currentTimeMillis(),
                new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
    }
}
