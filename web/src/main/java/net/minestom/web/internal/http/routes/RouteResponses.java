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
            json(ctx, httpRead(session, extractor));
        });
    }

    /// Read player state on its owner thread, bounded by the shared HTTP timeout. A wedged or
    /// slow owner surfaces as a 503/504 (`MailboxException`, mapped in DashboardServer) rather
    /// than pinning the request thread on an unbounded wait. Request handlers must use this in
    /// preference to the unbounded `Session#readState`.
    public static <T> T httpRead(Session session, Function<PlayerState, T> body) {
        return session.tryReadState(body, Session.HTTP_READ_TIMEOUT_MS);
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

    /// Parse the `limit` query param, clamped to [1, max] so a caller can't force an unbounded read.
    public static int parseLimit(Context ctx, int def, int max) {
        final long raw = parseLong(ctx.queryParam("limit"), def);
        return (int) Math.clamp(raw, 1, max);
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
        final var el = JsonParser.parseString(ctx.body());
        if (!el.isJsonObject()) throw new IllegalArgumentException("expected a JSON object body");
        return el.getAsJsonObject();
    }

    // ---- Body-field helpers: 400 + null on missing/invalid (mirrors pathUuid's contract) -----

    /// Required string field that may be empty (e.g. a match-all query). 400 + null when the
    /// field is absent, null, or not a string.
    public static @Nullable String stringField(Context ctx, JsonObject body, String field) {
        final var el = body.get(field);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) return el.getAsString();
        ctx.status(400).result("missing or invalid '" + field + "'");
        return null;
    }

    /// Required, non-blank string field. 400 + null when absent, null, not a string, or blank.
    public static @Nullable String requiredString(Context ctx, JsonObject body, String field) {
        final String v = stringField(ctx, body, field);
        if (v != null && v.isBlank()) {
            ctx.status(400).result("'" + field + "' must not be blank");
            return null;
        }
        return v;
    }

    /// Required boolean field. 400 + null (not `false`!) when absent or not a boolean — callers
    /// must null-check before unboxing.
    public static @Nullable Boolean requiredBoolean(Context ctx, JsonObject body, String field) {
        final var el = body.get(field);
        if (el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isBoolean()) return el.getAsBoolean();
        ctx.status(400).result("missing or invalid '" + field + "'");
        return null;
    }

    /// Required nested object field. 400 + null when absent or not an object.
    public static @Nullable JsonObject requiredObject(Context ctx, JsonObject body, String field) {
        final var el = body.get(field);
        if (el != null && el.isJsonObject()) return el.getAsJsonObject();
        ctx.status(400).result("missing or invalid '" + field + "'");
        return null;
    }

    public static @Nullable String queryOrHeader(Context ctx, String queryName, String headerName) {
        String v = ctx.header(headerName);
        if (v == null || v.isEmpty()) v = ctx.queryParam(queryName);
        return v == null || v.isEmpty() ? null : v;
    }
}
