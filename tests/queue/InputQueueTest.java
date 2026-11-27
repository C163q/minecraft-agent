package mcagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    public static void main(String[] args) {
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
