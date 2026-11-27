package mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/** Evaluates pure input-script expressions and manages their lexical bindings. */
final class ScriptValues {
    static final int MAX_OUTPUT_CHARS = 4 * 1024 * 1024;
    private static final int MAX_DEPTH = 48;
    private static final int MAX_EXPRESSION_NODES = 4096;
    private static final int MAX_VALUE_NODES = 16384;
    private static final int MAX_TEXT = 1 << 20;
    private static final int MAX_DECIMAL = 4096;
    private static final BigInteger MAX_WAIT = BigInteger.ONE.shiftLeft(Long.SIZE).subtract(BigInteger.ONE);
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    static final class Failure extends IllegalArgumentException {
        Failure(String message) { super(message); }
    }

    private static final class Binding {
        JsonElement value;
        final boolean readOnly;

        Binding(JsonElement value, boolean readOnly) {
            this.value = value;
            this.readOnly = readOnly;
        }
    }

    static final class Scope {
        private final Map<String, Binding> inherited;
        private final Map<String, Binding> locals = new HashMap<>();

        Scope() { this(Map.of()); }

        private Scope(Map<String, Binding> inherited) { this.inherited = inherited; }

        /** Capture the visible bindings, including any later updates to their values. */
        Scope child() {
            var visible = new HashMap<>(inherited);
            visible.putAll(locals);
            return new Scope(visible);
        }

        void declare(String name, JsonElement value, boolean readOnly) {
            if (locals.containsKey(name)) throw new Failure("duplicate declaration $" + name);
            locals.put(name, new Binding(copy(value), readOnly));
        }

        private Binding binding(String name) {
            Binding binding = locals.get(name);
            if (binding == null) binding = inherited.get(name);
            if (binding == null) throw new Failure("undeclared variable $" + name);
            return binding;
        }
    }

    static final class Target {
        private final Binding binding;
        private final List<Access> path;

        private Target(Binding binding, List<Access> path) {
            this.binding = binding;
            this.path = path;
        }

        void write(JsonElement value) {
            // A suspended function may have changed the binding in the meantime.
            // Replace only the requested path on the current value and publish after validation.
            JsonElement updated = replace(binding.value, 0, value);
            binding.value = copy(updated);
        }

        private JsonElement replace(JsonElement parent, int offset, JsonElement value) {
            if (offset == path.size()) return value;
            Access step = path.get(offset);
            JsonElement child = step.lookup(parent, true);
            JsonElement updated = replace(child, offset + 1, value);
            if (step.field != null) {
                var object = new JsonObject();
                for (var entry : parent.getAsJsonObject().entrySet()) object.add(entry.getKey(), entry.getValue());
                object.add(step.field, updated);
                return object;
            }
            var array = new JsonArray(parent.getAsJsonArray().size());
            array.addAll(parent.getAsJsonArray());
            array.set(step.index, updated);
            return array;
        }
    }

    /** One evaluated key or index. */
    private record Access(String field, int index) {
        Access(String field) { this(field, -1); }

        static Access of(JsonElement key) {
            if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isString()) return new Access(string(key));
            BigInteger index;
            try {
                index = number(key).toBigIntegerExact();
            } catch (ArithmeticException | Failure error) {
                throw new Failure("expected a string field name or a non-negative integer array index");
            }
            if (index.signum() < 0) throw new Failure("expected a non-negative array index");
            if (index.bitLength() >= Integer.SIZE) throw new Failure("array index is out of bounds");
            return new Access(null, index.intValue());
        }

