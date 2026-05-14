package net.minestom.web.internal.http.routes;

import com.google.gson.JsonParser;
import io.javalin.config.RoutesConfig;
import net.minestom.web.Throttle;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.proxy.ThrottleManager;

import java.util.UUID;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Throttle REST endpoints (live proxy only): get/set global and per-player throttles.
public final class ThrottleRoutes {
    private ThrottleRoutes() {}

    public static void register(RoutesConfig app) {
        app.get("/api/throttle", liveOnly((ctx, scope) -> {
            ThrottleManager tm = scope.proxy.throttles();
            encoded(ctx, WebCodecs.THROTTLES_SNAPSHOT,
                    new WebPayloads.ThrottlesSnapshot(tm.global(), tm.perPlayer()));
        }));

        app.put("/api/throttle/global", liveOnly((ctx, scope) -> {
            ThrottleManager tm = scope.proxy.throttles();
            tm.setGlobal(decodeThrottle(ctx.body()));
            encoded(ctx, WebCodecs.THROTTLE_OPTIONAL, tm.global());
        }));

        app.delete("/api/throttle/global", liveOnly((ctx, scope) -> {
            scope.proxy.throttles().setGlobal(null);
            ctx.status(204);
        }));

        app.put("/api/throttle/players/{uuid}", liveOnly((ctx, scope) -> {
            UUID uuid = pathUuid(ctx, "uuid");
            if (uuid == null) return;
            ThrottleManager tm = scope.proxy.throttles();
            tm.setForPlayer(uuid, decodeThrottle(ctx.body()));
            encoded(ctx, WebCodecs.THROTTLE_OPTIONAL, tm.perPlayer().get(uuid));
        }));

        app.delete("/api/throttle/players/{uuid}", liveOnly((ctx, scope) -> {
            UUID uuid = pathUuid(ctx, "uuid");
            if (uuid == null) return;
            scope.proxy.throttles().setForPlayer(uuid, null);
            ctx.status(204);
        }));
    }

    private static Throttle decodeThrottle(String body) {
        if (body == null || body.isBlank()) return null;
        return WebJson.decode(WebCodecs.THROTTLE_OPTIONAL, JsonParser.parseString(body));
    }
}
