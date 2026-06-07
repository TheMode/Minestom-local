package net.minestom.web.internal.http.routes;

import com.google.gson.JsonArray;
import io.javalin.config.RoutesConfig;
import net.minestom.web.internal.codec.MinimapCodec;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebJsonBuilders;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.session.PlayerView;
import net.minestom.web.internal.session.Session;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Player-related REST endpoints: list, single player, minimap, entities, registries, provenance, lifecycle.
public final class PlayerRoutes {
    private PlayerRoutes() {}

    public static void register(RoutesConfig app) {
        app.get("/api/players", scoped((ctx, scope) -> {
            JsonArray array = new JsonArray();
            for (PlayerView player : scope.registry.players()) array.add(player.playerJson());
            json(ctx, array);
        }));

        app.get("/api/players/{uuid}", scoped((ctx, scope) -> {
            PlayerView player = lookupPlayer(ctx, scope);
            jsonOrNotFound(ctx, player != null ? player.playerJson() : null, "not found");
        }));

        app.get("/api/players/{uuid}/minimap", livePlayerJson(MinimapCodec::snapshotJson));

        app.get("/api/players/{uuid}/entities/{eid}", scoped((ctx, scope) -> {
            Session session = lookupLive(ctx, scope);
            if (session == null) return;
            Integer eid = pathInt(ctx, "eid");
            if (eid == null) return;
            var snap = httpRead(session, player -> WebJsonBuilders.visibleEntityJson(player, eid));
            if (snap == null) { notFound(ctx, "not visible"); return; }
            int limit = parseLimit(ctx, 200, 5_000);
            var packets = scope.packetEvents(session, 0, limit, null, null, "ent." + eid);
            snap.add("packets", WebJson.encode(WebCodecs.PACKET_EVENT_LIST, packets));
            json(ctx, snap);
        }));

        app.get("/api/players/{uuid}/registries", scoped((ctx, scope) -> {
            Session session = lookupPlayerSession(ctx, scope);
            if (session == null) return;
            json(ctx, WebJsonBuilders.registriesJson(session.registries));
        }));

        app.get("/api/players/{uuid}/provenance", scoped((ctx, scope) -> {
            PlayerView player = lookupPlayer(ctx, scope);
            if (player == null) return;
            if (player instanceof PlayerView.Retained retained) {
                json(ctx, retained.provenanceHistoryJson(ctx.queryParam("field")));
                return;
            }
            Session session = ((PlayerView.Live) player).session();
            json(ctx, httpRead(session, state -> WebJsonBuilders.provenanceHistoryJson(state, ctx.queryParam("field"))));
        }));

        app.get("/api/players/{uuid}/lifecycle", scoped((ctx, scope) -> {
            Session session = lookupPlayerSession(ctx, scope);
            if (session == null) return;
            encoded(ctx, WebCodecs.LIFECYCLE_EVENT_LIST, session.lifecycle.snapshot());
        }));
    }
}
