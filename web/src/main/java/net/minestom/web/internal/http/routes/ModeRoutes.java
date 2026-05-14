package net.minestom.web.internal.http.routes;

import io.javalin.config.RoutesConfig;
import net.minestom.server.MinecraftServer;
import net.minestom.web.ProxyConfig;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.scope.DashboardScope;

import java.util.ArrayList;
import java.util.List;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Mode discovery and replay scope management REST endpoints.
public final class ModeRoutes {
    private ModeRoutes() {}

    public static void register(RoutesConfig app, ProxyConfig config, ScopeRouter routeCtx) {
        app.get("/api/mode", ctx -> {
            DashboardScope s = scope(ctx);
            encoded(ctx, WebCodecs.MODE_PAYLOAD, new WebPayloads.ModePayload(
                    config.replayMode() ? "replay" : "live",
                    s == null ? null : s.summary(),
                    MinecraftServer.PROTOCOL_VERSION));
        });

        app.post("/api/replay", ctx -> wrap(ctx, () -> {
            if (!config.replayMode()) {
                ctx.status(405).result("not in replay mode");
                return;
            }
            DashboardScope scope = routeCtx.createReplayScope(ctx);
            encoded(ctx, WebCodecs.SCOPE_SUMMARY, scope.summary());
        }));

        app.delete("/api/replay/{id}", ctx -> {
            String id = ctx.pathParam("id");
            if (!routeCtx.scopeExists(id)) {
                notFound(ctx, "unknown scope");
                return;
            }
            routeCtx.removeScope(id);
            ctx.status(204);
        });

        app.get("/api/replay", ctx -> {
            List<WebPayloads.ScopeSummary> summaries = new ArrayList<>();
            for (DashboardScope s : routeCtx.scopes()) if (s.isReplay()) summaries.add(s.summary());
            encoded(ctx, WebCodecs.SCOPE_SUMMARY_LIST, summaries);
        });
    }
}
