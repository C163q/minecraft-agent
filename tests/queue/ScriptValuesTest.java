package mcagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.util.List;

public final class ScriptValuesTest {
    private static JsonElement json(String source) { return JsonParser.parseString(source); }

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static void fails(Runnable action, String message) {
        try {
            action.run();
            throw new AssertionError("Expected error: " + message);
        } catch (ScriptValues.Failure error) {
            if (!error.getMessage().contains(message)) throw error;
        }
    }

    private static void numbers() {
        var scope = new ScriptValues.Scope();
        var maxWait = ScriptValues.evaluate(json("""
            ["/",["+",["num","36893488147419103228"],["num","2"]],["num","2"]]
            """), scope);
        check(maxWait.toString().equals("18446744073709551615"));
        check(ScriptValues.waitTicks(maxWait) == -1L);
        var fraction = ScriptValues.evaluate(json("[\"/\",[\"num\",\"5\"],[\"num\",\"2\"]]"), scope);
        check(fraction.toString().equals("2.5"));
        fails(() -> ScriptValues.waitTicks(fraction), "positive integer");
        var floor = ScriptValues.evaluate(json("[\"Floor\",[\"neg\",[\"num\",\".8\"]]]"), scope);
        check(floor.toString().equals("-1"));
        var nearInteger = ScriptValues.evaluate(json("""
            ["/",["+",["num","2e40"],["num","1"]],["-",["num","2e40"],["num","1"]]]
            """), scope);
        fails(() -> ScriptValues.waitTicks(nearInteger), "positive integer");
        check(ScriptValues.evaluate(json("[\"num\",\"1.5e200\"]"), scope).getAsBigDecimal().precision() >= 2);
        fails(() -> ScriptValues.evaluate(json("[\"num\",\"1e2147483647\"]"), scope), "limit");
        fails(() -> ScriptValues.constant(json("[\"+\",[\"var\",\"a\"],[\"/\",[\"num\",\"1\"],[\"num\",\"0\"]]]")), "division by zero");
        fails(() -> ScriptValues.constant(json("[\"+\",[\"lit\",true],[\"num\",\"0\"]]")), "number");
        fails(() -> ScriptValues.constant(json("[\"/\",[\"var\",\"a\"],[\"num\",\"0\"]]")), "division by zero");
        fails(() -> ScriptValues.constant(json("[\"+\",[\"var\",\"a\"],[\"lit\",\"3\"]]")), "number");
        fails(() -> ScriptValues.constant(json("[\"index\",[\"var\",\"a\"],[\"lit\",false]]")), "index");
    }

    private static void capturedScopesAndValueCopies() {
        var scope = new ScriptValues.Scope();
        scope.declare("original", json("{\"pos\":[1],\"block-id\":\"stone\"}"), false);
        var original = json("[\"var\",\"original\"]");
        scope.declare("copy", ScriptValues.evaluate(original, scope), false);
        var copy = json("[\"var\",\"copy\"]");
        var child = scope.child();
        scope.declare("later", json("1"), false);
        fails(() -> ScriptValues.evaluate(json("[\"var\",\"later\"]"), child), "undeclared");
        var path = json("[\"index\",[\"field\",[\"var\",\"copy\"],\"pos\"],[\"num\",\"0\"]]");
        ScriptValues.target(path, scope).write(json("2"));
        check(ScriptValues.evaluate(path, child).getAsInt() == 2);
        child.declare("copy", json("null"), true);
        check(ScriptValues.evaluate(copy, child).isJsonNull());
        fails(() -> ScriptValues.target(path, child), "read-only");
        check(ScriptValues.evaluate(original, scope).getAsJsonObject().getAsJsonArray("pos").get(0).getAsInt() == 1);
        check(ScriptValues.evaluate(json("[\"index\",[\"var\",\"original\"],[\"lit\",\"block-id\"]]"), scope).getAsString().equals("stone"));
        check(ScriptValues.evaluate(json("[\"field\",[\"field\",[\"var\",\"original\"],\"missing\"],\"next\"]"), scope).isJsonNull());
    }

    private static void frozenPathsAndAtomicWrites() {
        var scope = new ScriptValues.Scope();
        var items = json("[\"var\",\"items\"]");
        var i = json("[\"var\",\"i\"]");
        var indexed = json("[\"index\",[\"var\",\"items\"],[\"var\",\"i\"]]");
        scope.declare("items", json("[0,1]"), false);
        scope.declare("i", json("0"), false);
        var target = ScriptValues.target(indexed, scope);
        ScriptValues.target(i, scope).write(json("1"));
        ScriptValues.target(indexed, scope).write(json("20"));
        target.write(json("10"));
        check(ScriptValues.evaluate(items, scope).toString().equals("[10,20]"));
        ScriptValues.target(items, scope).write(json("[]"));
        fails(() -> target.write(json("30")), "out of bounds");
        check(ScriptValues.evaluate(items, scope).toString().equals("[]"));
        fails(() -> ScriptValues.evaluate(indexed, scope), "out of bounds");
    }

    private static void printing() {
        var text = ScriptValues.format("{{}} $v={} {}", List.of(json("\"a//b\""), json("{\"c\":true}")));
        check(text.equals("{} $v=a//b {\"c\":true}\n"));
        fails(() -> ScriptValues.validateFormat("oops {", 0), "brace");
        fails(() -> ScriptValues.validateFormat("{}", 0), "placeholder");
    }

    public static void main(String[] args) {
        numbers();
        capturedScopesAndValueCopies();
        frozenPathsAndAtomicWrites();
        printing();
    }
}
