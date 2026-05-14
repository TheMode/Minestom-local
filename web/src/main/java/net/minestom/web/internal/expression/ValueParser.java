package net.minestom.web.internal.expression;

import net.minestom.web.PlayerState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/// Value grammar: arithmetic, paths, calls, tuples, pipes. Comparisons/logicals stay for the host parser.
public final class ValueParser {

    private final List<Lexer.Token> tokens;
    private final Function<String, Function<PlayerState, ExprValue>> roots;
    private int pos;
    private Supplier<Expr> groupParser;

    public ValueParser(List<Lexer.Token> tokens, Function<String, Function<PlayerState, ExprValue>> roots) {
        this.tokens = tokens;
        this.roots = roots;
        this.groupParser = this::parseExpr;
    }

    public void groupParser(Supplier<Expr> groupParser) { this.groupParser = groupParser; }

    public Lexer.Token peek() { return tokens.get(pos); }

    public boolean match(Lexer.Kind kind) {
        if (peek().kind() == kind) { pos++; return true; }
        return false;
    }

    public boolean matchIdent(String text) {
        Lexer.Token t = peek();
        if (t.kind() == Lexer.Kind.IDENT && t.text().equals(text)) { pos++; return true; }
        return false;
    }

    public Lexer.Token expect(Lexer.Kind kind) {
        Lexer.Token t = peek();
        if (t.kind() != kind) throw new IllegalArgumentException("Expected " + kind + " got " + t);
        pos++;
        return t;
    }

    public Expr parseExpr() { return parsePipe(); }

    private Expr parsePipe() {
        Expr left = parseAdd();
        while (match(Lexer.Kind.PIPE)) left = new Expr.Pipe(left, expect(Lexer.Kind.IDENT).text());
        return left;
    }

    private Expr parseAdd() {
        Expr left = parseMul();
        while (peek().kind() == Lexer.Kind.PLUS || peek().kind() == Lexer.Kind.MINUS) {
            String op = peek().text();
            pos++;
            left = new Expr.Binary(op, left, parseMul());
        }
        return left;
    }

    private Expr parseMul() {
        Expr left = parseUnary();
        while (peek().kind() == Lexer.Kind.STAR || peek().kind() == Lexer.Kind.SLASH || peek().kind() == Lexer.Kind.PERCENT) {
            String op = peek().text();
            pos++;
            left = new Expr.Binary(op, left, parseUnary());
        }
        return left;
    }

    private Expr parseUnary() {
        if (match(Lexer.Kind.MINUS)) return new Expr.Binary("-", new Expr.Literal(new ExprValue.Num(0)), parseUnary());
        return parsePrimary();
    }

    private Expr parsePrimary() {
        Lexer.Token t = peek();
        return switch (t.kind()) {
            case NUMBER -> { pos++; yield new Expr.Literal(new ExprValue.Num(Double.parseDouble(t.text()))); }
            case STRING -> { pos++; yield new Expr.Literal(new ExprValue.Str(t.text())); }
            case LPAREN -> parseGroup();
            case IDENT  -> parseIdent(t.text());
            default     -> throw new IllegalArgumentException("Unexpected token: " + t);
        };
    }

    private Expr parseGroup() {
        expect(Lexer.Kind.LPAREN);
        Expr first = groupParser.get();
        if (!match(Lexer.Kind.COMMA)) {
            expect(Lexer.Kind.RPAREN);
            return first;
        }
        List<Expr> parts = new ArrayList<>();
        parts.add(first);
        parts.add(groupParser.get());
        while (match(Lexer.Kind.COMMA)) parts.add(groupParser.get());
        expect(Lexer.Kind.RPAREN);
        return new Expr.Tuple(parts);
    }

    private Expr parseIdent(String name) {
        pos++;
        if (peek().kind() == Lexer.Kind.LPAREN) {
            pos++;
            List<Expr> args = new ArrayList<>();
            if (peek().kind() != Lexer.Kind.RPAREN) {
                args.add(groupParser.get());
                while (match(Lexer.Kind.COMMA)) args.add(groupParser.get());
            }
            expect(Lexer.Kind.RPAREN);
            return new Expr.Call(name, args);
        }
        if ("true".equals(name))  return new Expr.Literal(new ExprValue.Bool(true));
        if ("false".equals(name)) return new Expr.Literal(new ExprValue.Bool(false));
        List<String> segments = new ArrayList<>();
        segments.add(name);
        while (match(Lexer.Kind.DOT)) segments.add(expect(Lexer.Kind.IDENT).text());
        return new Expr.Path(segments, roots.apply(name));
    }
}
