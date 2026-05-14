package net.minestom.web.internal.http.routes;

import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import net.minestom.web.internal.http.PacketCatalog;
import net.minestom.web.internal.http.PacketCodec;
import net.minestom.web.internal.session.Session;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Packet injection REST endpoint (live proxy only).
public final class InjectRoutes {
    private InjectRoutes() {}

    public static void register(RoutesConfig app) {
        app.post("/api/players/{uuid}/inject", liveOnly((ctx, scope) -> {
            Session session = lookupLive(ctx, scope);
            if (session == null) return;
            JsonObject body = parseJsonBody(ctx);
            String cls = body.get("class").getAsString();
            JsonObject fields = body.has("fields") ? body.getAsJsonObject("fields") : new JsonObject();
            if (!scope.proxy.inject(session.playerUuid(), PacketCatalog.directionFor(cls), PacketCodec.decode(cls, fields))) {
                notFound(ctx, "no live connection");
                return;
            }
            jsonRaw(ctx, OK_JSON);
        }));
    }
}
