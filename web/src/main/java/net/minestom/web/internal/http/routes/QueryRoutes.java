package net.minestom.web.internal.http.routes;

import io.javalin.config.RoutesConfig;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.expression.MqlConstants;
import net.minestom.web.internal.session.Session;

import java.util.ArrayList;
import java.util.List;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// MQL expression and query REST endpoints: compile expression, run query, get constants.
public final class QueryRoutes {
    private QueryRoutes() {}

    public static void register(RoutesConfig app) {
        app.get("/api/mql/constants", ctx -> json(ctx, MqlConstants.payload()));

        app.post("/api/expression/compile", scoped((ctx, scope) -> {
            String src = stringField(ctx, parseJsonBody(ctx), "src");
            if (src == null) return;
            scope.expressions.compile(src);
            jsonRaw(ctx, OK_JSON);
        }));

        app.post("/api/query", scoped((ctx, scope) -> {
            String ql = stringField(ctx, parseJsonBody(ctx), "ql");
            if (ql == null) return;
            var q = scope.queries.compile(ql);
            List<String> matches = new ArrayList<>();
            for (Session session : scope.registry.sessionsMatching(q)) matches.add(String.valueOf(session.playerUuid()));
            encoded(ctx, WebCodecs.QUERY_RESULT, new WebPayloads.QueryResult(matches));
        }));
    }
}
