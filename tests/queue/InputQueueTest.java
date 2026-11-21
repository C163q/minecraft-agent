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

    public static void main(String[] args) {
        new InputQueueTest().pauseAndOverlay();
        new InputQueueTest().clearAndCompactWait();
        new InputQueueTest().pausesAtEveryBoundary();
        new InputQueueTest().olderClearReleasesNewerWait();
        new InputQueueTest().concurrentStops();
        new InputQueueTest().legacyTargets();
        new InputQueueTest().mouseInputsMerge();
    }
}
