package net.minestom.web.internal.session;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import net.kyori.adventure.text.Component;
import net.minestom.server.codec.Codec;
import net.minestom.web.Action;
import net.minestom.web.internal.AddressResolver;
import net.minestom.web.PlayerState;
import net.minestom.web.internal.codec.PatchValue;
import net.minestom.web.internal.codec.WebCodecs;
import net.minestom.web.internal.codec.WebJson;
import net.minestom.web.internal.expression.ExprValue;
import net.minestom.web.internal.expression.ExpressionEngine;
import net.minestom.web.internal.http.PacketCatalog;
import net.minestom.web.internal.http.PacketCodec;
import net.minestom.web.internal.http.PacketSchema.Kind;
import net.minestom.web.internal.proxy.TcpAcceptor;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public final class ActionRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ActionRunner.class);

    private final @Nullable TcpAcceptor proxy;
    private final ExpressionEngine expressions;

    public ActionRunner(@Nullable TcpAcceptor proxy, ExpressionEngine expressions) {
        this.proxy = proxy;
        this.expressions = expressions;
    }

    public void execute(Action action, PlayerState p) throws Exception {
        if (p.uuid == null) return;
        switch (action) {
            case Action.Inject inj -> inject(p, inj.className(), WebJson.encodeAsObject(PatchValue.STRING_MAP, inj.fields()));
            case Action.Chat c -> {
                Object raw = c.component();
                Component message = raw instanceof Component comp ? comp
                        : WebCodecs.componentFromEval(expressions.compile((String) raw).eval(p));
                JsonObject fields = new JsonObject();
                fields.add("message", WebJson.encode(Codec.COMPONENT, message));
                fields.addProperty("overlay", false);
                inject(p, "SystemChatPacket", fields);
            }
            case Action.SetCustom sc -> p.custom.put(sc.key(), expressions.compile(sc.value()).eval(p).toObject());
            case Action.Move m -> {
                if (proxy == null) return;
                final String spec = expressions.compile(m.address()).eval(p).str();
                if (spec == null || spec.isBlank()) {
                    throw new IllegalArgumentException("move: address expression '" + m.address() + "' returned blank");
                }
                // SRV resolution can block for seconds. Offload off the owner thread so the
                // player's mailbox keeps draining (keep-alives, packet apply) while DNS works.
                final UUID target = p.uuid;
                final TcpAcceptor px = proxy;
                Thread.ofVirtual().name("Minestom-Web-Move-" + target).start(() -> {
                    try { px.movePlayer(target, AddressResolver.parseMinecraft(spec)); }
                    catch (RuntimeException e) {
                        LOGGER.warn("move {} → {} failed: {}", target, spec, e.toString());
                    }
                });
            }
            case Action.Sequence seq -> { for (var a : seq.actions()) execute(a, p); }
        }
    }

    private void inject(PlayerState p, String className, JsonObject fields) throws Exception {
        if (proxy == null) return;
        proxy.inject(p.uuid, PacketCatalog.directionFor(className),
                PacketCodec.decode(className, fields, (src, kind) -> evaluate(src, kind, p)));
    }

    /// Evaluator passed into [PacketCodec#decode]: compile + evaluate the expression, then
    /// coerce to a JSON primitive that matches the field's kind. Failures bubble up with
    /// the source so the user sees `compile error in 'health +': expected expression`
    /// instead of an opaque `NumberFormatException` from Gson.
    private JsonPrimitive evaluate(String src, Kind kind, PlayerState p) {
        // Empty input means "use the field's default value" rather than "evaluate '' as an
        // expression" — empty would fail compile and the user expects unfilled rows to send 0/null.
        if (src.isEmpty()) return emptyDefault(kind);
        ExprValue v;
        try {
            v = expressions.compile(src).eval(p);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("expression '" + src + "': " + e.getMessage(), e);
        }
        return switch (kind) {
            case BYTE, SHORT, INT, LONG, FLOAT, DOUBLE -> numericPrimitive(v, kind, src);
            case CHAR -> {
                String s = v.str();
                yield new JsonPrimitive(s.isEmpty() ? "\0" : s.substring(0, 1));
            }
            case STRING -> new JsonPrimitive(v.str());
            case UUID -> new JsonPrimitive(v instanceof ExprValue.Null ? NIL_UUID : v.str());
            default -> throw new IllegalStateException("evaluator called for non-expression kind: " + kind);
        };
    }

    private static final String NIL_UUID = "00000000-0000-0000-0000-000000000000";

    private static JsonPrimitive emptyDefault(Kind kind) {
        return switch (kind) {
            case STRING -> new JsonPrimitive("");
            case CHAR -> new JsonPrimitive("\0");
            case UUID -> new JsonPrimitive(NIL_UUID);
            case FLOAT, DOUBLE -> new JsonPrimitive(0.0);
            case BYTE, SHORT, INT, LONG -> new JsonPrimitive(0);
            default -> throw new IllegalStateException("evaluator called for non-expression kind: " + kind);
        };
    }

    private static JsonPrimitive numericPrimitive(ExprValue v, Kind kind, String src) {
        double d = switch (v) {
            case ExprValue.Num n -> n.value();
            case ExprValue.Bool b -> b.value() ? 1 : 0;
            case ExprValue.Null _ -> throw new IllegalArgumentException(
                    "expression '" + src + "' returned null but field expects " + kind.name().toLowerCase());
            default -> throw new IllegalArgumentException(
                    "expression '" + src + "' returned " + v.getClass().getSimpleName()
                            + " but field expects " + kind.name().toLowerCase());
        };
        if (Double.isNaN(d) || Double.isInfinite(d))
            throw new IllegalArgumentException("expression '" + src + "' = " + d + " is not a finite number");
        // Range-check in double space — `(long) d` saturates at Long.MIN/MAX, so an int-space
        // bounds check on the cast result would silently pass for huge doubles. Use double
        // bounds compared against the double-precision representation of LONG min/max.
        return switch (kind) {
            case BYTE -> bounded(d, Byte.MIN_VALUE, Byte.MAX_VALUE, src, kind);
            case SHORT -> bounded(d, Short.MIN_VALUE, Short.MAX_VALUE, src, kind);
            case INT -> bounded(d, Integer.MIN_VALUE, Integer.MAX_VALUE, src, kind);
            case LONG -> bounded(d, Long.MIN_VALUE, Long.MAX_VALUE, src, kind);
            default -> new JsonPrimitive(d);
        };
    }

    private static JsonPrimitive bounded(double d, double min, double max, String src, Kind kind) {
        if (d < min || d > max)
            throw new IllegalArgumentException("expression '" + src + "' = " + d
                    + " out of range for " + kind.name().toLowerCase());
        return new JsonPrimitive((long) d);
    }
}
