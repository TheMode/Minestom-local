package net.minestom.web.internal.expression;

import net.minestom.web.ControlBridge;
import net.minestom.web.PlayerState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

public final class ExpressionEngine {

    record FieldInfo(String name, String detail) {}

    private record FieldDef(FieldInfo info, BiFunction<ExpressionEngine, PlayerState, ExprValue> accessor) {}

    private static final List<FieldDef> FIELDS = List.of(
            field("backend", "Current upstream `host:port` this player is bridged to", str(s -> s.backendAddress)),
            field("brand", "Reported client brand", str(s -> s.clientBrand)),
            field("dimension", "Current dimension identifier", str(s -> s.dimension)),
            field("flying", "true if currently flying", bool(s -> s.flying)),
            field("food", "Hunger level, 0-20", num(s -> s.food)),
            field("gamemode", "SURVIVAL, CREATIVE, ADVENTURE, or SPECTATOR", str(s -> s.gamemode)),
            field("global", "Server-pushed global NBT data", (engine, _) -> new ExprValue.Tag(engine.control.globalData())),
            field("hardcore", "true if the world is hardcore", bool(s -> s.hardcore)),
            field("health", "Player health, 0-20", num(s -> s.health)),
            field("locale", "Client locale, e.g. en_us", str(s -> s.locale)),
            field("name", "Player username", str(s -> s.username)),
            field("onGround", "true if on ground", bool(s -> s.onGround)),
            field("ping", "Round-trip ping in milliseconds", num(s -> s.traffic.pingMs)),
            field("pos", "Position 3-tuple, for example distance(pos, (0, 64, 0))",
                    (_, s) -> new ExprValue.Vec3(s.posX, s.posY, s.posZ)),
            field("protocolVersion", "Numeric protocol version", num(s -> s.protocolVersion)),
            field("server", "Server-pushed NBT data (alias of serverData.*)", (_, s) -> new ExprValue.Tag(s.serverData)),
            field("serverData", "Server-pushed NBT data (dotted path)", (_, s) -> new ExprValue.Tag(s.serverData)),
            field("traffic", "Connection traffic counters and transport state", s -> ExprValue.of(s.traffic)),
            field("uuid", "Mojang UUID (string)", s -> s.uuid == null ? ExprValue.NULL : new ExprValue.Str(s.uuid.toString())),
            field("xpLevel", "Experience level", num(s -> s.xpLevel))
    );

    private static final Map<String, FieldDef> FIELDS_BY_NAME = fieldsByName();

    private final ControlBridge control;

    public ExpressionEngine(ControlBridge control) { this.control = control; }

    static List<FieldInfo> fieldInfo() {
        return FIELDS.stream().map(FieldDef::info).toList();
    }

    public Function<PlayerState, ExprValue> rootAccessor(String name) {
        FieldDef field = FIELDS_BY_NAME.get(name);
        return field == null ? reflect(name) : s -> field.accessor().apply(this, s);
    }

    public Expr compile(String src) {
        ValueParser p = newParser(src);
        Expr ast = p.parseExpr();
        if (p.peek().kind() != Lexer.Kind.EOF)
            throw new IllegalArgumentException("Trailing tokens at " + p.peek());
        return ast;
    }

    public ValueParser newParser(String src) {
        return new ValueParser(Lexer.tokenize(src), this::rootAccessor);
    }

    private static Function<PlayerState, ExprValue> num(ToDoubleFunction<PlayerState> f) {
        return s -> new ExprValue.Num(f.applyAsDouble(s));
    }

    private static Function<PlayerState, ExprValue> str(Function<PlayerState, String> f) {
        return s -> new ExprValue.Str(f.apply(s));
    }

    private static Function<PlayerState, ExprValue> bool(Predicate<PlayerState> f) {
        return s -> new ExprValue.Bool(f.test(s));
    }

    private static FieldDef field(String name, String detail, Function<PlayerState, ExprValue> accessor) {
        return field(name, detail, (_, state) -> accessor.apply(state));
    }

    private static FieldDef field(String name, String detail, BiFunction<ExpressionEngine, PlayerState, ExprValue> accessor) {
        return new FieldDef(new FieldInfo(name, detail), accessor);
    }

    private static Map<String, FieldDef> fieldsByName() {
        var fields = new LinkedHashMap<String, FieldDef>();
        for (FieldDef field : FIELDS) fields.put(field.info().name(), field);
        return Map.copyOf(fields);
    }

    private static Function<PlayerState, ExprValue> reflect(String name) {
        return s -> {
            try { return ExprValue.of(s.getClass().getField(name).get(s)); }
            catch (ReflectiveOperationException _) { return ExprValue.NULL; }
        };
    }
}
