package net.minestom.web.internal.http.routes;

import com.google.gson.JsonObject;
import io.javalin.config.RoutesConfig;
import net.minestom.web.PacketRecord;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebJsonBuilders;
import net.minestom.web.internal.codec.WebPayloads;
import net.minestom.web.internal.http.JsonSerialization;
import net.minestom.web.internal.http.PacketCatalog;
import net.minestom.web.internal.http.PacketSchema;
import net.minestom.web.internal.replay.PacketSeqResolver;
import net.minestom.web.internal.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

import static net.minestom.web.internal.http.routes.RouteResponses.*;

/// Packet-related REST endpoints: timeline, subjects, single packet, known packets, describe.
public final class PacketRoutes {
    private static final Logger LOGGER = LoggerFactory.getLogger(PacketRoutes.class);

    private PacketRoutes() {}

    public static void register(RoutesConfig app) {
        app.get("/api/connections/{id}/packets", scoped((ctx, scope) -> {
            Session session = lookupSession(ctx, scope);
            if (session == null) return;
            long since = parseLong(ctx.queryParam("since"), 0L);
            int limit = parseLimit(ctx, 200, 5_000);
            var recs = scope.packetEvents(session, since, limit,
                    parseDirection(ctx.queryParam("dir")), ctx.queryParam("class"), ctx.queryParam("subject"));
            encoded(ctx, WebCodecs.PACKET_EVENT_LIST, recs);
        }));

        app.get("/api/connections/{id}/packets/subjects", scoped((ctx, scope) -> {
            Session session = lookupSession(ctx, scope);
            if (session == null) return;
            int limit = parseLimit(ctx, 5_000, 50_000);
            var recs = scope.packetEvents(session, 0, limit, null, null, null);
            long now = System.currentTimeMillis();
            Map<String, Integer> recent = new HashMap<>();
            for (var s : recs) {
                if (now - s.ts() <= 1_000L) recent.merge(s.subject(), 1, Integer::sum);
            }
            Map<String, WebPayloads.SubjectAggregate> agg = new LinkedHashMap<>();
            for (var s : recs) {
                agg.compute(s.subject(), (k, cur) -> new WebPayloads.SubjectAggregate(
                        s.subject(), s.subjectLabel(), s.subjectGroup(),
                        cur == null ? 1 : cur.count() + 1,
                        cur == null ? s.ts() : Math.max(cur.lastTs(), s.ts()),
                        recent.getOrDefault(s.subject(), 0)));
            }
            encoded(ctx, WebCodecs.SUBJECT_AGGREGATE_LIST, new ArrayList<>(agg.values()));
        }));

        app.get("/api/connections/{id}/packets/{seq}", scoped((ctx, scope) -> {
            Session session = lookupSession(ctx, scope);
            if (session == null) return;
            Long seqNum = pathLong(ctx, "seq");
            if (seqNum == null) return;

            PacketRecord rec = session.packets.decoded(seqNum);
            if (rec == null) {
                final Path archive = scope.archivePath();
                if (archive != null) {
                    try {
                        rec = PacketSeqResolver.resolve(archive, session.id, seqNum);
                    } catch (Exception e) {
                        LOGGER.debug("packet {} resolve from {} failed: {}", seqNum, archive, e.toString());
                    }
                }
            }
            if (rec == null) {
                notFound(ctx, "packet seq not in memory or archive");
                return;
            }
            JsonObject o = WebJsonBuilders.packetRecordJson(rec, PacketCatalog.classify(rec));
            try {
                o.add("record", JsonSerialization.GSON.toJsonTree(rec.record()));
            } catch (Exception e) {
                o.addProperty("recordError", e.toString());
            }
            json(ctx, o);
        }));

        app.get("/api/packets/known", ctx -> {
            boolean analyzable = "true".equalsIgnoreCase(ctx.queryParam("analyzable"));
            encoded(ctx, PacketCatalog.Entry.LIST_CODEC,
                    analyzable ? PacketCatalog.entriesAnalyzable() : PacketCatalog.entries());
        });

        app.get("/api/packet/describe/{class}", ctx -> {
            try {
                json(ctx, PacketSchema.describe(ctx.pathParam("class")));
            } catch (Exception e) {
                notFound(ctx, e.getMessage());
            }
        });
    }
}
