package net.minestom.web.internal.http.routes;

import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.codec.WebJson;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Console and metrics REST endpoints: history, command, latest metrics, global data.
public final class ConsoleRoutes {
    private ConsoleRoutes() {}

    public static void register(RoutesConfig app) {
        app.get("/api/console/history", scoped((ctx, scope) ->
                encoded(ctx, WebCodecs.CONSOLE_LINE_LIST, scope.control.consoleHistory())));

        app.post("/api/console/command", liveOnly((ctx, scope) -> {
            JsonObject body = parseJsonBody(ctx);
            String command = body.get("command").getAsString();
            if (command.isBlank()) {
                badRequest(ctx, new IllegalArgumentException("empty command"));
                return;
            }
            scope.control.sendCommand(command);
            jsonRaw(ctx, OK_JSON);
        }));

        app.get("/api/metrics/latest", scoped((ctx, scope) -> {
            var m = scope.control.latestMetrics();
            if (m == null) { jsonRaw(ctx, "null"); return; }
            jsonRaw(ctx, WebJson.encodeAsObject(WebCodecs.CONTROL_METRICS, m).toString());
        }));

        app.get("/api/global", scoped((ctx, scope) ->
                encoded(ctx, WebCodecs.GLOBAL_DATA, new WebPayloads.GlobalData(scope.control.globalData()))));
    }
}
