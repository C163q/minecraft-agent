package mcagent;

import java.nio.file.Path;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.BooleanSupplier;

/** A block advances one fixed tick at a time alongside every other retained block. */
final class InputQueue {
    record Origin(String event, long block, int line) {}
    record ListenerState(String event, long block, int line, String state) {}
    record Entry(long block, int line, String source, String remaining_ticks, Origin listener) {}
    private static final class Listener {
        final long order;
        final Origin origin;
        final List<Run> body;
        boolean handling;

        Listener(long order, Origin origin, List<Run> body) {
            this.order = order;
            this.origin = origin;
            this.body = body;
        }
    }
    record Tick(Set<String> held, MouseDispatch.Actions mouse, int line) {}

    private record Run(int line, String source, long ticks, Set<String> legacyHeld,
                       Map<String,String> keys, MouseDispatch.Actions mouse,
                       boolean waitMouse, boolean clear, boolean pause, String event, List<Run> body,
                       boolean clearListeners, Path screenshot) {
        static Run read(JsonObject json, Set<String> knownKeys, boolean incremental, boolean inHandler) {
            int line = json.get("line").getAsInt();
            if (line < 1) throw new IllegalArgumentException("invalid source line");
            try {
                long ticks = Long.parseUnsignedLong(json.get("ticks").getAsString());
                boolean wait = flag(json, "wait_mouse"), clear = flag(json, "clear"), pause = flag(json, "pause");
                int controls = (wait ? 1 : 0) + (clear ? 1 : 0) + (pause ? 1 : 0);
                String event = json.has("event") ? json.get("event").getAsString() : null;
                var body = new ArrayList<Run>();
                if (event != null) {
                    if (inHandler || !Set.of("Hurt", "HpLost").contains(event))
                        throw new IllegalArgumentException("invalid listener declaration");
                    for (var row : json.getAsJsonArray("body"))
                        body.add(read(row.getAsJsonObject(), knownKeys, incremental, true));
                    controls++;
                }
                boolean clearListeners = flag(json, "clear_listeners");
                var screenshot = json.has("screenshot") ? Path.of(json.get("screenshot").getAsString()) : null;
                if (screenshot != null && !screenshot.isAbsolute())
                    throw new IllegalArgumentException("expected absolute screenshot path");
                if (clearListeners) controls++;
                if (screenshot != null) controls++;
                if (controls != (ticks == 0 ? 1 : 0)) throw new IllegalArgumentException("invalid control duration");
                if (!incremental && (pause || json.has("key_actions")))
                    throw new IllegalArgumentException("incremental actions require queue_version 1");
                var held = names(json, "held", knownKeys);
                var actions = new TreeMap<String,String>();
                if (json.has("key_actions")) json.getAsJsonObject("key_actions").entrySet().forEach(e -> {
                    String name = e.getKey(), action = e.getValue().getAsString();
                    if (!knownKeys.contains(name)) throw new IllegalArgumentException("unresolved key " + name);
                    if (!Set.of("temporary", "press", "release").contains(action))
                        throw new IllegalArgumentException("unknown key action " + action);
                    actions.put(name, action);
                });
                var mouse = MouseDispatch.Actions.read(json);
                if (ticks != 1 && (!actions.isEmpty() || !mouse.moves().isEmpty() || !mouse.buttons().isEmpty()))
                    throw new IllegalArgumentException("input actions require one tick");
                String source;
                if (json.has("source")) source = json.get("source").getAsString();
                else if (clear) source = "CLEAR";
                else if (wait) source = "WaitMouseMove()";
                else if (pause) source = "PAUSE";
                else if (!incremental) source = "legacy input, held " + held;
                else source = "input at line " + line;
                return new Run(line, source, ticks, incremental ? null : held, actions, mouse, wait, clear, pause, event, List.copyOf(body), clearListeners, screenshot);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException("line " + line + ": " + error.getMessage());
            }
        }

        private static boolean flag(JsonObject obj, String name) {
            return obj.has(name) && obj.get(name).getAsBoolean();
        }
    }

