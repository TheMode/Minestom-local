package net.minestom.web.internal.http.routes;

import io.javalin.config.RoutesConfig;
import net.minestom.server.item.Material;
import net.minestom.web.internal.AddressResolver;
import net.minestom.web.ControlPacket;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.persist.PersistentHistory;
import net.minestom.web.internal.proxy.ProxyMetrics;
import net.minestom.web.internal.renderer.ItemIconRenderer;
import net.minestom.web.internal.session.Session;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Miscellaneous REST endpoints: server info, materials, icons, persistence, export, mailbox, control packets, proxy metrics.
public final class MiscRoutes {
    private MiscRoutes() {}

    public static void register(RoutesConfig app, ItemIconRenderer itemIcons) {
        app.get("/api/server", scoped((ctx, scope) -> encoded(ctx, WebCodecs.SERVER_INFO,
                new WebPayloads.ServerInfo(scope.createdAt, scope.registry.players().size(),
                        Arrays.asList(scope.metrics.snapshot())))));

        app.get("/api/materials", ctx -> {
            List<String> keys = new ArrayList<>();
            for (Material m : Material.values()) keys.add(m.key().value());
            encoded(ctx, WebCodecs.STRING_LIST, keys);
        });

        app.get("/api/material-icon/{id}", ctx -> {
            byte[] png = itemIcons.iconFor(ctx.pathParam("id"));
            if (png == null) { ctx.status(404); return; }
            ctx.contentType("image/png");
            ctx.header("Cache-Control", "public, max-age=86400, immutable");
            ctx.result(png);
        });

        app.get("/api/persistence", ctx -> {
            var scope = scope(ctx);
            PersistentHistory p = scope == null ? null : scope.persistence;
            encoded(ctx, WebCodecs.PERSISTENCE_INFO, new WebPayloads.PersistenceInfo(
                    p != null,
                    p == null ? null : p.protocolVersion(),
                    p == null ? null : p.sessionId(),
                    p == null ? null : p.path().toString()));
        });

        app.get("/api/export.sqlite", scoped((ctx, scope) -> {
            if (scope.persistence == null) { notFound(ctx, "persistence disabled"); return; }
            Path tmp = Files.createTempFile("sessions-export-", ".sqlite");
            try {
                scope.persistence.exportSnapshot(tmp);
                ctx.contentType("application/vnd.sqlite3");
                ctx.header("Content-Disposition",
                        "attachment; filename=\"sessions-" + System.currentTimeMillis() + ".sqlite\"");
                ctx.result(Files.newInputStream(tmp));
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception _) {}
            }
        }));

        app.get("/api/sessions/mailbox", scoped((ctx, scope) -> {
            List<WebPayloads.MailboxRow> rows = new ArrayList<>();
            for (Session session : scope.registry.sessions()) {
                rows.add(new WebPayloads.MailboxRow(session.id, session.playerUuid(),
                        session.stateQueueDepth(), session.listenerCount()));
            }
            encoded(ctx, WebCodecs.MAILBOX_ROW_LIST, rows);
        }));

        app.get("/api/control/packets", ctx -> {
            List<String> names = new ArrayList<>();
            for (Class<?> permitted : ControlPacket.class.getPermittedSubclasses()) names.add(permitted.getSimpleName());
            encoded(ctx, WebCodecs.STRING_LIST, names);
        });

        app.get("/api/proxy/metrics", liveOnly((ctx, scope) ->
                jsonRaw(ctx, WebJson.encodeAsObject(ProxyMetrics.CODEC, scope.proxy.metrics().snapshot()).toString())));

        // Move a player to any reachable Minecraft server. Mints a transfer cookie + injects
        // CookieStore + Transfer. 404 if the player isn't online, 400 if the address spec is
        // invalid. Body shape: {"address": "play.example.com"} or {"address": "host:port"}.
        app.post("/api/players/{uuid}/move", liveOnly((ctx, scope) -> {
            final UUID uuid;
            try { uuid = UUID.fromString(ctx.pathParam("uuid")); }
            catch (IllegalArgumentException e) { badRequest(ctx, e); return; }
            final com.google.gson.JsonObject body = parseJsonBody(ctx);
            if (!body.has("address") || body.get("address").isJsonNull()) {
                badRequest(ctx, new IllegalArgumentException("address required"));
                return;
            }
            final InetSocketAddress target;
            try { target = AddressResolver.parseMinecraft(body.get("address").getAsString()); }
            catch (IllegalArgumentException e) { badRequest(ctx, e); return; }
            if (!scope.proxy.movePlayer(uuid, target)) {
                notFound(ctx, "no live connection or move rejected");
                return;
            }
            jsonRaw(ctx, OK_JSON);
        }));
    }
}
