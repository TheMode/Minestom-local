package net.minestom.web.internal.http.routes;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.javalin.http.Context;
import io.javalin.http.Handler;
import net.minestom.server.codec.Codec;
import net.minestom.web.Direction;
import net.minestom.web.PlayerState;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.http.JsonSerialization;
import net.minestom.web.internal.scope.DashboardScope;
import net.minestom.web.internal.session.PlayerView;
import net.minestom.web.internal.session.Session;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/// Stateless helpers shared by every route handler: scope-attribute lookup + handler wrappers,
/// JSON response writers, common entity lookups, and path/query parsers. The stateful scope
/// registry + replay lifecycle live in [ScopeRouter].
public final class RouteResponses {
    public static final String OK_JSON = "{\"ok\":true}";
    static final String SCOPE_ATTR = "dashboard.scope";

    private RouteResponses() {}

    // ---- Scope attribute + handler wrappers ----------------------------------------------

    public static @Nullable DashboardScope scope(Context ctx) {
        return ctx.attribute(SCOPE_ATTR);
    }

    @FunctionalInterface
    public interface ScopedHandler {
        void handle(Context ctx, DashboardScope scope) throws Exception;
    }

    /// Wraps a handler that needs the scope. 404s when no scope can be resolved.
    public static Handler scoped(ScopedHandler inner) {
        return ctx -> {
            DashboardScope scope = scope(ctx);
            if (scope == null) { notFound(ctx, "unknown scope"); return; }
            inner.handle(ctx, scope);
        };
    }

    /// Wraps a handler that requires a live proxy attached to the scope. 405s in replay mode.
    public static Handler liveOnly(ScopedHandler inner) {
        return ctx -> {
            DashboardScope scope = scope(ctx);
            if (scope == null) { notFound(ctx, "unknown scope"); return; }
            if (scope.proxy == null) {
                ctx.status(405).result("not supported in replay mode");
                return;
            }
            inner.handle(ctx, scope);
        };
    }

    /// Run `extractor` on a live player's state worker and send the result as JSON.
    public static Handler livePlayerJson(Function<PlayerState, Object> extractor) {
        return scoped((ctx, scope) -> {
            Session session = lookupLive(ctx, scope);
            if (session == null) return;
            json(ctx, session.readState(extractor::apply));
        });
    }

    // ---- JSON helpers --------------------------------------------------------------------

    public static void json(Context ctx, Object o) {
        ctx.contentType("application/json").result(JsonSerialization.GSON.toJson(o));
    }

    public static void jsonRaw(Context ctx, String body) {
        ctx.contentType("application/json").result(body);
    }

    /// Encode `value` via `codec` and write as JSON — replaces `json(ctx, WebJson.encode(codec, value))`.
    public static <T> void encoded(Context ctx, Codec<T> codec, T value) {
        jsonRaw(ctx, WebJson.encode(codec, value).toString());
    }

    public static <T> void jsonOrNotFound(Context ctx, @Nullable T value, String message) {
        if (value == null) { notFound(ctx, message); return; }
        json(ctx, value);
    }

    public static <T> void jsonOrNotFound(Context ctx, Optional<T> value, String message) {
        if (value.isEmpty()) { notFound(ctx, message); return; }
        json(ctx, value.get());
    }

    public static void notFound(Context ctx, String message) {
        ctx.status(404).result(message);
    }

    public static void badRequest(Context ctx, Throwable e) {
        ctx.status(400).contentType("application/json")
                .result(JsonSerialization.GSON.toJson(Map.of("error", String.valueOf(e.getMessage()))));
    }

    public static void wrap(Context ctx, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Exception e) {
            badRequest(ctx, e);
        }
    }

    @FunctionalInterface
    public interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ---- Lookup helpers ------------------------------------------------------------------

    public static @Nullable PlayerView lookupPlayer(Context ctx, DashboardScope scope) {
        UUID uuid = pathUuid(ctx, "uuid");
        if (uuid == null) return null;
        PlayerView player = scope.registry.player(uuid);
        if (player == null) { notFound(ctx, "not found"); return null; }
        return player;
    }

    public static @Nullable Session lookupSession(Context ctx, DashboardScope scope) {
        UUID id = pathUuid(ctx, "id");
        if (id == null) return null;
        Session session = scope.registry.sessionById(id);
        if (session == null) { notFound(ctx, "connection not found"); return null; }
        return session;
    }

    public static @Nullable Session lookupLive(Context ctx, DashboardScope scope) {
        PlayerView player = lookupPlayer(ctx, scope);
        if (player == null) return null;
        if (!(player instanceof PlayerView.Live live)) {
            notFound(ctx, "not live");
            return null;
        }
        return live.session();
    }

    /// Resolve the `Session` backing a player (live or retained). 404s if either lookup fails.
    public static @Nullable Session lookupPlayerSession(Context ctx, DashboardScope scope) {
        PlayerView player = lookupPlayer(ctx, scope);
        if (player == null) return null;
        Session session = scope.registry.sessionById(player.sessionId());
        if (session == null) { notFound(ctx, "connection gone"); return null; }
        return session;
    }

    // ---- Parsing helpers -----------------------------------------------------------------

    public static long parseLong(String s, long def) {
        if (s == null) return def;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /// Parse a path param as long; on failure sets 400 status and returns null.
    public static @Nullable Long pathLong(Context ctx, String name) {
        try {
            return Long.parseLong(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            ctx.status(400).result("bad " + name);
            return null;
        }
    }

    public static @Nullable Integer pathInt(Context ctx, String name) {
        try {
            return Integer.parseInt(ctx.pathParam(name));
        } catch (NumberFormatException e) {
            ctx.status(400).result("bad " + name);
            return null;
        }
    }

    public static @Nullable UUID pathUuid(Context ctx, String name) {
        try {
            return UUID.fromString(ctx.pathParam(name));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("invalid " + name);
            return null;
        }
    }

    public static @Nullable Direction parseDirection(String dir) {
        if (dir == null) return null;
        return switch (dir.toLowerCase()) {
            case "client", "clientbound", "cb" -> Direction.CLIENTBOUND;
            case "server", "serverbound", "sb" -> Direction.SERVERBOUND;
            default -> null;
        };
    }

    public static JsonObject parseJsonBody(Context ctx) {
        return JsonParser.parseString(ctx.body()).getAsJsonObject();
    }

    public static @Nullable String queryOrHeader(Context ctx, String queryName, String headerName) {
        String v = ctx.header(headerName);
        if (v == null || v.isEmpty()) v = ctx.queryParam(queryName);
        return v == null || v.isEmpty() ? null : v;
    }
}