    private static Set<String> names(JsonObject json, String field, Set<String> knownKeys) {
        var result = new TreeSet<String>();
        for (var key : json.getAsJsonArray(field)) {
            String name = key.getAsString();
            if (!knownKeys.contains(name)) throw new IllegalArgumentException("unresolved key " + name);
            result.add(name);
        }
        return result;
    }

    static final class Block {
        final ArrayDeque<Run> runs = new ArrayDeque<>();
        final Set<String> legacyFinal;
        long id, remaining;
        Listener listener;

        private Block(Set<String> legacyFinal) { this.legacyFinal = legacyFinal; }

        static Block read(JsonObject json, Set<String> knownKeys) {
            boolean incremental = json.has("queue_version");
            if (incremental && !"1".equals(json.get("queue_version").getAsString()))
                throw new IllegalArgumentException("unsupported queue_version");
            var finalKeys = names(json, "persistent", knownKeys);
            var block = new Block(incremental ? null : finalKeys);
            for (var el : json.getAsJsonArray("runs")) block.runs.add(Run.read(el.getAsJsonObject(), knownKeys, incremental, false));
            block.loadRemaining();
            return block;
        }

        void next() {
            runs.removeFirst();
            loadRemaining();
        }

        private void loadRemaining() {
            remaining = runs.isEmpty() ? 0 : runs.getFirst().ticks;
        }
    }

    private final ArrayList<Block> blocks = new ArrayList<>();
    private final TreeSet<String> persistent = new TreeSet<>();
    private long lastId, lastRegistration;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Listener> pending = new ArrayList<>();
    private List<Origin> pauseListeners = List.of();
    private final List<Path> screenshots = new ArrayList<>();

    void observe(String event) {
        for (var listener : listeners) {
            if (!listener.handling && listener.origin.event.equals(event)) {
                listener.handling = true;
                pending.add(listener);
            }
        }
    }

    boolean hasObserved() { return !pending.isEmpty(); }

    // An event can arrive mid-tick. Enlist its block only after the dispatched tick advances.
    private void activateObserved() {
        pending.sort(Comparator.comparingLong(listener -> listener.order));
        for (var listener : pending) {
            var block = new Block(null);
            block.id = ++lastId;
            block.listener = listener;
            block.runs.addAll(listener.body);
            block.loadRemaining();
            blocks.add(block);
        }
        pending.clear();
    }

    List<ListenerState> listeners() {
        return listeners.stream().map(l -> new ListenerState(l.origin.event, l.origin.block,
            l.origin.line, l.handling ? "handling" : "ready")).toList();
    }

    List<Origin> pauseListeners() { return pauseListeners; }

    List<Path> takeScreenshots() {
        var paths = List.copyOf(screenshots);
        screenshots.clear();
        return paths;
    }
    /** Its END stops playback. After that END, older blocks can continue in free play. */
    private Block foreground;

    boolean isEmpty() { return blocks.isEmpty(); }
    Set<String> persistent() { return Set.copyOf(persistent); }

    long submit(Block block) {
        if (!blocks.isEmpty()) {
            boolean startsWithClear = !block.runs.isEmpty() && block.runs.getFirst().clear;
            if (block.legacyFinal != null || (!startsWithClear && blocks.stream().anyMatch(b -> b.legacyFinal != null)))
                throw new IllegalStateException("legacy keyboard targets cannot merge with a retained queue; use a queue-capable client with CLEAR");
        }
        activateObserved();
        block.id = ++lastId;
        blocks.add(block);
        foreground = block;
        return block.id;
    }

    void reset() {
        blocks.clear();
        listeners.clear();
        pending.clear();
        screenshots.clear();
        pauseListeners = List.of();
        persistent.clear();
        foreground = null;
    }

