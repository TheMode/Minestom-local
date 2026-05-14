package net.minestom.web.internal.expression;

import java.util.ArrayList;
import java.util.List;

public final class Lexer {

    public enum Kind {
        NUMBER, STRING, IDENT,
        DOT, COMMA, LPAREN, RPAREN,
        PLUS, MINUS, STAR, SLASH, PERCENT, PIPE,
        EQ, NE, LT, LE, GT, GE, TILDE,
        EOF
    }

    public record Token(Kind kind, String text) {}

    private Lexer() {}

    public static List<Token> tokenize(String src) {
        List<Token> tokens = new ArrayList<>();
        int n = src.length();
        for (int i = 0; i < n; ) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }

            if (Character.isLetter(c) || c == '_') {
                int start = i++;
                while (i < n && (Character.isLetterOrDigit(src.charAt(i)) || src.charAt(i) == '_')) i++;
                tokens.add(new Token(Kind.IDENT, src.substring(start, i)));
            } else if (Character.isDigit(c)) {
                int start = i++;
                while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '.')) i++;
                tokens.add(new Token(Kind.NUMBER, src.substring(start, i)));
            } else if (c == '"') {
                i = readString(src, i + 1, n, tokens);
            } else if ("<>=!".indexOf(c) >= 0) {
                boolean two = i + 1 < n && src.charAt(i + 1) == '=';
                Kind k = compareKind(c, two);
                tokens.add(new Token(k, two ? src.substring(i, i + 2) : String.valueOf(c)));
                i += two ? 2 : 1;
            } else {
                Kind k = singleCharKind(c, i);
                tokens.add(new Token(k, String.valueOf(c)));
                i++;
            }
        }
        tokens.add(new Token(Kind.EOF, ""));
        return tokens;
    }

    private static int readString(String src, int from, int n, List<Token> out) {
        var sb = new StringBuilder();
        int i = from;
        while (i < n && src.charAt(i) != '"') {
            if (src.charAt(i) == '\\' && i + 1 < n) { sb.append(src.charAt(i + 1)); i += 2; }
            else                                    { sb.append(src.charAt(i));     i++; }
        }
        if (i < n) i++; // closing "
        out.add(new Token(Kind.STRING, sb.toString()));
        return i;
    }

    private static Kind compareKind(char c, boolean two) {
        return switch (c) {
            case '<' -> two ? Kind.LE : Kind.LT;
            case '>' -> two ? Kind.GE : Kind.GT;
            case '=' -> Kind.EQ;
            case '!' -> Kind.NE; // tolerate bare '!' as '!='
            default  -> throw new AssertionError();
        };
    }

    private static Kind singleCharKind(char c, int i) {
        return switch (c) {
            case '(' -> Kind.LPAREN; case ')' -> Kind.RPAREN;
            case ',' -> Kind.COMMA;  case '.' -> Kind.DOT;
            case '+' -> Kind.PLUS;   case '-' -> Kind.MINUS;
            case '*' -> Kind.STAR;   case '/' -> Kind.SLASH;
            case '%' -> Kind.PERCENT;
            case '|' -> Kind.PIPE;
            case '~' -> Kind.TILDE;
            default  -> throw new IllegalArgumentException("Unexpected character '" + c + "' at " + i);
        };
    }
}
