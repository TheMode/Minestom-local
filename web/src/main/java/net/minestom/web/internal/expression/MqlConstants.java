package net.minestom.web.internal.expression;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MqlConstants {

    public record OperatorInfo(String name, String detail, String kind) {}

    private static final List<OperatorInfo> OPERATORS = List.of(
            op("+", "Addition (numbers) or concatenation (any non-numeric operand becomes string)", "arithmetic"),
            op("-", "Subtraction", "arithmetic"),
            op("*", "Multiplication", "arithmetic"),
            op("/", "Division (0 if divisor is 0)", "arithmetic"),
            op("%", "Modulo (0 if divisor is 0)", "arithmetic"),
            op("|", "Pipe into a unary function, for example blockKey or upper", "pipe"),
            op("=", "Equality (numbers compared by value, others by string representation)", "comparison"),
            op("!=", "Inequality", "comparison"),
            op("<", "Less than (numeric)", "comparison"),
            op("<=", "Less than or equal (numeric)", "comparison"),
            op(">", "Greater than (numeric)", "comparison"),
            op(">=", "Greater than or equal (numeric)", "comparison"),
            op("~", "Case-insensitive substring match", "comparison"),
            op("has", "Collection or map contains the right-hand value", "keyword"),
            op("in", "Left value is contained in the right-hand collection", "keyword"),
            op("contains", "Right-hand value is a substring of the left", "keyword"),
            op("matches", "Left-hand string matches the Java regex on the right", "keyword"),
            op("and", "Short-circuit conjunction", "logical"),
            op("or", "Short-circuit disjunction", "logical"),
            op("not", "Logical negation", "logical")
    );

    private static final List<String> LITERALS = List.of("true", "false");
    private static final Set<String> KEYWORD_OPERATORS = Set.copyOf(operatorNames("keyword"));

    private MqlConstants() {}

    public static Map<String, Object> payload() {
        var out = new LinkedHashMap<String, Object>();
        out.put("fields", ExpressionEngine.fieldInfo().stream()
                .map(field -> object("name", field.name(), "detail", field.detail()))
                .toList());
        out.put("functions", Builtins.functionInfo().stream()
                .map(fn -> object("name", fn.name(), "sig", fn.sig(), "detail", fn.detail(), "pipe", fn.pipe()))
                .toList());
        out.put("operators", OPERATORS.stream()
                .map(op -> object("name", op.name(), "detail", op.detail(), "kind", op.kind()))
                .toList());
        out.put("literals", LITERALS);
        return out;
    }

    public static boolean isKeywordOperator(String name) {
        return KEYWORD_OPERATORS.contains(name);
    }

    private static OperatorInfo op(String name, String detail, String kind) {
        return new OperatorInfo(name, detail, kind);
    }

    private static List<String> operatorNames(String... kinds) {
        Set<String> included = Set.of(kinds);
        return OPERATORS.stream()
                .filter(op -> included.contains(op.kind()))
                .map(OperatorInfo::name)
                .toList();
    }

    private static Map<String, Object> object(Object... kv) {
        var out = new LinkedHashMap<String, Object>(kv.length / 2);
        for (int i = 0; i < kv.length; i += 2) out.put((String) kv[i], kv[i + 1]);
        return out;
    }
}
