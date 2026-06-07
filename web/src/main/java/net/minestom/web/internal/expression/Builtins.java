package net.minestom.web.internal.expression;

import net.minestom.server.instance.block.Block;
import net.minestom.web.PlayerState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoubleBinaryOperator;

final class Builtins {

    @FunctionalInterface
    interface Unary { ExprValue apply(ExprValue v, PlayerState s); }

    @FunctionalInterface
    interface Fn { ExprValue apply(List<Expr> args, PlayerState s); }

    static record FunctionInfo(String name, String sig, String detail, boolean pipe) {}

    private interface Def { FunctionInfo info(); }

    private record UnaryDef(FunctionInfo info, Unary fn) implements Def {}

    private record FnDef(FunctionInfo info, Fn fn) implements Def {}

    private static final List<UnaryDef> UNARY_DEFS = List.of(
            unary("blockKey(id)", "Block state id to namespaced block key (e.g. minecraft:stone).", (v, s) -> {
                if (v instanceof ExprValue.Null) return ExprValue.NULL;
                Block block = Block.fromStateId((int) v.num());
                return block == null ? ExprValue.NULL : new ExprValue.Str(block.key().asString());
            }),
            unary("upper(s)", "Uppercase a string.", (v, s) -> new ExprValue.Str(v.str().toUpperCase(Locale.ROOT))),
            unary("lower(s)", "Lowercase a string.", (v, s) -> new ExprValue.Str(v.str().toLowerCase(Locale.ROOT))),
            unary("str(x)", "Coerce any value to its string form.", (v, s) -> new ExprValue.Str(v.str())),
            unary("num(x)", "Coerce any value to a number.", (v, s) -> new ExprValue.Num(v.num())),
            unary("len(s)", "Length of a string.", (v, s) -> new ExprValue.Num(v.str().length())),
            unary("floor(n)", "Round down to integer.", (v, s) -> new ExprValue.Num(Math.floor(v.num()))),
            unary("ceil(n)", "Round up to integer.", (v, s) -> new ExprValue.Num(Math.ceil(v.num()))),
            unary("round(n)", "Round to nearest integer.", (v, s) -> new ExprValue.Num(Math.round(v.num()))),
            unary("abs(n)", "Absolute value.", (v, s) -> new ExprValue.Num(Math.abs(v.num())))
    );

    private static final List<FnDef> FUNCTION_DEFS = List.of(
            fn("distance(a, b)", "Euclidean distance between two 3-tuples.", Builtins::distance),
            fn("blockId(x, y, z)", "Block state id at world coordinates (null if chunk not loaded).", Builtins::blockId),
            fn("concat(a, b, ...)", "Concatenate strings.", (args, s) -> {
                var sb = new StringBuilder();
                for (Expr a : args) sb.append(a.eval(s).str());
                return new ExprValue.Str(sb.toString());
            }),
            fn("substr(s, from[, to])", "Substring with clamped indices.", Builtins::substr),
            fn("min(a, b, ...)", "Numeric minimum.", (args, s) -> new ExprValue.Num(reduce(args, s, Math::min))),
            fn("max(a, b, ...)", "Numeric maximum.", (args, s) -> new ExprValue.Num(reduce(args, s, Math::max)))
    );

    static final Map<String, UnaryDef> UNARY = byName(UNARY_DEFS);
    static final Map<String, FnDef> FUNCTIONS = byName(FUNCTION_DEFS);

    private Builtins() {}

    static List<FunctionInfo> functionInfo() {
        var out = new ArrayList<FunctionInfo>(UNARY_DEFS.size() + FUNCTION_DEFS.size());
        for (UnaryDef def : UNARY_DEFS) out.add(def.info());
        for (FnDef def : FUNCTION_DEFS) out.add(def.info());
        out.sort(Comparator.comparing(FunctionInfo::name));
        return List.copyOf(out);
    }

    static ExprValue applyUnary(String name, ExprValue value, PlayerState s) {
        UnaryDef fn = UNARY.get(name);
        if (fn == null) throw new IllegalArgumentException("Unknown transform: " + name);
        return fn.fn().apply(value, s);
    }

    static ExprValue apply(String name, List<Expr> args, PlayerState s) {
        UnaryDef unary = UNARY.get(name);
        if (unary != null) {
            if (args.size() != 1) throw new IllegalArgumentException(name + " expects 1 argument");
            return unary.fn().apply(args.getFirst().eval(s), s);
        }
        FnDef fn = FUNCTIONS.get(name);
        if (fn == null) throw new IllegalArgumentException("Unknown function: " + name);
        return fn.fn().apply(args, s);
    }

    private static UnaryDef unary(String sig, String detail, Unary fn) {
        return new UnaryDef(new FunctionInfo(name(sig), sig, detail, true), fn);
    }

    private static FnDef fn(String sig, String detail, Fn fn) {
        return new FnDef(new FunctionInfo(name(sig), sig, detail, false), fn);
    }

    private static String name(String sig) {
        return sig.substring(0, sig.indexOf('('));
    }

    private static <T extends Def> Map<String, T> byName(List<T> defs) {
        var out = new LinkedHashMap<String, T>();
        for (T def : defs) out.put(def.info().name(), def);
        return Map.copyOf(out);
    }

    private static ExprValue blockId(List<Expr> args, PlayerState s) {
        if (args.size() != 3) return ExprValue.NULL;
        int id = s.world.getBlockStateId(
                (int) args.get(0).eval(s).num(),
                (int) args.get(1).eval(s).num(),
                (int) args.get(2).eval(s).num());
        return id < 0 ? ExprValue.NULL : new ExprValue.Num(id);
    }

    private static ExprValue distance(List<Expr> args, PlayerState s) {
        if (args.size() != 2) return ExprValue.NULL;
        ExprValue a = args.get(0).eval(s), b = args.get(1).eval(s);
        if (!(a instanceof ExprValue.Vec3 x) || !(b instanceof ExprValue.Vec3 y)) return ExprValue.NULL;
        double dx = x.x() - y.x(), dy = x.y() - y.y(), dz = x.z() - y.z();
        return new ExprValue.Num(Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    private static ExprValue substr(List<Expr> args, PlayerState s) {
        String v = args.getFirst().eval(s).str();
        int from = Math.clamp((int) args.get(1).eval(s).num(), 0, v.length());
        int to = args.size() > 2 ? (int) args.get(2).eval(s).num() : v.length();
        to = Math.clamp(to, from, v.length());
        return new ExprValue.Str(v.substring(from, to));
    }

    private static double reduce(List<Expr> args, PlayerState s, DoubleBinaryOperator op) {
        if (args.isEmpty()) return 0;
        double acc = args.getFirst().eval(s).num();
        for (int i = 1; i < args.size(); i++) acc = op.applyAsDouble(acc, args.get(i).eval(s).num());
        return acc;
    }
}
