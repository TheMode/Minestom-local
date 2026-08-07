package net.minestom.web.internal.session;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.Packet;
import net.minestom.web.Direction;
import net.minestom.web.PacketEvent;
import net.minestom.web.PacketRecord;
import net.minestom.web.internal.http.PacketCatalog;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Per-session packet timeline plus a bounded decoded-packet cache.
///
/// Timeline events are append-only and cover the whole connection, capped at [#MAX_EVENTS] so
/// long-lived sessions do not grow heap without bound. Full decoded packet records are
/// intentionally bounded because the inspector can recover old packets from persisted raw
/// bytes when persistence is enabled.
public final class PacketTimeline {
    /// In-memory event cap per connection. Persistence / archive still hold full history.
    public static final int MAX_EVENTS = 32_768;

    private final int decodedCacheSize;
    private final List<PacketEvent> events = new ArrayList<>();
    private final LinkedHashMap<Long, PacketRecord> decodedCache;
    private long nextSeq = 1;

    public PacketTimeline(int decodedCacheSize) {
        if (decodedCacheSize < 0) throw new IllegalArgumentException("decodedCacheSize < 0");
        this.decodedCacheSize = decodedCacheSize;
        this.decodedCache = new LinkedHashMap<>(Math.max(16, decodedCacheSize), 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Long, PacketRecord> eldest) {
                return PacketTimeline.this.decodedCacheSize > 0 && size() > PacketTimeline.this.decodedCacheSize;
            }
        };
    }

    public synchronized PacketRecord recordDecoded(Direction direction, ConnectionState state,
                                                   Packet packet, int sizeBytes, long ioEventSeq) {
        final long seq = nextSeq++;
        final long ts = System.currentTimeMillis();
        final PacketCatalog.Subject subject = PacketCatalog.classify(packet);
        events.add(new PacketEvent(seq, ts, direction, state, packet.getClass().getSimpleName(), sizeBytes,
                subject.id(), subject.label(), subject.groupId(), ioEventSeq));
        if (events.size() > MAX_EVENTS) events.removeFirst();

        final PacketRecord record = new PacketRecord(seq, ts, direction, state,
                packet.getClass().getSimpleName(), sizeBytes, packet);
        if (decodedCacheSize != 0) decodedCache.put(seq, record);
        return record;
    }

    public synchronized void bumpSeq() {
        nextSeq++;
    }

    public synchronized void seedAfter(long packetSeq) {
        if (packetSeq >= 0) nextSeq = packetSeq + 1;
    }

    public synchronized long latestSeq() {
        return nextSeq - 1;
    }

    public synchronized @Nullable PacketRecord decoded(long seq) {
        return decodedCache.get(seq);
    }

    public synchronized @Nullable PacketEvent latestEvent() {
        return events.isEmpty() ? null : events.getLast();
    }

    public synchronized List<PacketEvent> events(long sinceSeq, int limit,
                                                 @Nullable Direction dirFilter,
                                                 @Nullable String classFilter,
                                                 @Nullable String subjectFilter) {
        if (limit <= 0) return List.of();
        final List<PacketEvent> out = new ArrayList<>(Math.min(limit, events.size()));
        for (PacketEvent event : events) {
            if (event.seq() <= sinceSeq) continue;
            if (dirFilter != null && event.direction() != dirFilter) continue;
            if (classFilter != null && !event.className().equalsIgnoreCase(classFilter)) continue;
            if (subjectFilter != null && !subjectFilter.isEmpty() && !event.subject().equals(subjectFilter)) continue;
            out.add(event);
            if (out.size() >= limit) break;
        }
        return out;
    }
}
