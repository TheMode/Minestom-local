package net.minestom.web;

import net.minestom.web.internal.session.Session;
import net.minestom.web.internal.session.SessionRegistry;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRegistryTest {

    @Test
    void playersMatchingFiltersCurrentDeduplicatedPlayers() {
        SessionRegistry registry = new SessionRegistry(16);
        UUID uuid = UUID.randomUUID();

        Session stale = startedSession(registry, "old");
        stale.mutateState(player -> {
            player.uuid = uuid;
            player.health = 1;
            stale.refreshPlayerUuid();
        });
        stale.close();

        Session current = startedSession(registry, "current");
        current.mutateState(player -> {
            player.uuid = uuid;
            player.health = 20;
            current.refreshPlayerUuid();
        });

        assertTrue(registry.sessionsMatching(query(p -> p.health < 5)).isEmpty());
        assertEquals(current.id, registry.players().iterator().next().sessionId());
    }

    @Test
    void playersMatchingReturnsMatchingCurrentPlayers() {
        SessionRegistry registry = new SessionRegistry(16);
        Session alice = startedSession(registry, "alice");
        alice.mutateState(player -> {
            player.uuid = UUID.randomUUID();
            player.health = 4;
            alice.refreshPlayerUuid();
        });

        Session bob = startedSession(registry, "bob");
        bob.mutateState(player -> {
            player.uuid = UUID.randomUUID();
            player.health = 12;
            bob.refreshPlayerUuid();
        });

        Collection<Session> matches = registry.sessionsMatching(query(p -> p.health < 5));

        assertEquals(1, matches.size());
        assertTrue(matches.contains(alice));
    }

    @Test
    void queryErrorsDoNotMatchPlayers() {
        SessionRegistry registry = new SessionRegistry(16);
        Session session = startedSession(registry, "alice");
        session.mutateState(player -> {
            player.uuid = UUID.randomUUID();
            session.refreshPlayerUuid();
        });

        assertTrue(registry.sessionsMatching(query(_ -> { throw new IllegalStateException("bad query"); })).isEmpty());
        assertFalse(registry.playerMatches(query(_ -> { throw new IllegalStateException("bad query"); }), session));
    }

    private static Session startedSession(SessionRegistry registry, String address) {
        final Session session = registry.openSession(address);
        session.startDefaultLoop();
        return session;
    }

    private static Query query(Predicate<PlayerState> predicate) {
        return new Query() {
            @Override
            public String source() {
                return "";
            }

            @Override
            public boolean matches(PlayerState state) {
                return predicate.test(state);
            }
        };
    }
}