    /** Newer CLEAR takes effect before an older block can execute a control at this boundary. */
    String controls(BooleanSupplier moving, Runnable clearMoves) {
        activateObserved();
        var paused = new HashSet<Block>();
        boolean end = false, cleared;
        do {
            cleared = false;
            for (int i = blocks.size() - 1; i >= 0; --i) {
                var block = blocks.get(i);
                if (paused.contains(block)) continue;
                while (!block.runs.isEmpty()) {
                    var run = block.runs.getFirst();
                    if (run.ticks != 0 || (run.waitMouse && moving.getAsBoolean())) break;
                    block.next();
                    if (run.event != null)
                        listeners.add(new Listener(++lastRegistration, new Origin(run.event, block.id, run.line), run.body));
                    if (run.clearListeners) listeners.clear();
                    if (run.screenshot != null) screenshots.add(run.screenshot);
                    if (run.pause) {
                        paused.add(block);
                        break;
                    }
                    if (run.clear) {
                        var older = blocks.subList(0, i);
                        paused.removeAll(older);
                        for (var removed : older) retire(removed);
                        older.clear();
                        i = 0;
                        clearMoves.run();
                        cleared = true;
                    }
                }
                if (block.runs.isEmpty() && !paused.contains(block)) {
                    blocks.remove(i);
                    retire(block);
                    if (block.listener != null) end = true;
                    if (block.legacyFinal != null) {
                        persistent.clear();
                        persistent.addAll(block.legacyFinal);
                    }
                    if (block == foreground) {
                        foreground = null;
                        end = true;
                    }
                }
            }
            // A later block may have been waiting for a movement cleared by an earlier block.
        } while (cleared);
        pauseListeners = paused.stream().map(b -> b.listener).filter(Objects::nonNull)
            .sorted(Comparator.comparingLong(l -> l.order)).map(l -> l.origin).toList();
        if (!paused.isEmpty()) return "pause";
        return end ? "end" : null;
    }

    private void retire(Block block) {
        listeners.removeIf(l -> l.origin.block == block.id);
        if (block.listener != null) block.listener.handling = false;
    }

    Tick input() {
        var keys = new TreeMap<String,String>();
        var buttons = new TreeMap<Integer,String>();
        var moves = new ArrayList<MouseDispatch.Input>();
        Set<String> legacyHeld = null;
        int line = 0;
        for (var block : blocks) {
            var run = block.runs.getFirst();
            keys.putAll(run.keys);
            buttons.putAll(run.mouse.buttons());
            moves.addAll(run.mouse.moves());
            if (run.legacyHeld != null) legacyHeld = run.legacyHeld;
            line = run.line;
        }
        if (legacyHeld != null) {
            // Before legacy END we only know which previously persistent keys were definitely released.
            persistent.retainAll(legacyHeld);
            return new Tick(legacyHeld, new MouseDispatch.Actions(moves, buttons), line);
        }
        var wanted = new TreeSet<>(persistent);
        keys.forEach((key, action) -> {
            if (action.equals("release")) {
                persistent.remove(key);
                wanted.remove(key);
            } else {
                if (action.equals("press")) persistent.add(key);
                wanted.add(key);
            }
        });
        return new Tick(wanted, new MouseDispatch.Actions(moves, buttons), line);
    }

    void advance() {
        for (var block : blocks) {
            if (block.remaining != 0 && --block.remaining == 0) block.next();
        }
    }

    List<Entry> inspect() {
        var rows = new ArrayList<Entry>();
        for (var block : blocks) {
            boolean head = true;
            for (var run : block.runs) {
                String remaining = run.ticks == 0 ? null : Long.toUnsignedString(head ? block.remaining : run.ticks);
                rows.add(new Entry(block.id, run.line, run.source, remaining, block.listener == null ? null : block.listener.origin));
                head = false;
            }
        }
        // Predict IDs in activation order without enlisting an observed block in a tick already in flight.
        long id = lastId;
        for (var l : pending.stream().sorted(Comparator.comparingLong(l -> l.order)).toList()) {
            id++;
            for (var run : l.body) rows.add(new Entry(id, run.line, run.source,
                run.ticks == 0 ? null : Long.toUnsignedString(run.ticks), l.origin));
        }
        return rows;
    }
}
