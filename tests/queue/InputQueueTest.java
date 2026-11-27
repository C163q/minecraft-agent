package mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Exercises the actual wire decoder and tick scheduler without launching a game. */
public final class InputQueueTest {
    private final InputQueue queue = new InputQueue();
    private boolean moving;

    private long submit(String... rows) {
        var program = new JsonObject();
        program.addProperty("queue_version", 1);
        var runs = new JsonArray();
        var keys = new JsonObject();
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            var run = new JsonObject();
            run.addProperty("line", i + 2);
            run.addProperty("source", row);
            run.add("held", new JsonArray());
            if (Set.of("pause", "clear", "wait_mouse").contains(row)) {
                run.addProperty(row, true);
                run.addProperty("ticks", "0");
            } else if (row.startsWith("Wait ")) {
                run.addProperty("ticks", row.substring(5));
            } else {
                run.addProperty("ticks", "1");
                var actions = new JsonObject();
                for (String pair : row.split(" ")) {
                    var parts = pair.split("=");
                    actions.addProperty(parts[0], parts[1]);
                    keys.addProperty(parts[0], i + 2);
                }
                run.add("key_actions", actions);
            }
            runs.add(run);
        }
        program.add("runs", runs);
        program.add("persistent", new JsonArray());
        return queue.submit(InputQueue.Block.read(program, keys.keySet()));
    }

    private String controls() { return queue.controls(() -> moving, () -> moving = false); }

    private void tick(String... wanted) {
        equal(null, controls());
        equal(Set.of(wanted), queue.input().held());
        queue.advance();
    }

    static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    void pauseAndOverlay() {
        long first = submit("w=press", "pause", "a=press", "d=temporary", "q=temporary");
        tick("w");
        equal("pause", controls());
        long newer = submit("a=release s=temporary");
        tick("w", "s");
        equal("end", controls());
        equal(2, queue.inspect().size());
        equal(first, queue.inspect().getFirst().block());
        equal("d=temporary", queue.inspect().getFirst().source());
        equal(5, queue.inspect().getFirst().line());
        equal(true, newer > first);
        tick("w", "d");
        tick("w", "q");
        equal(null, controls()); // Manual continuation after newest END stays in free play.
        tick("w");               // Release temporary key left by an older END.
    }

    void clearAndCompactWait() {
        submit("w=press", "pause", "pause", "q=press");
        tick("w");
        equal("pause", controls());
        moving = true;
        submit("clear", "Wait 18446744073709551615", "w=release");
        tick("w");
        equal(false, moving);
        var rows = queue.inspect();
        equal(2, rows.size());
        equal("18446744073709551614", rows.getFirst().remaining_ticks());
        try {
            submit("clear", "w=bogus");
            throw new AssertionError("malformed suffix accepted");
        } catch (IllegalArgumentException expected) {
            equal(rows, queue.inspect());
        }
    }

    void pausesAtEveryBoundary() {
        submit("pause", "pause");
        equal("pause", controls());
        equal("pause", controls());
        equal("end", controls());
        submit("Wait 1", "pause", "Wait 2");
        tick();
        submit("Wait 2");
        equal("pause", controls()); // First block can still stop its successor.
        tick();
        tick();
        equal("end", controls());
    }

    void olderClearReleasesNewerWait() {
        submit("Wait 1", "clear", "Wait 1");
        submit("Wait 1", "wait_mouse", "a=temporary");
        moving = true;
        tick();
        tick("a");
        equal("end", controls());
    }

    void concurrentStops() {
        submit("Wait 1", "pause", "w=temporary");
        submit("Wait 1", "pause", "pause", "q=temporary");
        tick();
        equal("pause", controls());
        equal("pause", controls());
        tick("q", "w");
        equal("end", controls());

        submit("Wait 1", "pause", "d=temporary");
        submit("Wait 1");
        tick();
        equal("pause", controls()); // The coincident foreground END has retired.
        tick("d");
        equal(null, controls());
    }

    private InputQueue.Block wire(String json, String... keys) {
        return InputQueue.Block.read(JsonParser.parseString(json).getAsJsonObject(), Set.of(keys));
    }

    void legacyTargets() {
        submit("w=press");
        tick("w");
        equal("end", controls());
        var legacy = wire("""
            {"persistent":["q"], "runs":[
               {"line":2,"ticks":"1","held":["q"]},
               {"line":3,"ticks":"1","held":["q"]}]}
            """, "q");
        queue.submit(legacy);
        tick("q");
        try {
            submit("q=release");
            throw new AssertionError("legacy suffix overwritten");
        } catch (IllegalStateException expected) {
            equal(1, queue.inspect().size());
        }
        // A target omitting W proves it has already been released.
        equal(Set.of(), queue.persistent());
        tick("q");
        equal("end", controls());
        equal(Set.of("q"), queue.persistent());

        submit("Wait 2");
        try {
            queue.submit(wire("{\"persistent\":[],\"runs\":[]}"));
            throw new AssertionError("legacy block overlaid incremental input");
        } catch (IllegalStateException expected) {
            equal(1, queue.inspect().size());
        }
    }

    void mouseInputsMerge() {
        String json = """
            {"queue_version":1,"persistent":[],"runs":[{
               "line":3,"ticks":"1","held":[],"buttons":{"left":"ACTION"},
               "moves":[{"absolute":false,"args":[{"unit":"px","value":X},{"unit":"px","value":4}]}]
            }]}
            """;
        queue.submit(wire(json.replace("ACTION", "press").replace("X", "2")));
        queue.submit(wire(json.replace("ACTION", "release").replace("X", "-3")));
        equal(null, controls());
        var mouse = queue.input().mouse();
        equal(List.of("release"), List.copyOf(mouse.buttons().values()));
        equal(List.of(2.0, -3.0), mouse.moves().stream().map(move -> move.args().getFirst().value()).toList());

        try {
            String invalid = json.replace("ACTION","temporary").replace("X","1")
                .replace("4}]", "4},{\"unit\":\"px\",\"value\":0},{\"unit\":\"px\",\"value\":2}]");
            queue.submit(wire(invalid));
            throw new AssertionError("immobile axis accepted before playback");
        } catch (IllegalArgumentException expected) {
            equal(2, queue.inspect().size());
        }
    }

    private static final String ACTION = """
        {"line":5,"ticks":"1","held":[],"key_actions":{"s":"temporary"},"source":"Key(s)"}
        """;
    private static final String PAUSE = """
        {"line":5,"ticks":"0","held":[],"pause":true,"source":"PAUSE"}
        """;

    void lastTickEvent(String body, String reason, int pending) {
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
                {"line":2,"ticks":"0","held":[],"event":"Hurt","body":[BODY]},
                {"line":7,"ticks":"1","held":[],"key_actions":{"w":"temporary"}}]}
            """.replace("BODY", body), "w", "s"));
        equal(null, controls());
        equal(Set.of("w"), queue.input().held());
        queue.observe("Hurt");
        queue.observe("Hurt");
        queue.advance();
        equal(reason, controls());
        equal(pending, queue.inspect().size());
        equal(List.of(), queue.listeners());
        if (reason.equals("pause")) equal("Hurt", queue.pauseListeners().getFirst().event());
        if (pending > 0) {
            equal(null, controls());
            equal(Set.of("s"), queue.input().held());
            queue.advance();
            equal(pending == 1 ? "end" : "pause", controls());
        }
    }

    void observedBeforeDispatchMergesInRegistrationOrder() {
        String runs = """
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"event":"HpLost","body":[
                 {"line":4,"ticks":"1","held":[],"key_actions":{"w":"release","s":"temporary"}}]},
              {"line":6,"ticks":"0","held":[],"event":"Hurt","body":[
                 {"line":8,"ticks":"1","held":[],"key_actions":{"w":"press"}}]},
              {"line":10,"ticks":"1","held":[],"key_actions":{"a":"temporary","w":"temporary"}},
              {"line":11,"ticks":"1","held":[]}]}
            """;
        queue.submit(wire(runs, "a", "w", "s"));
        queue.observe("Hurt"); // Before registration.
        equal(null, controls());
        equal(2, queue.inspect().size());
        queue.observe("Hurt");
        long hurtBlock = queue.inspect().getLast().block();
        queue.observe("HpLost");
        equal(hurtBlock, queue.inspect().getLast().block());
        equal(4, queue.inspect().size());
        tick("a", "w", "s");
        equal(Set.of("w"), queue.persistent());
        equal("end", controls());
        equal(2L, queue.listeners().stream().filter(l -> l.state().equals("ready")).count());
    }

    void lastExplicitPauseExpiresOwner() {
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"event":"Hurt","body":[]},
              {"line":5,"ticks":"1","held":[]},
              {"line":5,"ticks":"0","held":[],"pause":true}]}
            """));
        tick();
        equal("pause", controls());
        queue.observe("Hurt");
        equal(List.of(), queue.listeners());
        equal(false, queue.hasObserved());
    }

    void onlyClearingListenersPreservesOldControls() {
        submit("pause", "pause", "s=temporary");
        equal("pause", controls());
        var old = queue.inspect();
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"clear_listeners":true}]}
            """));
        equal("end", controls());
        equal(old, queue.inspect());
        equal("pause", controls());
    }

    void onlyThisDeathsHandlersCrossDimensions() {
        String program = """
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"event":"Death","body":[{"line":4,"ticks":"80","held":[]}]},
              {"line":6,"ticks":"100","held":[]}]}
            """;
        queue.submit(wire(program));
        equal(null, controls());
        long first = queue.observe("Death");
        equal(null, controls());
        long owner = queue.submit(wire(program));
        equal(false, queue.foregroundObserved(first));
        equal(null, controls());
        long second = queue.observe("Death");
        equal(true, queue.foregroundObserved(second));
        equal(true, first != second);
        queue.retainObservation(second);
        equal(List.of(), queue.listeners());
        equal(1, queue.inspect().size());
        equal(owner, queue.inspect().getFirst().listener().block());
        equal("80", queue.inspect().getFirst().remaining_ticks());
    }

    private static String control(int line, String fields) {
        return "{\"line\":" + line + ",\"held\":[],\"ticks\":\"0\"," + fields + "}";
    }

    private static String call(int line, String name) {
        return control(line, "\"call\":\"" + name + "\"");
    }

    private static String returned(int line) { return control(line, "\"return\":true"); }

    private static String listener(String event, String... body) {
        return control(9, "\"event\":\"" + event + "\",\"body\":[" + String.join(",", body) + "]");
    }

    private static String definition(String name, String... body) {
        return "\"" + name + "\":[" + String.join(",", body) + "]";
    }

    private long program(String definitions, String... main) {
        return queue.submit(wire("{\"queue_version\":1,\"persistent\":[],\"functions\":{" + definitions
            + "},\"runs\":[" + String.join(",", main) + "]}", "s"));
    }

    private static String branch(int line, String expression, String... body) {
        return "{\"line\":" + line + (expression == null ? "" : ",\"expr\":" + expression)
            + ",\"body\":[" + String.join(",", body) + "]}";
    }

    private static String choose(int line, String... branches) {
        return control(line, "\"flow\":{\"op\":\"if\",\"branches\":[" + String.join(",", branches) + "]}");
    }

    private static String loop(int line, String operator, String expression, String... body) {
        return control(line, "\"flow\":{\"op\":\"" + operator + "\""
            + (operator.equals("for") ? ",\"name\":\"item\"" : "")
            + ",\"expr\":" + expression + ",\"body\":[" + String.join(",", body) + "]}");
    }

    private static String jump(int line, String operator) {
        return control(line, "\"flow\":{\"op\":\"" + operator + "\"}");
    }

    private static String value(int line, String action, String target, String expression) {
        return control(line, "\"v\":{\"op\":\"" + action + "\",\"target\":" + target + ",\"expr\":" + expression + "}");
    }

    private static String print(int line, String expression) {
        return control(line, "\"v\":{\"op\":\"print\",\"format\":\"{}\",\"args\":[" + expression + "]}");
    }

    private static String returnValue(int line, String expression) {
        return control(line, "\"return\":true,\"v\":{\"expr\":" + expression + "}");
    }

    private String controls(List<String> output) {
        return queue.controls(() -> moving, () -> moving = false, output::add);
    }

    void branchSelectionAndLoopSnapshotSurvivePause() {
        String src = "[\"var\",\"source\"]", item = "[\"var\",\"item\"]";
        String one = "[\"num\",\"1\"]", two = "[\"num\",\"2\"]";
        String yes = "[\"lit\",true]", no = "[\"lit\",false]";
        program("", value(2, "let", src, "[\"array\"," + one + "," + two + "]"),
            loop(3, "for", src,
                choose(4, branch(4, "[\"==\"," + item + "," + one + "]", PAUSE, print(6, item),
                        value(7, "set", item, "[\"num\",\"99\"]"),
                        value(8, "set", src, "[\"array\"]"), jump(9, "continue")),
                    branch(11, no, print(12, "[\"index\"," + src + "," + one + "]")),
                    branch(13, yes, print(14, item), ACTION),
                    branch(16, null, PAUSE)),
                jump(18, "break")),
            returnValue(19, src));
        equal(List.of(2, 3, 19), queue.inspect().stream().map(InputQueue.Entry::line).toList());
        var output = new java.util.ArrayList<String>();
        equal("pause", controls(output));
        equal(List.of(6, 7, 8, 9, 18, 3, 19), queue.inspect().stream().map(InputQueue.Entry::line).toList());
        equal(null, controls(output));
        equal(List.of("1\n", "2\n"), output);
        equal(Set.of("s"), queue.input().held());
        queue.advance();
        equal("end", controls(output));
        equal(JsonParser.parseString("[]"), queue.takeReturnValue());
    }

    void lastTickListenerPauseDefersLoopExit() {
        for (String terminal : List.of("", "break", "continue", "return")) {
            String yes = "[\"lit\",true]";
            var body = new java.util.ArrayList<>(List.of(listener("Hurt", PAUSE, ACTION), ACTION,
                print(8, "[\"lit\",\"owner\"]")));
            if (terminal.equals("return")) body.add(returnValue(9, "[\"lit\",\"returned\"]"));
            else if (!terminal.isEmpty()) body.add(jump(9, terminal));
            program("", loop(2, "for", "[\"array\"," + yes + "]",
                choose(3, branch(3, yes, body.toArray(String[]::new)))), ACTION);
            tick("s");
            equal(true, queue.observe("Hurt") > 0);
            var output = new java.util.ArrayList<String>();
            equal("pause", controls(output));
            equal(List.of("owner\n"), output);
            equal(1, queue.listeners().size());
            equal(1, queue.pauseListeners().size());
            equal(true, queue.inspect().stream().anyMatch(row -> row.listener() != null));
            equal(terminal.equals("return") ? "end" : null, controls(output));
            equal(List.of("owner\n"), output);
            equal(List.of(), queue.listeners());
            equal(false, queue.inspect().stream().anyMatch(row -> row.listener() != null));
            if (terminal.equals("return")) equal("returned", queue.takeReturnValue().getAsString());
            else {
                tick("s");
                equal("end", controls());
            }
        }
    }

    void loopListenerReturnTargetsRegisteringCall() {
        String yes = "[\"lit\",true]", hit = "[\"var\",\"hit\"]";
        program(definition("helper", choose(31, branch(31, yes, returnValue(32, "[\"num\",\"7\"]"))), ACTION) + ","
                + definition("inner", ACTION, PAUSE) + ","
                + definition("watch", loop(10, "for", "[\"array\"," + yes + "]",
                    listener("Hurt", choose(15, branch(15, yes, control(16,
                        "\"call\":\"helper\",\"v\":{\"op\":\"let\",\"target\":" + hit + "}"), returnValue(17, hit)))),
                    call(18, "inner")), PAUSE),
            control(2, "\"call\":\"watch\",\"v\":{\"op\":\"let\",\"target\":" + hit + "}"),
            returnValue(3, hit));
        tick("s");
        var active = queue.activeCalls().getFirst();
        equal(List.of("watch", "inner"), active.call_stack().stream().map(InputQueue.Call::name).toList());
        equal(true, queue.observe("Hurt") > 0);
        equal("end", controls());
        equal(7, queue.takeReturnValue().getAsInt());
        equal(List.of(), queue.inspect());
        equal(List.of(), queue.listeners());
    }

    void loopListenersExpireOnNextIterationAndClear() {
        var outer = listener("HpLost", print(7, "[\"lit\",\"outer\"]"));
        var inner = listener("Hurt", ACTION, PAUSE);
        var item = "[\"var\",\"item\"]";
        program("", outer, loop(2, "for", "[\"array\",[\"num\",\"1\"],[\"num\",\"2\"]]",
            inner, print(3, item), ACTION, jump(4, "continue")), ACTION);
        var output = new java.util.ArrayList<String>();
        equal(null, controls(output));
        equal(List.of("1\n"), output);
        queue.input();
        queue.advance();
        queue.observe("Hurt");
        queue.observe("HpLost");
        equal("end", controls(output)); // Outer handler's own END stops playback.
        equal(List.of("1\n", "outer\n", "2\n"), output);
        equal(List.of("HpLost", "Hurt"), queue.listeners().stream().map(InputQueue.ListenerState::event).toList());
        equal(List.of("ready", "ready"), queue.listeners().stream().map(InputQueue.ListenerState::state).toList());
        equal(false, queue.inspect().stream().anyMatch(row -> row.listener() != null));
        queue.observe("Hurt");
        program("", control(2, "\"clear\":true"), ACTION);
        tick("s");
        equal("end", controls(output));
        equal(List.of(), queue.inspect());
        equal(List.of(), queue.listeners());
    }

    void returningSiblingCancelsCompletingHandlerFromBlockScope() {
        String truth = "[\"lit\",true]";
        program("", choose(2, branch(2, truth,
            listener("Hurt", control(7, "\"clear\":true"), ACTION),
            listener("Hurt", returned(8)), PAUSE, ACTION)));
        equal("pause", controls());
        submit("Wait 20");
        equal(null, controls());
        queue.observe("Hurt");
        equal("end", controls());
        equal(List.of(), queue.inspect());
    }

    void siblingReturnWaitsForPausedLocalHandler() {
        var result = "[\"var\",\"result\"]";
        var yes = "[\"lit\",true]";
        program("", value(2, "let", result, "[\"lit\",\"initial\"]"),
            loop(3, "for", "[\"array\"," + yes + "]",
                listener("Hurt", PAUSE, value(9, "set", result, "[\"lit\",\"resumed\"]"), print(10, result)),
                listener("Hurt", choose(13, branch(13, yes, returnValue(14, result)))),
                ACTION, print(17, "[\"lit\",\"owner\"]")),
            ACTION);
        tick("s");
        queue.observe("Hurt");
        var output = new java.util.ArrayList<String>();
        equal("pause", controls(output));
        equal(List.of("owner\n"), output);
        equal(null, queue.takeReturnValue());
        equal(2, queue.listeners().size());
        equal(List.of(9, 10, 14), queue.inspect().stream().filter(row -> row.listener() != null)
            .map(InputQueue.Entry::line).toList());
        equal("end", controls(output));
        equal(List.of("owner\n", "resumed\n"), output);
        equal("resumed", queue.takeReturnValue().getAsString());
        equal(List.of(), queue.inspect());
    }

    void detachedReturnDoesNotUnwindPausedSibling() {
        String yes = "[\"lit\",true]";
        program("", loop(2, "for", "[\"array\"," + yes + "]", listener("Death", PAUSE, ACTION),
            listener("Death", returnValue(10, "[\"lit\",\"detached\"]")), ACTION));
        equal(null, controls());
        queue.retainObservation(queue.observe("Death"));
        equal("pause", controls());
        equal("detached", queue.takeReturnValue().getAsString());
        equal(List.of(5), queue.inspect().stream().map(InputQueue.Entry::line).toList());
        tick("s");
        equal("end", controls());
    }

    void observedMutationChangesTheNextWhileConditionButKeepsSelectedBranch() {
        String condition = "[\"var\",\"keep\"]";
        String yes = "[\"lit\",true]", no = "[\"lit\",false]";
        program("", value(2, "let", condition, yes),
            listener("Hurt", value(3, "set", condition, no)),
            loop(4, "while", condition,
                choose(5, branch(5, condition, PAUSE, print(10, "[\"lit\",\"selected\"]")),
                    branch(12, null, PAUSE)),
                ACTION),
            returnValue(14, condition));
        equal("pause", controls());
        queue.observe("Hurt");
        var output = new java.util.ArrayList<String>();
        equal("end", controls(output));
        equal(List.of("selected\n"), output);
        tick("s");
        equal("end", controls(output));
        equal(List.of("selected\n"), output);
        equal(false, queue.takeReturnValue().getAsBoolean());
        equal(List.of(), queue.inspect());
    }

    void nestedLoopBodyDoesNotIncreaseFunctionDepth() {
        String yes = "[\"lit\",true]";
        var defs = new java.util.ArrayList<String>();
        for (int i = 1; i <= 32; i++) {
            String action = i == 32 ? PAUSE : call(i, "f" + (i + 1));
            defs.add(definition("f" + i, choose(41, branch(41, yes,
                loop(42, "for", "[\"array\"," + yes + "]", loop(43, "while", yes, action, jump(44, "break")),
                    print(45, "[\"lit\",\"after break\"]"))))));
        }
        program(String.join(",", defs), call(2, "f1"));
        equal("pause", controls());
        var active = queue.activeCalls().getFirst();
        equal(32, active.call_stack().size());
        equal(44, active.next_line());
        var output = new java.util.ArrayList<String>();
        equal("end", controls(output));
        equal(java.util.Collections.nCopies(32, "after break\n"), output);
    }

    void validatesEveryFlowBeforeAcceptanceAndReportsEvaluatedBranchLine() {
        submit("Wait 4");
        var before = queue.inspect();
        var no = "[\"lit\",false]";
        var yes = "[\"lit\",true]";
        for (String body : List.of(
                choose(2, branch(2, yes), branch(8, "[\"num\",\"1\"]")),
                choose(2, branch(2, no, loop(8, "for", "[\"lit\",true]"))),
                choose(2, branch(2, no, jump(8, "break"))),
                loop(2, "while", no, listener("Hurt", choose(3, branch(3, yes, jump(8, "continue"))))),
                listener("Hurt", choose(3, branch(3, yes, control(8, "\"event\":\"Death\",\"body\":[]")))))) {
            try {
                queue.submit(wire("{\"queue_version\":1,\"persistent\":[],\"runs\":[],\"checks\":[[" + body + "]]}"));
                throw new AssertionError("accepted an invalid skipped branch " + body);
            } catch (IllegalArgumentException expected) {
                equal(true, expected.getMessage().contains("line 8"));
                equal(before, queue.inspect());
            }
        }

        String branch = control(4, "\"file\":\"lib.input\",\"flow\":{\"op\":\"if\",\"branches\":["
            + branch(4, no) + "," + branch(8, "[\"var\",\"flag\"]") + "]}");
        long bad = program(definition("flag", value(3, "let", "[\"var\",\"flag\"]", "[\"num\",\"0\"]"), branch),
            print(2, "[\"lit\",\"before\"]"), call(12, "flag"));
        var output = new java.util.ArrayList<String>();
        try {
            controls(output);
            throw new AssertionError("accepted non-boolean ELIF");
        } catch (InputQueue.ValueFailure expected) {
            equal(bad, expected.block());
            equal("lib.input", expected.file());
            equal(8, expected.line());
            equal(List.of("flag"), expected.call_stack().stream().map(InputQueue.Call::name).toList());
            equal(List.of("before\n"), output);
            equal(before, queue.inspect());
        }
    }

    void iterationsShareTheSubmissionBudgetAcrossPausesAndHandlers() {
        String calls = String.join(",", java.util.Collections.nCopies(4095, call(4, "empty")));
        String no = "[\"lit\",false]", yes = "[\"lit\",true]";
        String one = "[\"array\"," + yes + "]";
        program(definition("empty"), calls, loop(8, "while", no, PAUSE),
            loop(9, "for", "[\"array\"]", PAUSE), loop(10, "for", one, jump(11, "continue")), ACTION,
            calls, loop(15, "for", one, jump(16, "break")));
        tick("s");
        equal("end", controls());

        program(definition("empty"), listener("Hurt", loop(19, "while", yes, jump(20, "continue"))), calls,
            loop(11, "for", one, PAUSE));
        equal("pause", controls());
        queue.observe("Hurt");
        try {
            controls();
            throw new AssertionError("handler entered iteration 4097 after same-tick pause");
        } catch (InputQueue.ValueFailure expected) {
            equal(19, expected.line());
            equal(true, expected.getMessage().contains("4096"));
            equal(List.of(), queue.inspect());
            equal(List.of(), queue.listeners());
        }

        program("", loop(21, "while", yes, jump(22, "continue")));
        try {
            controls();
            throw new AssertionError("zero-tick WHILE continued past its entry limit");
        } catch (InputQueue.ValueFailure expected) {
            equal(21, expected.line());
            equal(true, expected.getMessage().contains("4096"));
        }
    }

    void callerContinuesAtReturn() {
        program(definition("inner", PAUSE, returned(8), ACTION) + "," + definition("outer", call(7, "inner")),
            call(2, "outer"), ACTION);
        equal("pause", controls());
        var pending = queue.inspect();
        equal(List.of(8, 5, 5), pending.stream().map(InputQueue.Entry::line).toList());
        tick("s");
        equal("end", controls());
    }

    void observedOuterCancelsInnerBeforeDispatch() {
        program(definition("outer", listener("Hurt", returned(7)), call(8, "inner"))
            + "," + definition("inner", listener("Hurt", PAUSE, ACTION), ACTION), call(2, "outer"), ACTION);
        equal(null, controls());
        queue.observe("Hurt");
        tick("s");
        equal(List.of(), queue.listeners());
        equal("end", controls());
    }

    void observedPausePrecedesNaturalReturn() {
        String snap = control(13, "\"screenshot\":\"/tmp/function-handler.png\"");
        program(definition("last", listener("Hurt", call(11, "helper")), ACTION) + ","
            + definition("helper", snap, PAUSE, ACTION), call(2, "last"), PAUSE);
        tick("s");
        queue.observe("Hurt");
        equal("pause", controls());
        equal(1, queue.pauseListeners().size());
        equal(1, queue.takeScreenshots().size());
        equal(List.of(5, 5), queue.inspect().stream().map(InputQueue.Entry::line).toList());
        equal("pause", controls()); // The caller resumes, cancelling the helper's pending input.
        equal(List.of(), queue.inspect());
    }

    void detachedReturnStopsOnlyOriginalRequest() {
        String definitions = definition("watch", listener("Death", call(12, "helper"), returned(13)), ACTION)
            + "," + definition("helper", PAUSE, returned(16));
        program(definitions, call(2, "watch"), ACTION);
        equal(null, controls());
        long death = queue.observe("Death");
        queue.retainObservation(death);
        equal("pause", controls());
        equal("end", controls());
        program(definitions, call(2, "watch"), ACTION);
        equal(null, controls());
        queue.retainObservation(queue.observe("Death"));
        equal("pause", controls());
        submit("Wait 2");
        tick();
        tick();
        equal("end", controls());
    }

    void validatesUnusedFunctionsAtomically() {
        submit("Wait 4");
        var old = queue.inspect();
        try {
            program(definition("bad", listener("Hurt", call(27, "unknown"))), returned(3));
            throw new AssertionError("undefined function in unused handler accepted");
        } catch (IllegalArgumentException expected) {
            equal(true, expected.getMessage().contains("27"));
            equal(old, queue.inspect());
        }
    }

    void laterHandlerReturnKeepsEarlierPauseAndScreenshot() {
        String snap = control(8, "\"screenshot\":\"/tmp/first-handler.png\"");
        program(definition("watch", listener("Hurt", snap, PAUSE, ACTION), listener("Hurt", returned(12)), ACTION),
            call(2, "watch"), ACTION);
        equal(null, controls());
        queue.observe("Hurt");
        equal("pause", controls());
        equal(List.of("Hurt"), queue.pauseListeners().stream().map(InputQueue.Origin::event).toList());
        equal(List.of(java.nio.file.Path.of("/tmp/first-handler.png")), queue.takeScreenshots());
        equal(1, queue.inspect().size());
        equal(null, queue.inspect().getFirst().call_stack());
        tick("s");
        equal("end", controls());
    }

    void laterHandlerClearCancelsEarlierPause() {
        program("", listener("Hurt", PAUSE), listener("Hurt", control(8, "\"clear\":true"), ACTION), ACTION);
        equal(null, controls());
        queue.observe("Hurt");
        tick("s");
        equal(List.of(), queue.pauseListeners());
        equal("end", controls());
    }

    void pauseKeepsExplicitReturn() {
        program("", listener("Hurt", PAUSE, ACTION), ACTION, returned(10), ACTION);
        tick("s");
        queue.observe("Hurt");
        equal("pause", controls());
        equal(List.of(10, 5, 5), queue.inspect().stream().map(InputQueue.Entry::line).toList());
        submit("Wait 2");
        tick();
        tick();
        equal("end", controls());
    }

    void inspectDistinguishesCallsToSameFunction() {
        program(definition("same", listener("Death", PAUSE), PAUSE, ACTION), call(7, "same"), call(8, "same"));
        equal("pause", controls());
        var first = queue.inspect().getFirst().call_stack().getFirst();
        equal("same", first.name());
        equal(7, first.line());
        equal(List.of(first), queue.listeners().getFirst().call_stack());
        equal(null, queue.inspect().getLast().call_stack());
        tick("s");
        equal("pause", controls());
        var second = queue.inspect().getFirst().call_stack().getFirst();
        equal(8, second.line());
        equal(true, first.id() != second.id());
        queue.observe("Death");
        equal("pause", controls());
        equal(List.of(second), queue.pauseListeners().getFirst().call_stack());
    }

    void malformedFunctionControls() {
        for (String bad : List.of(
                "{\"line\":19,\"held\":[],\"ticks\":\"1\",\"return\":false}",
                control(19, "\"return\":\"true\""),
                control(19, "\"call\":true"))) {
            try {
                program(definition("true"), bad);
                throw new AssertionError("malformed function control accepted: " + bad);
            } catch (IllegalArgumentException expected) {
                equal(true, expected.getMessage().contains("19"));
            }
        }
    }

    void budgetSharedByHandlersAndResetAfterDeathTick() {
        String calls = String.join(",", java.util.Collections.nCopies(4096, call(4, "empty")));
        String onDeath = listener("Death", call(20, "empty"), PAUSE);
        program(definition("empty"), onDeath, calls, ACTION);
        equal(null, controls());
        queue.observe("Death");
        try {
            controls();
            throw new AssertionError("handler received an independent zero-tick budget");
        } catch (InputQueue.FunctionLimit expected) {
            equal(true, expected.getMessage().contains("line 20"));
        }
        program(definition("empty"), onDeath, calls, ACTION);
        equal(null, controls());
        equal(Set.of("s"), queue.input().held());
        queue.retainObservation(queue.observe("Death"));
        queue.advance(); // The dispatched tick finishes after the old world has been removed.
        equal("pause", controls());
    }

    void handlerInheritsDepth() {
        var defs = new java.util.ArrayList<String>();
        for (int i = 1; i < 32; i++) defs.add(definition("f" + i, call(i, "f" + (i + 1))));
        defs.add(definition("f32", listener("Hurt", call(44, "extra")), ACTION));
        defs.add(definition("extra"));
        program(String.join(",", defs), call(2, "f1"));
        equal(null, controls());
        queue.observe("Hurt");
        try {
            controls();
            throw new AssertionError("handler discarded its owner's depth");
        } catch (InputQueue.FunctionLimit expected) {
            for (var part : List.of("$f1", "$f32", "$extra", "line 44")) equal(true, expected.getMessage().contains(part));
        }
        equal(List.of(), queue.inspect());
        equal(List.of(), queue.listeners());
    }

    void olderHandlerClearStopsDiscardedForeground() {
        program("", listener("HpLost", control(3, "\"clear\":true"), returned(4)), PAUSE, ACTION);
        equal("pause", controls());
        submit("Wait 20");
        equal(null, controls());
        queue.observe("HpLost");
        equal("end", controls());
        equal(List.of(), queue.inspect());
        equal(null, controls());
    }

    void olderHandlerClearStopsDiscardedDetachedForeground() {
        program("", PAUSE, listener("Death", control(7, "\"clear\":true"), returned(8)), ACTION);
        equal("pause", controls());
        program("", listener("Death", ACTION), ACTION);
        equal(null, controls()); // The new request registers before the older request resumes.
        queue.retainObservation(queue.observe("Death"));
        equal("end", controls());
        equal(List.of(), queue.inspect());
    }

    void olderClearFinishesAfterItsHandler(boolean explicit) {
        String clear = control(7, "\"clear\":true");
        String onHurt = explicit ? listener("Hurt", clear, ACTION, returned(10)) : listener("Hurt", clear, ACTION);
        program("", onHurt, PAUSE, ACTION);
        equal("pause", controls());
        submit("Wait 20");
        equal(null, controls());
        queue.observe("Hurt");
        tick("s");
        equal("end", controls());
        equal(List.of(), queue.inspect());
    }

    void anotherClearTakesOverCompletion() {
        String clear = control(7, "\"clear\":true");
        program("", listener("Hurt", clear, ACTION), listener("Hurt", clear, ACTION, returned(8)), PAUSE, ACTION);
        equal("pause", controls());
        submit("Wait 20");
        equal(null, controls());
        queue.observe("Hurt");
        tick("s");
        equal("end", controls());
        equal(null, controls());
    }

    void returningSiblingCancelsCompletingHandler() {
        program("", listener("Hurt", control(7, "\"clear\":true"), ACTION), listener("Hurt", returned(8)), PAUSE, ACTION);
        equal("pause", controls());
        submit("Wait 20");
        equal(null, controls());
        queue.observe("Hurt");
        equal("end", controls());
        equal(List.of(), queue.inspect());
    }

    void newerSubmissionReplacesCompletion() {
        program("", listener("Hurt", control(7, "\"clear\":true"), PAUSE, returned(8)), PAUSE, ACTION);
        equal("pause", controls());
        submit("Wait 20");
        equal(null, controls());
        queue.observe("Hurt");
        equal("pause", controls());
        submit("Wait 2");
        tick();
        tick();
        equal("end", controls());
    }

    void inspectExhaustedPausedCall() {
        equal(null, queue.activeCalls());
        long block = program(definition("f", PAUSE), call(2, "f"));
        equal(null, queue.activeCalls());
        equal("pause", controls());
        equal(List.of(), queue.inspect());
        var active = queue.activeCalls().getFirst();
        equal(block, active.block());
        equal("f", active.call_stack().getFirst().name());
        equal(5, active.last_line());
        equal("END", active.next());
        equal(null, active.next_line());
        equal("end", controls());
        equal(null, queue.activeCalls());
    }

    void depthAndBudget() {
        var defs = new java.util.ArrayList<String>();
        for (int i = 1; i < 32; i++) defs.add(definition("f" + i, call(i, "f" + (i + 1))));
        defs.add(definition("f32", PAUSE));
        program(String.join(",", defs), call(1, "f1"));
        equal("pause", controls());
        equal("end", controls());

        submit("Wait 3");
        var old = queue.inspect();
        long bad = program(definition("A", call(30, "B")) + "," + definition("B", call(31, "A")), call(3, "A"));
        try {
            controls();
            throw new AssertionError("entered recursive level 33");
        } catch (InputQueue.FunctionLimit expected) {
            String message = expected.getMessage();
            for (var part : List.of("block " + bad, "$A", "$B", "line 31", "32"))
                equal(true, message.contains(part));
            equal(old, queue.inspect());
        }

        String calls = String.join(",", java.util.Collections.nCopies(4096, call(4, "empty")));
        program(definition("empty"), calls, ACTION, calls);
        tick("s");
        equal("end", controls());
        program(definition("empty"), calls, PAUSE, call(19, "empty"));
        equal("pause", controls());
        try {
            controls();
            throw new AssertionError("pause reset function budget");
        } catch (InputQueue.FunctionLimit expected) {
            equal(true, expected.getMessage().contains("4096"));
            equal(true, expected.getMessage().contains("line 19"));
        }
    }

    void recordsOneNotificationInObservationOrder() {
        String second = control(17, "\"event\":\"BlockInteracted\",\"body\":[" + ACTION + "]");
        long block = program(definition("watch", second, listener("Hurt", ACTION), ACTION),
            listener("BlockInteracted", ACTION), call(4, "watch"));
        var journal = new EventJournal();
        var fields = JsonParser.parseString("{\"item\":null,\"before\":{\"open\":false}}").getAsJsonObject();
        equal(0L, queue.observe("BlockInteracted", fields, journal));
        equal(null, controls());
        queue.observe("Hurt", null, journal);
        equal(true, queue.observe("BlockInteracted", fields, journal) > 0);
        equal(0L, queue.observe("BlockInteracted", fields, journal));
        fields.getAsJsonObject("before").addProperty("open", true);
        tick("s");
        equal("end", controls());

        var frame = new Playback.Observation(1, "paused", "end", List.of(), journal.seal(), null, null);
        var reply = JsonParser.parseString(GameBridge.playReply(frame)).getAsJsonObject();
        var events = reply.getAsJsonArray("events");
        equal(2, events.size());
        equal("Hurt", events.get(0).getAsJsonObject().get("event").getAsString());
        var interaction = events.get(1).getAsJsonObject();
        equal("BlockInteracted", interaction.get("event").getAsString());
        equal(true, interaction.get("item").isJsonNull());
        equal(false, interaction.getAsJsonObject("before").get("open").getAsBoolean());
        var listeners = interaction.getAsJsonArray("listeners");
        equal(JsonParser.parseString("""
            [{"event":"BlockInteracted","block":%d,"line":9},
             {"event":"BlockInteracted","block":%d,"line":17,"call_stack":[{"id":1,"name":"watch","line":4}]}]
            """.formatted(block, block)), listeners);
        equal(false, reply.has("pause_listeners"));
        equal(false, reply.has("events_omitted"));
    }

    void sealsAtTheStopFrameWhileRetainingListeners() {
        long owner = program("", listener("ScreenOpened", PAUSE, ACTION), listener("Hurt", ACTION),
            PAUSE, ACTION, ACTION);
        equal("pause", controls());
        var firstWindow = new EventJournal();
        queue.observe("ScreenOpened", null, firstWindow);
        equal("pause", controls());
        queue.observe("Hurt", null, firstWindow); // Confirmed between the pause boundary and its frame.
        var stopped = firstWindow.seal();
        equal(List.of("ScreenOpened", "Hurt"), stopped.entries().stream().map(EventJournal.Entry::event).toList());
        equal(owner, stopped.entries().getFirst().listeners().getFirst().block());
        tick("s");
        equal("end", controls());

        // A delivery after the cutoff still dispatches its listener.
        equal(true, queue.observe("ScreenOpened", null, firstWindow) > 0);
        equal("pause", controls());
        equal(2, firstWindow.seal().entries().size());

        submit("Wait 4");
        var frame = new Playback.Observation(4, "paused", "end", List.of(), new EventJournal().seal(), null, null);
        var reply = JsonParser.parseString(GameBridge.playReply(frame)).getAsJsonObject();
        equal(new JsonArray(), reply.getAsJsonArray("events"));
    }

    void reportsListenersFromAnOlderSubmissionInTheNewWindow() {
        long older = program("", listener("DamageBlocked", PAUSE, ACTION), PAUSE, ACTION);
        equal("pause", controls());
        submit("Wait 3");
        var journal = new EventJournal();
        queue.observe("DamageBlocked", null, journal);
        equal("pause", controls());
        equal(older, journal.seal().entries().getFirst().listeners().getFirst().block());
    }

    void keepsAcceptedNotificationAfterHandlerReturns() {
        long block = program(definition("watch", listener("LeftGround", returned(10)), ACTION),
            call(2, "watch"));
        var journal = new EventJournal();
        equal(null, controls());
        queue.observe("LeftGround", null, journal);
        equal("end", controls());
        equal(List.of(), queue.pauseListeners());
        equal(block, journal.seal().entries().getFirst().listeners().getFirst().block());
    }

    void evictsOnlyOldEntriesWhenTheJournalIsFull() {
        program("", listener("BlockBroken", ACTION), "{\"line\":12,\"ticks\":\"36\",\"held\":[]}");
        var journal = new EventJournal();
        for (int i = 0; i < 36; i++) {
            equal(null, controls());
            var fields = new JsonObject();
            fields.addProperty("x", i);
            equal(true, queue.observe("BlockBroken", fields, journal) > 0);
            tick("s");
            equal("end", controls());
        }
        var frame = new Playback.Observation(36, "paused", "end", List.of(), journal.seal(), null, null);
        var reply = JsonParser.parseString(GameBridge.playReply(frame)).getAsJsonObject();
        equal(4, reply.get("events_omitted").getAsInt());
        var events = reply.getAsJsonArray("events");
        equal(32, events.size());
        for (int i = 0; i < 32; i++) equal(i + 4, events.get(i).getAsJsonObject().get("x").getAsInt());
    }

    void worldTransitionWaitsForReadyDestination() {
        var wait = new WorldTransition(7, "leave", new CompletableFuture<>(), 0);
        equal(null, wait.frameStatus(1, true, true));
        wait.signal(7, "completed", 2);
        equal(null, wait.frameStatus(3, false, true));
        equal("completed", wait.frameStatus(4, true, true));
        equal(false, wait.signal(7, "failed", TimeUnit.SECONDS.toNanos(30)));
        equal("in_progress", wait.frameStatus(TimeUnit.SECONDS.toNanos(30), true, true));
    }

    void worldTransitionAcceptsOnlyLiveOwnerSignals() {
        var result = new CompletableFuture<Playback.Observation>();
        var wait = new WorldTransition(7, "enter", result, 0);
        wait.signal(6, "failed", 1);
        equal(null, wait.frameStatus(2, false, true));
        wait.signal(7, "needs_input", 3);
        equal(null, wait.frameStatus(4, false, false));
        equal("needs_input", wait.frameStatus(4, false, true));
        wait.signal(7, null, 5);
        equal(null, wait.frameStatus(6, false, true));
        result.cancel(false);
        equal(false, wait.accepts(7, 8));
    }

    void worldTransitionBoundsMissingReadbackForItsOwnPeer() {
        var result = new CompletableFuture<Playback.Observation>();
        var wait = new WorldTransition(7, "enter", result, 0);
        wait.expire(result, TimeUnit.SECONDS.toNanos(30));
        equal(false, result.isDone());
        wait.expire(new CompletableFuture<>(), TimeUnit.SECONDS.toNanos(35));
        equal(false, result.isDone());
        wait.expire(result, TimeUnit.SECONDS.toNanos(35));
        equal(true, result.isCompletedExceptionally());
    }

    void worldTransitionReleasesPauseWhenCompletedFrameCannotBeSent() {
        var result = new CompletableFuture<Playback.Observation>();
        var wait = new WorldTransition(7, "enter", result, 0);
        result.complete(new Playback.Observation(1, "paused", "world_changed", List.of(),
            new EventJournal().seal(), null, null, new Playback.Transition("enter", "completed", true), null, null));
        equal(false, wait.deliveryConfirmed());
        equal(false, wait.abandoned());

        wait.finishDelivery(new CompletableFuture<>(), false);
        equal(false, wait.abandoned());
        wait.finishDelivery(result, false);
        equal(true, wait.abandoned());
        equal(false, result.isCompletedExceptionally());
        wait.finishDelivery(result, true);
        equal(false, wait.deliveryConfirmed());
    }

    void worldTransitionRetainsPauseAfterSuccessfulDelivery() {
        var result = new CompletableFuture<Playback.Observation>();
        var wait = new WorldTransition(7, "enter", result, 0);
        wait.finishDelivery(result, true);
        equal(false, wait.deliveryConfirmed());

        result.complete(new Playback.Observation(1, "paused", "world_changed", List.of(),
            new EventJournal().seal(), null, null, new Playback.Transition("enter", "completed", true), null, null));
        wait.finishDelivery(result, true);
        wait.finishDelivery(result, false);
        equal(true, wait.deliveryConfirmed());
        equal(false, wait.abandoned());
    }

    void pausedAssignmentKeepsTheOriginalIndexAndUpdatesTheCurrentArray() {
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"params":{"f":["arg"]},"functions":{"f":[
              {"line":20,"ticks":"0","held":[],"pause":true},
              {"line":21,"ticks":"0","held":[],"return":true,"v":{"expr":["var","arg"]}}
            ]},"runs":[
              {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","items"],"expr":["array",["num","0"],["num","0"]]}},
              {"line":3,"ticks":"0","held":[],"v":{"op":"let","target":["var","i"],"expr":["num","0"]}},
              {"line":4,"ticks":"0","held":[],"event":"Hurt","v":{"bind":"hit"},"body":[
                {"line":5,"ticks":"0","held":[],"v":{"op":"set","target":["var","i"],"expr":["num","1"]}},
                {"line":6,"ticks":"0","held":[],"v":{"op":"set","target":["index",["var","items"],["var","i"]],"expr":["field",["var","hit"],"amount"]}}
              ]},
              {"line":8,"ticks":"0","held":[],"call":"f","v":{"op":"set","target":["index",["var","items"],["var","i"]],"args":[["num","7"]]}},
              {"line":9,"ticks":"0","held":[],"return":true,"v":{"expr":["var","items"]}}
            ]}
            """));
        equal("pause", controls());
        var fields = new JsonObject();
        fields.addProperty("amount", 9);
        queue.observe("Hurt", fields, null);
        fields.addProperty("amount", 19);
        equal("end", controls());
        equal(JsonParser.parseString("[7,9]"), queue.takeReturnValue());
    }

    void validatesConstantFailuresInImportedEntriesBeforeAcceptance() {
        submit("Wait 2");
        var previous = queue.inspect();
        for (var row : List.of(
            """
            {"line":8,"file":"lib.input","ticks":"1","held":[],"v":{"op":"wait","expr":["/",["num","5"],["num","2"]]}}
            """,
            """
            {"line":8,"file":"lib.input","ticks":"1","held":[],"v":{"op":"input","actions":[["Key",["lit","unknown_key"]]]}}
            """,
            """
            {"line":8,"file":"lib.input","ticks":"0","held":[],"v":{"op":"let","target":["var","x"],"expr":["+",["var","y"],["/",["num","1"],["num","0"]]]}}
            """)) {
            try {
                queue.submit(wire("""
                    {"queue_version":1,"persistent":[],"runs":[],"checks":[[%s]]}
                    """.formatted(row)));
                throw new AssertionError("accepted invalid imported entry " + row);
            } catch (IllegalArgumentException expected) {
                equal(true, expected.getMessage().contains("lib.input"));
                equal(previous, queue.inspect());
            }
        }
    }

    void failedInputTickLeavesOtherInputsAndPersistentKeysUntouched() {
        submit("a=press", "pause", "w=press");
        tick("a");
        equal("pause", controls());
        var older = queue.inspect();
        long failed = queue.submit(wire("""
            {"queue_version":1,"persistent":[],"functions":{"f":[
              {"line":8,"file":"lib.input","ticks":"1","held":[],"v":{"op":"input","actions":[["KeyRelease",["lit","a"]],["KeyPress",["var","name"]],["KeyRelease",["lit","w"]]]}}
            ]},"params":{"f":["name"]},"runs":[
              {"line":2,"ticks":"0","held":[],"call":"f","v":{"args":[["lit","w"]]}}
            ]}
            """));
        equal(null, controls());
        try {
            queue.input();
            throw new AssertionError("accepted conflicting dynamic inputs");
        } catch (InputQueue.ValueFailure expected) {
            equal(failed, expected.block());
            equal("lib.input", expected.file());
            equal(8, expected.line());
            equal("f", expected.call_stack().getFirst().name());
            equal(true, expected.getMessage().contains("conflicting"));
        }
        equal(Set.of("a"), queue.persistent());
        equal(older, queue.inspect());
        tick("w", "a");
    }

    void invalidDynamicMouseContextLeavesCandidateAndBufferedInputUntouched() {
        submit("a=press", "pause", "w=press");
        tick("a");
        equal("pause", controls());
        var older = queue.inspect();
        long failed = queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","x"],"expr":["num","100"]}},
              {"line":3,"ticks":"0","held":[],"event":"Hurt","body":[
                {"line":4,"ticks":"1","held":[],"v":{"op":"input","actions":[["KeyRelease",["lit","a"]],["MouseMoveTo",["px",["var","x"]],["px",["num","0"]]]]}}
              ]},
              {"line":5,"ticks":"2","held":[]}
            ]}
            """));
        equal(null, controls());
        equal(true, queue.observe("Hurt") > failed);
        equal(null, controls());
        var mouse = new MouseDispatch(new HeldToggleBindings());
        var view = new MouseDispatch.Geometry(800, 480, 800, 480, 0, 0, true, false, 1, false, false);
        try {
            queue.input(KeyDispatch::resolve, rows -> mouse.prepare(view, true, rows, 50));
            throw new AssertionError("accepted absolute movement with a grabbed cursor");
        } catch (InputQueue.ValueFailure expected) {
            equal(failed, expected.block());
            equal(4, expected.line());
            equal(true, expected.getMessage().contains("free cursor"));
        }
        equal(Set.of("a"), queue.persistent());
        equal(older, queue.inspect());
    }

    void dynamicMousePreflightsConvertedAndCombinedMovement() {
        var geometry = new MouseDispatch.Geometry(800, 480, 800, 480, 12, 0, false, false, .01, false, false);
        var mouse = new MouseDispatch(new HeldToggleBindings());
        var absoluteWithZeroSpeed = """
            ["MouseMoveTo",["px",["var","x"]],["px",["num","0"]],["px",["num","0"]],["px",["num","0"]]]
            """;
        var rotate = """
            ["MouseMove",["rotate",["var","x"]],["px",["num","0"]]]
            """;
        var hugeStep = """
            ["MouseMove",["px",["var","x"]],["px",["num","0"]],["px",["var","x"]]]
            """;
        for (var scenario : List.of(
            List.of("100", absoluteWithZeroSpeed, "moving axis"),
            List.of("1e308", rotate, "invalid straight"),
            List.of("1e308", hugeStep + "," + hugeStep, "combined movement"))) {
            long block = queue.submit(wire("""
                {"queue_version":1,"persistent":[],"runs":[
                  {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","x"],"expr":["num","%s"]}},
                  {"line":3,"ticks":"1","held":[],"v":{"op":"input","actions":[%s]}}
                ]}
                """.formatted(scenario.get(0), scenario.get(1))));
            equal(null, controls());
            try {
                queue.input(KeyDispatch::resolve, rows -> mouse.prepare(geometry, true, rows, 50));
                throw new AssertionError("expected mouse preflight to fail with " + scenario.get(2));
            } catch (InputQueue.ValueFailure expected) {
                equal(block, expected.block());
                equal(3, expected.line());
                equal(true, expected.getMessage().contains(scenario.get(2)));
            }
            equal(List.of(), mouse.inspectMoves());
        }
    }

    void waitUsesTheValueAtTheFirstConsumedTick() {
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","n"],"expr":["num","2"]}},
              {"line":3,"ticks":"0","held":[],"event":"Hurt","body":[
                {"line":4,"ticks":"0","held":[],"pause":true},
                {"line":5,"ticks":"0","held":[],"v":{"op":"set","target":["var","n"],"expr":["num","4"]}},
                {"line":6,"ticks":"1","held":[]}
              ]},
              {"line":7,"ticks":"0","held":[],"event":"HpLost","body":[
                {"line":8,"ticks":"0","held":[],"v":{"op":"set","target":["var","n"],"expr":["num","1"]}},
                {"line":9,"ticks":"1","held":[]}
              ]},
              {"line":10,"ticks":"1","held":[],"v":{"op":"wait","expr":["var","n"]}},
              {"line":11,"ticks":"1","held":[],"key_actions":{"w":"temporary"}}
            ]}
            """, "w"));
        equal(null, controls());
        queue.observe("Hurt");
        equal("pause", controls());
        equal(null, queue.inspect().getFirst().remaining_ticks());
        tick();
        equal("3", queue.inspect().getFirst().remaining_ticks());
        queue.observe("HpLost");
        equal("end", controls()); // The first listener naturally finished at the last tick.
        tick();
        equal("2", queue.inspect().getFirst().remaining_ticks());
        equal("end", controls()); // The second listener finishes without shortening the wait.
        tick();
        tick();
        tick("w");
        equal("end", controls());
    }

    void listenerReturnCancelsLaterHandlersAndDeliversOnlyItsOwnersResult() {
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"functions":{
              "helper":[{"line":20,"ticks":"0","held":[],"return":true,"v":{"expr":["lit",null]}}],
              "watch":[
                {"line":2,"ticks":"0","held":[],"event":"Hurt","body":[
                  {"line":3,"ticks":"0","held":[],"call":"helper","v":{"op":"let","target":["var","val"]}},
                  {"line":4,"ticks":"0","held":[],"return":true,"v":{"expr":["var","val"]}}
                ]},
                {"line":5,"ticks":"0","held":[],"event":"Hurt","body":[
                  {"line":6,"ticks":"0","held":[],"v":{"op":"print","format":"skipped"}}
                ]},
                {"line":7,"ticks":"1","held":[]}
              ]},
              "runs":[
                {"line":8,"ticks":"0","held":[],"call":"watch","v":{"op":"let","target":["var","ret"]}},
                {"line":9,"ticks":"0","held":[],"v":{"op":"print","format":"got {}","args":[["var","ret"]]}},
                {"line":10,"ticks":"0","held":[],"return":true,"v":{"expr":["var","ret"]}}
            ]}
            """));
        equal(null, controls());
        queue.observe("Hurt");
        var log = new StringBuilder();
        equal("end", queue.controls(() -> false, () -> {}, log::append));
        equal("got null\n", log.toString());
        equal(JsonParser.parseString("null"), queue.takeReturnValue());

        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"pause":true},
              {"line":3,"ticks":"0","held":[],"return":true,"v":{"expr":["num","1"]}}
            ]}
            """));
        equal("pause", controls());
        submit("Wait 1");
        tick();
        equal("end", controls());
        equal(null, queue.takeReturnValue());
    }

    void reportsFailedWriteBackAtTheSuspendedAssignment() {
        submit("Wait 4");
        long failed = queue.submit(wire("""
            {"queue_version":1,"persistent":[],"functions":{"f":[
              {"line":20,"ticks":"0","held":[],"pause":true},
              {"line":21,"ticks":"0","held":[],"return":true,"v":{"expr":["num","0"]}}
            ]},"runs":[
              {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","items"],"expr":["array",["num","1"]]}},
              {"line":3,"ticks":"0","held":[],"event":"Hurt","body":[
                {"line":4,"ticks":"0","held":[],"v":{"op":"set","target":["var","items"],"expr":["array"]}},
                {"line":5,"ticks":"0","held":[],"v":{"op":"print","format":"cleared"}}
              ]},
              {"line":6,"ticks":"0","held":[],"call":"f","v":{"op":"set","target":["index",["var","items"],["num","0"]]}},
              {"line":7,"ticks":"0","held":[],"v":{"op":"print","format":"skipped"}}
            ]}
            """));
        equal("pause", controls());
        queue.observe("Hurt");
        var log = new StringBuilder();
        try {
            queue.controls(() -> false, () -> {}, log::append);
            throw new AssertionError("wrote into the old array after the listener cleared it");
        } catch (InputQueue.ValueFailure expected) {
            equal(failed, expected.block());
            equal(6, expected.line());
            equal(List.of(), expected.call_stack());
            equal(true, expected.getMessage().contains("out of bounds"));
        }
        equal("cleared\n", log.toString());
        equal(List.of(), queue.listeners());
        equal("4", queue.inspect().getFirst().remaining_ticks());
        equal(1, queue.inspect().size());
    }

    void listenerContinuesWithItsCapturedValuesAfterNaturalEntryEnd() {
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","name"],"expr":["lit","kept"]}},
              {"line":3,"ticks":"0","held":[],"event":"Hurt","v":{"bind":"hit"},"body":[
                {"line":4,"ticks":"1","held":[],"v":{"op":"input","actions":[["Key",["field",["var","hit"],"key"]]]}},
                {"line":5,"ticks":"0","held":[],"v":{"op":"print","format":"{} {}","args":[["var","name"],["field",["var","hit"],"event"]]}}
              ]},
              {"line":6,"ticks":"1","held":[]}
            ]}
            """));
        equal(null, controls());
        equal(Set.of(), queue.input().held());
        var fields = new JsonObject();
        fields.addProperty("key", "s");
        queue.observe("Hurt", fields, null);
        fields.addProperty("key", "w");
        queue.advance();
        equal("end", controls());
        equal(null, queue.takeReturnValue());
        tick("s");
        var log = new StringBuilder();
        equal("end", queue.controls(() -> false, () -> {}, log::append));
        equal("kept Hurt\n", log.toString());
    }

    void olderErrorKeepsTheCurrentSubmissionsReturnValue() {
        long older = queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"v":{"op":"let","target":["var","n"],"expr":["lit","bad"]}},
              {"line":3,"ticks":"0","held":[],"pause":true},
              {"line":4,"ticks":"0","held":[],"return":true,"v":{"expr":["+",["var","n"],["num","1"]]}}
            ]}
            """));
        equal("pause", controls());
        queue.submit(wire("""
            {"queue_version":1,"persistent":[],"runs":[
              {"line":2,"ticks":"0","held":[],"return":true,"v":{"expr":["num","5"]}}
            ]}
            """));
        try {
            controls();
            throw new AssertionError("the older submission should have reported its invalid return expression");
        } catch (InputQueue.ValueFailure expected) {
            equal(older, expected.block());
        }
        equal(JsonParser.parseString("5"), queue.takeReturnValue());
    }

    public static void main(String[] args) {
        new InputQueueTest().branchSelectionAndLoopSnapshotSurvivePause();
        new InputQueueTest().lastTickListenerPauseDefersLoopExit();
        new InputQueueTest().loopListenerReturnTargetsRegisteringCall();
        new InputQueueTest().loopListenersExpireOnNextIterationAndClear();
        new InputQueueTest().returningSiblingCancelsCompletingHandlerFromBlockScope();
        new InputQueueTest().siblingReturnWaitsForPausedLocalHandler();
        new InputQueueTest().detachedReturnDoesNotUnwindPausedSibling();
        new InputQueueTest().observedMutationChangesTheNextWhileConditionButKeepsSelectedBranch();
        new InputQueueTest().nestedLoopBodyDoesNotIncreaseFunctionDepth();
        new InputQueueTest().validatesEveryFlowBeforeAcceptanceAndReportsEvaluatedBranchLine();
        new InputQueueTest().iterationsShareTheSubmissionBudgetAcrossPausesAndHandlers();
        new InputQueueTest().pausedAssignmentKeepsTheOriginalIndexAndUpdatesTheCurrentArray();
        new InputQueueTest().validatesConstantFailuresInImportedEntriesBeforeAcceptance();
        new InputQueueTest().failedInputTickLeavesOtherInputsAndPersistentKeysUntouched();
        new InputQueueTest().invalidDynamicMouseContextLeavesCandidateAndBufferedInputUntouched();
        new InputQueueTest().dynamicMousePreflightsConvertedAndCombinedMovement();
        new InputQueueTest().waitUsesTheValueAtTheFirstConsumedTick();
        new InputQueueTest().listenerReturnCancelsLaterHandlersAndDeliversOnlyItsOwnersResult();
        new InputQueueTest().reportsFailedWriteBackAtTheSuspendedAssignment();
        new InputQueueTest().listenerContinuesWithItsCapturedValuesAfterNaturalEntryEnd();
        new InputQueueTest().olderErrorKeepsTheCurrentSubmissionsReturnValue();
        new InputQueueTest().worldTransitionWaitsForReadyDestination();
        new InputQueueTest().worldTransitionAcceptsOnlyLiveOwnerSignals();
        new InputQueueTest().worldTransitionBoundsMissingReadbackForItsOwnPeer();
        new InputQueueTest().worldTransitionReleasesPauseWhenCompletedFrameCannotBeSent();
        new InputQueueTest().worldTransitionRetainsPauseAfterSuccessfulDelivery();
        new InputQueueTest().recordsOneNotificationInObservationOrder();
        new InputQueueTest().sealsAtTheStopFrameWhileRetainingListeners();
        new InputQueueTest().reportsListenersFromAnOlderSubmissionInTheNewWindow();
        new InputQueueTest().keepsAcceptedNotificationAfterHandlerReturns();
        new InputQueueTest().evictsOnlyOldEntriesWhenTheJournalIsFull();
        new InputQueueTest().olderClearFinishesAfterItsHandler(false);
        new InputQueueTest().olderClearFinishesAfterItsHandler(true);
        new InputQueueTest().anotherClearTakesOverCompletion();
        new InputQueueTest().returningSiblingCancelsCompletingHandler();
        new InputQueueTest().newerSubmissionReplacesCompletion();
        new InputQueueTest().olderHandlerClearStopsDiscardedForeground();
        new InputQueueTest().olderHandlerClearStopsDiscardedDetachedForeground();
        new InputQueueTest().inspectExhaustedPausedCall();
        new InputQueueTest().budgetSharedByHandlersAndResetAfterDeathTick();
        new InputQueueTest().handlerInheritsDepth();
        new InputQueueTest().laterHandlerClearCancelsEarlierPause();
        new InputQueueTest().pauseKeepsExplicitReturn();
        new InputQueueTest().inspectDistinguishesCallsToSameFunction();
        new InputQueueTest().malformedFunctionControls();
        new InputQueueTest().laterHandlerReturnKeepsEarlierPauseAndScreenshot();
        new InputQueueTest().callerContinuesAtReturn();
        new InputQueueTest().observedOuterCancelsInnerBeforeDispatch();
        new InputQueueTest().observedPausePrecedesNaturalReturn();
        new InputQueueTest().detachedReturnStopsOnlyOriginalRequest();
        new InputQueueTest().validatesUnusedFunctionsAtomically();
        new InputQueueTest().depthAndBudget();
        new InputQueueTest().onlyThisDeathsHandlersCrossDimensions();
        new InputQueueTest().lastExplicitPauseExpiresOwner();
        new InputQueueTest().onlyClearingListenersPreservesOldControls();
        new InputQueueTest().observedBeforeDispatchMergesInRegistrationOrder();
        new InputQueueTest().lastTickEvent(ACTION, "end", 1);
        new InputQueueTest().lastTickEvent(PAUSE, "pause", 0);
        new InputQueueTest().lastTickEvent(ACTION + "," + PAUSE, "end", 2);
        new InputQueueTest().pauseAndOverlay();
        new InputQueueTest().clearAndCompactWait();
        new InputQueueTest().pausesAtEveryBoundary();
        new InputQueueTest().olderClearReleasesNewerWait();
        new InputQueueTest().concurrentStops();
        new InputQueueTest().legacyTargets();
        new InputQueueTest().mouseInputsMerge();
    }
}
