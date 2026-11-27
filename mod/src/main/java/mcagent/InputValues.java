package mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Decodes and checks the value operands carried by one queue entry. */
record InputValues(String op, JsonElement target, JsonElement expr, String binding, String format,
                   List<JsonElement> args, List<Action> actions) {
    static final InputValues NONE = new InputValues("", null, null, null, null, List.of(), List.of());

    static InputValues read(JsonObject value, String event, String call, boolean returned) {
        if (value == null) return NONE;
        var op = value.has("op") ? ScriptValues.string(value.get("op")) : "";
        Set<String> fields = switch (op) {
            case "let", "set" -> call == null ? Set.of("op", "target", "expr") : Set.of("op", "target", "args");
            case "wait" -> Set.of("op", "expr");
            case "print" -> Set.of("op", "format", "args");
            case "input" -> Set.of("op", "actions");
            case "" -> event != null ? Set.of("bind") : call != null ? Set.of("args") : returned ? Set.of("expr") : Set.of();
            default -> throw new IllegalArgumentException("unknown value operation " + op);
        };
        boolean assignment = op.equals("let") || op.equals("set");
        if (!fields.containsAll(value.keySet()) ||
                !op.isEmpty() && (event != null || returned || (call != null && !assignment)))
            throw new IllegalArgumentException("unexpected value operands");

        var target = value.get("target");
        if (assignment) {
            if (target == null) throw new IllegalArgumentException("missing assignment target");
            ScriptValues.validateTarget(target);
            if (op.equals("let")) declarationName(target);
        }
        var expr = value.get("expr");
        var format = value.has("format") ? ScriptValues.string(value.get("format")) : null;
        var binding = value.has("bind") ? ScriptValues.name(value.get("bind")) : null;
        var args = value.has("args") ? elements(value.getAsJsonArray("args")) : List.<JsonElement>of();
        args.forEach(ScriptValues::constant);
        if (op.equals("print")) {
            if (format == null) throw new IllegalArgumentException("missing print format");
            ScriptValues.validateFormat(format, args.size());
        }
        if (op.equals("wait") || (call == null && assignment) || (returned && !value.isEmpty())) {
            if (expr == null) throw new IllegalArgumentException("missing expression");
            var constant = ScriptValues.constant(expr);
            if (op.equals("wait")) constant.ifPresent(ScriptValues::waitTicks);
        }
        var actions = new ArrayList<Action>();
        if (op.equals("input")) {
            var encoded = value.getAsJsonArray("actions");
            if (encoded == null || encoded.isEmpty()) throw new IllegalArgumentException("missing input actions");
            for (var action : encoded) actions.add(Action.read(action.getAsJsonArray()));
            collect(actions, expression -> ScriptValues.constant(expression).orElse(null), KeyDispatch::resolve);
        }
        return new InputValues(op, target, expr, binding, format, args, List.copyOf(actions));
    }

    boolean assignment() { return target != null; }
    boolean delay() { return op.equals("wait"); }
    boolean input() { return op.equals("input"); }

    String declarationName() {
        return declarationName(target);
    }

    private static String declarationName(JsonElement target) {
        var parts = target.getAsJsonArray();
        if (!parts.get(0).getAsString().equals("var")) throw new IllegalArgumentException("expected a variable after LET");
        return ScriptValues.name(parts.get(1));
    }

    List<JsonElement> evaluateArgs(ScriptValues.Scope scope) {
        return args.stream().map(expr -> ScriptValues.evaluate(expr, scope)).toList();
    }

    private static List<JsonElement> elements(JsonArray array) {
        var result = new ArrayList<JsonElement>(array.size());
        for (var element : array) result.add(element);
        return List.copyOf(result);
    }

    Resolved evaluateInput(ScriptValues.Scope scope, ToIntFunction<String> resolveKey) {
        return collect(actions, expr -> ScriptValues.evaluate(expr, scope), resolveKey);
    }

    private static Resolved collect(List<Action> actions, Function<JsonElement, JsonElement> evaluate,
                                    ToIntFunction<String> resolveKey) {
        var input = new Resolved();
        for (var action : actions) action.apply(evaluate, resolveKey, input);
        return input;
    }

    static final class Resolved {
        final Map<String,String> keys = new TreeMap<>();
        final Map<Integer,String> buttons = new TreeMap<>();
        final List<MouseDispatch.Input> moves = new ArrayList<>();
        private final Map<Integer,String> keyNames = new HashMap<>();

        void key(String name, String action, ToIntFunction<String> resolveKey) {
            if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("invalid keyboard key " + name);
            int code = resolveKey.applyAsInt(name);
            String key = keyNames.putIfAbsent(code, name);
            record(keys, key == null ? name : key, action, "keyboard key " + name);
        }

        void button(String name, String action) {
            record(buttons, MouseDispatch.button(name), action, "mouse button " + name);
        }

        private static <K> void record(Map<K,String> actions, K key, String action, String name) {
            String previous = actions.get(key);
            if (previous != null && previous.equals("release") != action.equals("release"))
                throw new IllegalArgumentException("conflicting actions for " + name);
            if (!"press".equals(previous)) actions.put(key, action);
        }
    }

    sealed interface Action permits Button, Move {
        void apply(Function<JsonElement, JsonElement> evaluate, ToIntFunction<String> resolveKey, Resolved input);

        static Action read(JsonArray action) {
            String opcode = ScriptValues.string(action.get(0));
            return switch (opcode) {
                case "Key", "KeyPress", "KeyRelease", "Mouse", "MousePress", "MouseRelease" -> {
                    if (action.size() != 2) throw new IllegalArgumentException(opcode + " expects one name");
                    String mode = switch (opcode) {
                        case "KeyPress", "MousePress" -> "press";
                        case "KeyRelease", "MouseRelease" -> "release";
                        default -> "temporary";
                    };
                    yield new Button(opcode.startsWith("Key"), mode, action.get(1));
                }
                case "MouseMove", "MouseMoveTo" -> {
                    if (action.size() < 3 || action.size() > 5)
                        throw new IllegalArgumentException("expected two to four mouse arguments");
                    var operands = new ArrayList<QuantityOperand>();
                    for (int i = 1; i < action.size(); ++i) {
                        var arg = action.get(i).getAsJsonArray();
                        if (arg.size() != 2) throw new IllegalArgumentException("invalid mouse quantity");
                        String unit = ScriptValues.string(arg.get(0));
                        MouseDispatch.Quantity.validateUnit(unit);
                        operands.add(new QuantityOperand(unit, arg.get(1)));
                    }
                    yield new Move(opcode.equals("MouseMoveTo"), List.copyOf(operands));
                }
                default -> throw new IllegalArgumentException("unknown input action " + opcode);
            };
        }
    }

    private record Button(boolean keyboard, String action, JsonElement expr) implements Action {
        @Override
        public void apply(Function<JsonElement, JsonElement> evaluate, ToIntFunction<String> resolveKey, Resolved input) {
            var value = evaluate.apply(expr);
            if (value == null) return;
            String name = ScriptValues.string(value);
            if (keyboard) input.key(name, action, resolveKey);
            else input.button(name, action);
        }
    }

    private record QuantityOperand(String unit, JsonElement expr) {}

    private record Move(boolean absolute, List<QuantityOperand> operands) implements Action {
        @Override
        public void apply(Function<JsonElement, JsonElement> evaluate, ToIntFunction<String> resolveKey, Resolved input) {
            var args = new ArrayList<MouseDispatch.Quantity>();
            for (var operand : operands) {
                var value = evaluate.apply(operand.expr);
                args.add(value == null ? null : new MouseDispatch.Quantity(operand.unit, ScriptValues.finiteNumber(value)));
            }
            MouseDispatch.Input.validateSpeeds(absolute, args);
            if (!args.contains(null)) input.moves.add(new MouseDispatch.Input(absolute, List.copyOf(args)));
        }
    }
}
