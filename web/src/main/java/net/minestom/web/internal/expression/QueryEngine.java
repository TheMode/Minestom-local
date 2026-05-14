package net.minestom.web.internal.expression;

import net.minestom.web.PlayerState;
import net.minestom.web.Query;

/// The boolean/host layer over [ValueParser]: parses `and`/`or`/`not` and comparison/keyword
/// operators into an [Expr] tree, then wraps it as a [Query] that evaluates against a
/// [PlayerState].
public record QueryEngine(ExpressionEngine expressions) {

    private static final Query MATCH_ALL = new Query() {
        @Override public String source() { return ""; }
        @Override public boolean matches(PlayerState state) { return true; }
    };

    public Query compile(String src) {
        if (src == null || src.isBlank()) return MATCH_ALL;
        ValueParser vp = expressions.newParser(src);
        Parser p = new Parser(vp);
        Expr ast = p.parseTop();
        return new Query() {
            @Override public String source() { return src; }
            @Override public boolean matches(PlayerState state) {
                return ast.eval(state).isTruthy();
            }
            @Override public String toString() { return "Query(" + src + ")"; }
        };
    }

    private record Parser(ValueParser vp) {
        Parser(ValueParser vp) {
            this.vp = vp;
            vp.groupParser(this::parseOr);
        }

        Expr parseTop() {
            Expr e = parseOr();
            if (vp.peek().kind() != Lexer.Kind.EOF)
                throw new IllegalArgumentException("Trailing tokens at " + vp.peek());
            return e;
        }

        private Expr parseOr() {
            Expr left = parseAnd();
            while (vp.matchIdent("or")) left = new Expr.Binary("or", left, parseAnd());
            return left;
        }

        private Expr parseAnd() {
            Expr left = parseNot();
            while (vp.matchIdent("and")) left = new Expr.Binary("and", left, parseNot());
            return left;
        }

        private Expr parseNot() {
            if (vp.matchIdent("not")) return new Expr.Not(parseNot());
            return parseCmp();
        }

        private Expr parseCmp() {
            Expr left = vp.parseExpr();
            String op = cmpOp();
            return op == null ? left : new Expr.Binary(op, left, vp.parseExpr());
        }

        private String cmpOp() {
            Lexer.Token t = vp.peek();
            return switch (t.kind()) {
                case EQ -> { vp.expect(Lexer.Kind.EQ); yield "="; }
                case NE -> { vp.expect(Lexer.Kind.NE); yield "!="; }
                case LT -> { vp.expect(Lexer.Kind.LT); yield "<"; }
                case LE -> { vp.expect(Lexer.Kind.LE); yield "<="; }
                case GT -> { vp.expect(Lexer.Kind.GT); yield ">"; }
                case GE -> { vp.expect(Lexer.Kind.GE); yield ">="; }
                case TILDE -> { vp.expect(Lexer.Kind.TILDE); yield "~"; }
                case IDENT -> {
                    if (!MqlConstants.isKeywordOperator(t.text())) yield null;
                    vp.expect(Lexer.Kind.IDENT);
                    yield t.text();
                }
                default -> null;
            };
        }
    }
}