        JsonElement lookup(JsonElement parent, boolean writing) {
            if (field != null) {
                if (!writing && parent.isJsonNull()) return JsonNull.INSTANCE;
                if (!parent.isJsonObject()) throw new Failure("field " + field + " requires an object");
                JsonElement child = parent.getAsJsonObject().get(field);
                return child == null ? JsonNull.INSTANCE : child;
            }
            if (!parent.isJsonArray()) throw new Failure("array index requires an array");
            JsonArray array = parent.getAsJsonArray();
            if (index >= array.size()) throw new Failure("array index " + index + " is out of bounds");
            return array.get(index);
        }
    }

    static void validateTarget(JsonElement target) {
        readTarget(target, new Evaluation(null), null, 1);
    }

    static Target target(JsonElement target, Scope scope) {
        var path = new ArrayList<Access>();
        String variable = readTarget(target, new Evaluation(scope), path, 1);
        Binding binding = scope.binding(variable);
        if (binding.readOnly) throw new Failure("variable $" + variable + " is read-only");
        JsonElement value = binding.value;
        for (Access step : path) value = step.lookup(value, true);
        return new Target(binding, List.copyOf(path));
    }

    private static String readTarget(JsonElement target, Evaluation evaluation, List<Access> path, int depth) {
        var node = evaluation.node(target, depth);
        String operator = string(node.get(0));
        if (operator.equals("var")) {
            arity(node, 1);
            return name(node.get(1));
        }
        if (!operator.equals("field") && !operator.equals("index"))
            throw new Failure("SET target must be a variable or a path starting at a variable");
        arity(node, 2);
        String root = readTarget(node.get(1), evaluation, path, depth + 1);
        JsonElement index = operator.equals("field") ? new JsonPrimitive(string(node.get(2)))
            : evaluation.value(node.get(2), depth + 1);
        Access step = index == null ? null : Access.of(index);
        if (path != null) path.add(step);
        return root;
    }

    static JsonElement evaluate(JsonElement expression, Scope scope) {
        return copy(new Evaluation(scope).value(expression, 1));
    }

    /** Visit every subtree, including constants nested inside an expression with variable references. */
    static Optional<JsonElement> constant(JsonElement expression) {
        JsonElement value = new Evaluation(null).value(expression, 1);
        return value == null ? Optional.empty() : Optional.of(copy(value));
    }

    private static final class Evaluation {
        private final Scope scope;
        private int nodes;

        Evaluation(Scope scope) { this.scope = scope; }

        JsonArray node(JsonElement expression, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_EXPRESSION_NODES)
                throw new Failure("expression exceeds complexity limit");
            if (!expression.isJsonArray() || expression.getAsJsonArray().isEmpty())
                throw new Failure("expected an expression array");
            if (expression.getAsJsonArray().size() > MAX_EXPRESSION_NODES)
                throw new Failure("expression exceeds complexity limit");
            return expression.getAsJsonArray();
        }

        // A Java null during validation indicates that the result needs a variable.
        // JSON null is represented by JsonNull and remains a known value.
        JsonElement value(JsonElement expression, int depth) {
            JsonArray node = node(expression, depth);
            String operator = string(node.get(0));
            return switch (operator) {
                case "var" -> {
                    arity(node, 1);
                    String variable = name(node.get(1));
                    yield scope == null ? null : scope.binding(variable).value;
                }
                case "lit" -> {
                    arity(node, 1);
                    JsonElement literal = node.get(1);
                    if (literal.isJsonObject() || literal.isJsonArray())
                        throw new Failure("expected a scalar literal");
                    if (literal.isJsonPrimitive()) {
                        if (literal.getAsJsonPrimitive().isNumber()) number(literal);
                        if (literal.getAsJsonPrimitive().isString()) string(literal);
                    }
                    yield literal;
                }
                case "num" -> {
                    arity(node, 1);
                    yield numeric(decimal(string(node.get(1))));
                }
                case "field", "index" -> {
                    arity(node, 2);
                    JsonElement parent = value(node.get(1), depth + 1);
                    Access step;
                    if (operator.equals("field")) {
                        step = new Access(string(node.get(2)));
                    } else {
                        JsonElement key = value(node.get(2), depth + 1);
                        step = key == null ? null : Access.of(key);
                    }
                    yield step == null || parent == null ? null : step.lookup(parent, false);
                }
                case "array" -> array(node, depth + 1);
                case "object" -> object(node, depth + 1);
                case "neg", "Floor", "Ceil", "Abs" -> {
                    arity(node, 1);
                    JsonElement operand = value(node.get(1), depth + 1);
                    yield operand == null ? null : unary(operator, number(operand));
                }
                case "!", "Len" -> {
                    arity(node, 1);
                    JsonElement operand = value(node.get(1), depth + 1);
                    if (operand == null) yield null;
                    yield operator.equals("!") ? new JsonPrimitive(!bool(operand))
                        : new JsonPrimitive(length(operand));
                }
                case "+", "-", "*", "/", "Min", "Max", "<", "<=", ">", ">=" -> {
                    arity(node, 2);
                    JsonElement left = value(node.get(1), depth + 1);
                    JsonElement right = value(node.get(2), depth + 1);
                    BigDecimal a = left == null ? null : number(left);
                    BigDecimal b = right == null ? null : number(right);
                    if (operator.equals("/") && b != null && b.signum() == 0) throw new Failure("division by zero");
                    yield a == null || b == null ? null : binary(operator, a, b);
                }
                case "==", "!=" -> {
                    arity(node, 2);
                    JsonElement left = value(node.get(1), depth + 1);
                    JsonElement right = value(node.get(2), depth + 1);
                    yield left == null || right == null ? null
                        : new JsonPrimitive(equal(left, right) == operator.equals("=="));
                }
                default -> throw new Failure("unknown expression operator " + operator);
            };
        }

        private JsonElement array(JsonArray node, int depth) {
            var array = new JsonArray(node.size() - 1);
            boolean constant = true;
            for (int i = 1; i < node.size(); ++i) {
                JsonElement item = value(node.get(i), depth);
                constant &= item != null;
                array.add(item);
            }
            return constant ? array : null;
        }

        private JsonElement object(JsonArray node, int depth) {
            var object = new JsonObject();
            boolean constant = true;
            for (int i = 1; i < node.size(); ++i) {
                JsonElement entry = node.get(i);
                if (!entry.isJsonArray() || entry.getAsJsonArray().size() != 2)
                    throw new Failure("expected an object field and its expression");
                JsonArray pair = entry.getAsJsonArray();
                String key = string(pair.get(0));
                if (object.has(key)) throw new Failure("duplicate object field " + key);
                JsonElement item = value(pair.get(1), depth);
                constant &= item != null;
                object.add(key, item);
            }
            return constant ? object : null;
        }
    }

    private static void arity(JsonArray node, int operands) {
        if (node.size() != operands + 1)
            throw new Failure(node.get(0).getAsString() + " expects " + operands + " operands");
    }

    static String string(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new Failure("expected a string");
        String text = value.getAsString();
        if (text.length() > MAX_TEXT) throw new Failure("string exceeds size limit");
        return text;
    }

    static String name(JsonElement value) {
        String name = string(value);
        if (!NAME.matcher(name).matches()) throw new Failure("invalid variable or function name " + name);
        return name;
    }

    static boolean bool(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new Failure("expected a boolean");
        return value.getAsBoolean();
    }

    private static int length(JsonElement value) {
        if (value.isJsonArray()) return value.getAsJsonArray().size();
        if (value.isJsonObject()) return value.getAsJsonObject().size();
        throw new Failure("Len expects an array or object");
    }

    static List<JsonElement> iteration(JsonElement value) {
        if (value.isJsonArray()) return List.copyOf(copy(value).getAsJsonArray().asList());
        if (value.isJsonObject())
            return value.getAsJsonObject().keySet().stream()
                .sorted(ScriptValues::compareFieldNames)
                .<JsonElement>map(JsonPrimitive::new).toList();
        throw new Failure("FOR requires an array or object");
    }

    private static int compareFieldNames(String left, String right) {
        int commonLimit = Math.min(left.length(), right.length());
        for (int offset = 0; offset < commonLimit;) {
            int a = left.codePointAt(offset);
            int b = right.codePointAt(offset);
            if (a != b) return Integer.compare(a, b);
            offset += Character.charCount(a);
        }
        return Integer.compare(left.length(), right.length());
    }

    private static boolean equal(JsonElement left, JsonElement right) {
        if (left.isJsonArray() && right.isJsonArray()) {
            JsonArray a = left.getAsJsonArray();
            JsonArray b = right.getAsJsonArray();
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); ++i) {
                if (!equal(a.get(i), b.get(i))) return false;
            }
            return true;
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            JsonObject a = left.getAsJsonObject();
            JsonObject b = right.getAsJsonObject();
            if (a.size() != b.size()) return false;
            for (var entry : a.entrySet()) {
                JsonElement value = b.get(entry.getKey());
                if (value == null || !equal(entry.getValue(), value)) return false;
            }
            return true;
        }
        if (left.isJsonPrimitive() && right.isJsonPrimitive()) {
            JsonPrimitive a = left.getAsJsonPrimitive();
            JsonPrimitive b = right.getAsJsonPrimitive();
            if (a.isNumber() && b.isNumber()) return number(a).compareTo(number(b)) == 0;
            return a.equals(b);
        }
        return left.isJsonNull() && right.isJsonNull();
    }

    private static BigDecimal number(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new Failure("expected a number");
        Number number = value.getAsNumber();
        return number instanceof BigDecimal decimal ? checked(decimal) : decimal(number.toString());
    }

    private static BigDecimal decimal(String text) {
        if (text.length() > MAX_DECIMAL) throw new Failure("number exceeds size limit");
        try {
            return checked(new BigDecimal(text));
        } catch (NumberFormatException error) {
            throw new Failure("invalid number or exponent exceeds limit");
        }
    }

    private static BigDecimal checked(BigDecimal value) {
        if (value.precision() > MAX_DECIMAL || Math.abs((long) value.scale()) > MAX_DECIMAL)
            throw new Failure("number exceeds precision or scale limit");
        return value;
    }

    private static JsonPrimitive numeric(BigDecimal value) {
        value = value.stripTrailingZeros();
        if (value.scale() < 0 && value.precision() - (long) value.scale() <= MAX_DECIMAL)
            value = value.setScale(0);
        return new JsonPrimitive(checked(value));
    }

    private static JsonPrimitive unary(String operator, BigDecimal value) {
        return numeric(switch (operator) {
            case "neg" -> value.negate();
            case "Floor" -> value.setScale(0, RoundingMode.FLOOR);
            case "Ceil" -> value.setScale(0, RoundingMode.CEILING);
            case "Abs" -> value.abs();
            default -> throw new AssertionError(operator);
        });
    }

    private static JsonPrimitive binary(String operator, BigDecimal left, BigDecimal right) {
        return switch (operator) {
            case "+" -> numeric(left.add(right));
            case "-" -> numeric(left.subtract(right));
            case "*" -> numeric(left.multiply(right));
            case "/" -> numeric(divide(left, right));
            case "Min" -> numeric(left.min(right));
            case "Max" -> numeric(left.max(right));
            case "<" -> new JsonPrimitive(left.compareTo(right) < 0);
            case "<=" -> new JsonPrimitive(left.compareTo(right) <= 0);
            case ">" -> new JsonPrimitive(left.compareTo(right) > 0);
            case ">=" -> new JsonPrimitive(left.compareTo(right) >= 0);
            default -> throw new AssertionError(operator);
        };
    }

    private static BigDecimal divide(BigDecimal left, BigDecimal right) {
        try {
            return left.divide(right);
        } catch (ArithmeticException repeating) {
            BigDecimal approximation = left.divide(right, MathContext.DECIMAL128);
            if (approximation.stripTrailingZeros().scale() > 0) return approximation;
            // Preserve the non-integral result when rounding would cross an integer boundary.
            BigDecimal integerPart = left.divideToIntegralValue(right);
            int wholeDigits = integerPart.signum() == 0 ? 0 : integerPart.precision() - integerPart.scale();
            int fractionDigits = MAX_DECIMAL - wholeDigits;
            if (fractionDigits > 0) {
                approximation = left.divide(right, fractionDigits, RoundingMode.DOWN);
                if (approximation.stripTrailingZeros().scale() > 0) return approximation;
            }
            throw new Failure("division result exceeds precision limit");
        }
    }

    static long waitTicks(JsonElement value) {
        BigInteger ticks;
        try {
            ticks = number(value).toBigIntegerExact();
        } catch (ArithmeticException | Failure error) {
            throw new Failure("Wait duration must be a positive integer");
        }
        if (ticks.signum() <= 0 || ticks.compareTo(MAX_WAIT) > 0)
            throw new Failure("Wait duration must be a positive integer within unsigned 64-bit range");
        return ticks.longValue();
    }

    static double finiteNumber(JsonElement value) {
        double size = number(value).doubleValue();
        if (!Double.isFinite(size)) throw new Failure("expected a finite mouse value");
        return size;
    }

    private static JsonElement copy(JsonElement value) {
        return new ValueCopy().copy(value, 1);
    }

    private static final class ValueCopy {
        private int nodes;
        private int text;

        private void countText(int size) {
            if (size > MAX_TEXT - text) throw new Failure("value exceeds size limit");
            text += size;
        }

        JsonElement copy(JsonElement value, int depth) {
            if (depth > MAX_DEPTH || ++nodes > MAX_VALUE_NODES)
                throw new Failure("value exceeds complexity limit");
            if (value.isJsonArray()) {
                var copy = new JsonArray();
                for (JsonElement item : value.getAsJsonArray()) copy.add(copy(item, depth + 1));
                return copy;
            }
            if (value.isJsonObject()) {
                var copy = new JsonObject();
                for (var entry : value.getAsJsonObject().entrySet()) {
                    countText(entry.getKey().length());
                    copy.add(entry.getKey(), copy(entry.getValue(), depth + 1));
                }
                return copy;
            }
            if (value.isJsonPrimitive()) {
                JsonPrimitive primitive = value.getAsJsonPrimitive();
                if (primitive.isNumber()) number(primitive);
                if (primitive.isNumber() || primitive.isString()) countText(primitive.getAsString().length());
            }
            return value;
        }
    }

    static void validateFormat(String format, int arguments) {
        render(format, arguments, null, null);
    }

    static String format(String format, List<JsonElement> values) {
        var result = new StringBuilder();
        render(format, values.size(), values, result);
        return result.append('\n').toString();
    }

    private static void render(String format, int arguments, List<JsonElement> values, StringBuilder result) {
        if (format.length() > MAX_TEXT) throw new Failure("Print format exceeds size limit");
        int maximum = MAX_OUTPUT_CHARS - 1; // Reserve the final newline.
        int argument = 0;
        for (int i = 0; i < format.length(); ++i) {
            char next = format.charAt(i);
            if (next == '{' || next == '}') {
                if (i + 1 == format.length()) throw new Failure("unmatched brace in Print format");
                char after = format.charAt(++i);
                if (after != next) {
                    if (next != '{' || after != '}') throw new Failure("unmatched brace in Print format");
                    if (argument >= arguments) throw new Failure("too many placeholders in Print format");
                    if (result != null) {
                        JsonElement value = values.get(argument);
                        String text = value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                            ? value.getAsString() : value.toString();
                        if (text.length() > maximum - result.length()) throw new Failure("Print line exceeds size limit");
                        result.append(text);
                    }
                    ++argument;
                    continue;
                }
            }
            if (result != null) {
                if (result.length() == maximum) throw new Failure("Print line exceeds size limit");
                result.append(next);
            }
        }
        if (argument != arguments) throw new Failure("too few placeholders in Print format");
    }
}
