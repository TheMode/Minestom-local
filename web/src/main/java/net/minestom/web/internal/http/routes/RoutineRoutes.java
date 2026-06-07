package net.minestom.web.internal.http.routes;

import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import net.minestom.web.Action;
import net.minestom.web.Query;
import net.minestom.web.RegisteredRoutine;
import net.minestom.web.internal.codec.RoutineCodecs;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.session.ActionRunner;
import net.minestom.web.internal.session.Session;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Routine and action REST endpoints: CRUD for routines/actions, trigger execution.
public final class RoutineRoutes {
    private RoutineRoutes() {}

    public static void register(RoutesConfig app) {
        app.get("/api/routines", scoped((ctx, scope) ->
                json(ctx, RoutineCodecs.routinesJson(scope.registry.listRoutines()))));

        app.post("/api/routines", scoped((ctx, scope) ->
                json(ctx, RoutineCodecs.routineJson(scope.registry.upsertRoutine(ctx.body())))));

        app.put("/api/routines/{id}/enabled", scoped((ctx, scope) -> {
            UUID id = pathUuid(ctx, "id");
            if (id == null) return;
            Boolean enabled = requiredBoolean(ctx, parseJsonBody(ctx), "enabled");
            if (enabled == null) return;
            RegisteredRoutine routine = scope.registry.setRoutineEnabled(id, enabled);
            jsonOrNotFound(ctx, routine != null ? RoutineCodecs.routineJson(routine) : null, "unknown routine");
        }));

        app.delete("/api/routines/{id}", scoped((ctx, scope) -> {
            UUID id = pathUuid(ctx, "id");
            if (id == null) return;
            scope.registry.removeRoutine(id);
            ctx.status(204);
        }));

        app.get("/api/actions", scoped((ctx, scope) -> json(ctx, scope.registry.listActions())));

        app.post("/api/actions", scoped((ctx, scope) -> json(ctx, scope.registry.upsertAction(ctx.body()))));

        app.delete("/api/actions/{id}", scoped((ctx, scope) -> {
            UUID id = pathUuid(ctx, "id");
            if (id == null) return;
            scope.registry.removeAction(id);
            ctx.status(204);
        }));

        app.post("/api/trigger", scoped((ctx, scope) -> {
            JsonObject body = parseJsonBody(ctx);
            String qSrc = body.has("query") && !body.get("query").isJsonNull() ? body.get("query").getAsString() : null;
            Query q = scope.queries.compile(qSrc);
            JsonObject actionObj = requiredObject(ctx, body, "action");
            if (actionObj == null) return;
            Action action = scope.registry.resolveAction(actionObj);
            ActionRunner runner = scope.registry.actionRunner();
            int matched = 0, fired = 0;
            List<String> errors = new ArrayList<>();
            for (Session session : scope.registry.sessionsMatching(q)) {
                matched++;
                try {
                    session.callState(player -> {
                        if (runner != null) runner.execute(action, player);
                        return null;
                    });
                    fired++;
                } catch (Exception e) {
                    errors.add(session.playerUuid() + ": " + e.getMessage());
                }
            }
            encoded(ctx, WebCodecs.TRIGGER_RESULT, new WebPayloads.TriggerResult(matched, fired, errors));
        }));
    }
}
