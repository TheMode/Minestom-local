package net.minestom.web.internal.session;

import com.google.gson.JsonObject;
import net.minestom.web.PlayerState;
import net.minestom.web.internal.codec.WebJsonBuilders;
import net.minestom.web.internal.codec.WebJson;

import java.util.UUID;

public sealed interface PlayerView permits PlayerView.Live, PlayerView.Retained {
    UUID uuid();

    UUID sessionId();

    long connectedAt();

    long disconnectedAt();

    JsonObject playerJson();

    /// View backed by a still-connected [Session]; reads go through the mailbox.
    record Live(Session session) implements PlayerView {
        @Override
        public UUID uuid() {
            return session.playerUuid();
        }

        @Override
        public UUID sessionId() {
            return session.id;
        }

        @Override
        public long connectedAt() {
            return session.connectedAt;
        }

        @Override
        public long disconnectedAt() {
            return 0L;
        }

        @Override
        public JsonObject playerJson() {
            return session.tryReadState(p -> WebJsonBuilders.playerStateJson(p, session.jsonCoder),
                    Session.HTTP_READ_TIMEOUT_MS);
        }
    }

    /// Immutable snapshot retained after a player disconnects.
    record Retained(
            UUID uuid,
            UUID sessionId,
            long connectedAt,
            long disconnectedAt,
            JsonObject playerJson,
            JsonObject provenanceHistoryJson
    ) implements PlayerView {
        static Retained from(Session session, PlayerState player) {
            return new Retained(
                    player.uuid,
                    session.id,
                    player.connectedAt,
                    player.disconnectedAt,
                    WebJsonBuilders.playerStateJson(player, WebJson.CODER),
                    WebJsonBuilders.provenanceHistoryJson(player, null));
        }

        @Override
        public JsonObject playerJson() {
            return playerJson.deepCopy();
        }

        public JsonObject provenanceHistoryJson(String field) {
            if (field == null) return provenanceHistoryJson.deepCopy();
            JsonObject out = new JsonObject();
            if (provenanceHistoryJson.has(field)) {
                out.add(field, provenanceHistoryJson.get(field).deepCopy());
            }
            return out;
        }
    }
}
