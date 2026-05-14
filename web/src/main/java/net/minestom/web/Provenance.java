package net.minestom.web;

/// "Where did this value come from?" — pointer to the packet that last wrote a field.
///
/// Carried alongside every traceable [PlayerState] field via [PlayerState#provenance]. The
/// dashboard's profile page renders this as a quiet dotted underline; clicking opens the full
/// change history (kept per-field in [PlayerState#provenanceHistory]).
///
/// @param seq         monotonic sequence of the source packet on the per-connection ring
/// @param ts          epoch millis of the source packet
/// @param packetClass simple class name (e.g. `UpdateHealthPacket`)
/// @param direction   `CLIENTBOUND` or `SERVERBOUND`
public record Provenance(long seq, long ts, String packetClass, Direction direction) {

    /// A single recorded mutation — `source` is the packet, `prev`/`value` are the before/after.
    /// Stored in a bounded deque per field so the profile-page popover can show the recent
    /// history without re-scanning the packet ring.
    public record Entry(Provenance source, Object prev, Object value) {}
}
