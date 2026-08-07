package net.minestom.web;

import net.minestom.server.network.ConnectionState;
import net.minestom.server.network.packet.server.common.KeepAlivePacket;
import net.minestom.web.internal.session.PacketTimeline;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class PacketTimelineTest {

    @Test
    void recordsEventsAndDecodedCache() {
        PacketTimeline timeline = new PacketTimeline(8);
        for (int i = 0; i < 5; i++) {
            timeline.recordDecoded(Direction.CLIENTBOUND, ConnectionState.PLAY, new KeepAlivePacket(i), 10, i + 10);
        }

        var events = timeline.events(0, 100, null, null, null);
        assertEquals(5, events.size());
        assertEquals(1, events.getFirst().seq());
        assertEquals(14, events.getLast().ioEventSeq());
        assertEquals(5, timeline.latestSeq());

        var single = timeline.decoded(3);
        assertNotNull(single);
        assertEquals(new KeepAlivePacket(2), single.record());
    }

    @Test
    void decodedCacheEvictsButEventsRemain() {
        PacketTimeline timeline = new PacketTimeline(3);
        for (int i = 0; i < 6; i++) {
            timeline.recordDecoded(Direction.SERVERBOUND, ConnectionState.PLAY, new KeepAlivePacket(i), 5, i + 1);
        }

        assertEquals(6, timeline.events(0, 100, null, null, null).size());
        assertNull(timeline.decoded(1));
        assertNotNull(timeline.decoded(6));
    }

    @Test
    void eventsEvictBeyondCap() {
        PacketTimeline timeline = new PacketTimeline(0);
        int excess = PacketTimeline.MAX_EVENTS + 50;
        for (int i = 0; i < excess; i++) {
            timeline.recordDecoded(Direction.CLIENTBOUND, ConnectionState.PLAY, new KeepAlivePacket(i), 1, i);
        }
        var events = timeline.events(0, excess + 1, null, null, null);
        assertEquals(PacketTimeline.MAX_EVENTS, events.size());
        assertEquals(excess - PacketTimeline.MAX_EVENTS + 1, events.getFirst().seq());
        assertEquals(excess, events.getLast().seq());
    }
}
