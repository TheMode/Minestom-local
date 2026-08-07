package net.minestom.web.internal.expression;

import net.minestom.web.PlayerState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

public sealed interface Expr {
    ExprValue eval(PlayerState s);

    record Literal(ExprValue value) implements Expr {
        @Override public ExprValue eval(PlayerState s) { return value; }
    }

    record Path(List<String> segments, Function<PlayerState, ExprValue> root) implements Expr {
        @Override public ExprValue eval(PlayerState s) {
            ExprValue cur = root.apply(s);
            for (int i = 1; i < segments.size(); i++) {
                if (cur instanceof ExprValue.Null) return ExprValue.NULL;
                cur = step(cur, segments.get(i));
            }
            return cur;
        }

        private static ExprValue step(ExprValue cur, String seg) {
            return switch (cur) {
                case ExprValue.Dict d -> d.value().getOrDefault(seg, ExprValue.NULL);
                case ExprValue.Tag t -> {
                    var child = t.value().get(seg);
                    yield child == null ? ExprValue.NULL : ExprValue.of(child);
                }
                default -> ExprValue.of(reflect(cur.toObject(), seg));
            };
        }

        private static Object reflect(Object cur, String seg) {
            if (cur == null) return null;
            try { return cur.getClass().getMethod(seg).invoke(cur); }
            catch (Exception _) {
                try { return cur.getClass().getField(seg).get(cur); }
                catch (Exception _) { return null; }
            }
        }
    }

    record Tuple(List<Expr> parts) implements Expr {
        @Override public ExprValue eval(PlayerState s) {
            double x = 0, y = 0, z = 0;
            int n = Math.min(3, parts.size());
            for (int i = 0; i < n; i++) {
                double v = parts.get(i).eval(s).num();
                if (i == 0) x = v; else if (i == 1) y = v; else z = v;
            }
            return new ExprValue.Vec3(x, y, z);
        }
    }

    record Binary(String op, Expr left, Expr right) implements Expr {
        /// Compiled-pattern cache for `matches`. The right-hand side is almost always a constant
        /// literal, so distinct patterns are few; recompiling per call would recompile on every
        /// per-player cadence tick.
        private static final Map<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

        @Override public ExprValue eval(PlayerState s) {
            ExprValue a = left.eval(s), b = right.eval(s);
            return switch (op) {
                case "="  -> bool(equals(a, b));
                case "!=" -> bool(!equals(a, b));
                case "<"  -> bool(a.num() < b.num());
                case "<=" -> bool(a.num() <= b.num());
                case ">"  -> bool(a.num() > b.num());
                case ">=" -> bool(a.num() >= b.num());
                case "matches"  -> bool(present(a, b) && PATTERNS.computeIfAbsent(b.str(), Pattern::compile).matcher(a.str()).matches());
                case "contains" -> bool(present(a, b) && a.str().contains(b.str()));
                case "~" -> bool(present(a, b) && a.str().toLowerCase(Locale.ROOT).contains(b.str().toLowerCase(Locale.ROOT)));
                case "has", "in" -> bool(membership(op.equals("has") ? a : b, op.equals("has") ? b : a));
                case "and" -> bool(a.isTruthy() && b.isTruthy());
                case "or"  -> bool(a.isTruthy() || b.isTruthy());
                case "+" -> a instanceof ExprValue.Num && b instanceof ExprValue.Num
                        ? new ExprValue.Num(a.num() + b.num()) : new ExprValue.Str(a.str() + b.str());
                case "-" -> new ExprValue.Num(a.num() - b.num());
                case "*" -> new ExprValue.Num(a.num() * b.num());
                case "/" -> new ExprValue.Num(b.num() == 0 ? 0 : a.num() / b.num());
                case "%" -> new ExprValue.Num(b.num() == 0 ? 0 : a.num() % b.num());
                default -> throw new IllegalArgumentException("Unknown operator: " + op);
            };
        }

        private static ExprValue.Bool bool(boolean v) { return new ExprValue.Bool(v); }

        private static boolean present(ExprValue a, ExprValue b) {
            return !(a instanceof ExprValue.Null) && !(b instanceof ExprValue.Null);
        }

        private static boolean equals(ExprValue a, ExprValue b) {
            if (a instanceof ExprValue.Null) return b instanceof ExprValue.Null;
            if (b instanceof ExprValue.Null) return false;
            if (a instanceof ExprValue.Num na && b instanceof ExprValue.Num nb) return na.value() == nb.value();
            return a.str().equals(b.str());
        }

        private static boolean membership(ExprValue container, ExprValue value) {
            if (!present(container, value)) return false;
            String v = value.str();
            return switch (container) {
                case ExprValue.Coll c -> c.value().stream().anyMatch(x -> x.str().equals(v));
                case ExprValue.Dict d -> d.value().containsKey(v);
                default -> {
                    Object raw = container.toObject();
                    if (raw != null && raw.getClass().isArray()) {
                        int n = java.lang.reflect.Array.getLength(raw);
                        for (int i = 0; i < n; i++) {
                            Object item = java.lang.reflect.Array.get(raw, i);
                            if (item != null && item.toString().contains(v)) yield true;
                        }
                        yield false;
                    }
                    yield container.str().contains(v);
                }
            };
        }
    }

    record Not(Expr inner) implements Expr {
        @Override public ExprValue eval(PlayerState s) { return new ExprValue.Bool(!inner.eval(s).isTruthy()); }
    }

    record Call(String name, List<Expr> args) implements Expr {
        @Override public ExprValue eval(PlayerState s) { return Builtins.apply(name, args, s); }
    }

    record Pipe(Expr value, String name) implements Expr {
        @Override public ExprValue eval(PlayerState s) { return Builtins.applyUnary(name, value.eval(s), s); }
    }
}
