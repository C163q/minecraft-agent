package mcagent;

import java.nio.file.Path;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** A block advances one fixed tick at a time alongside every other retained block. */
final class InputQueue {
    static final List<String> EVENT_NAMES = List.of("Hurt", "HpLost", "Death", "CollideX", "CollideZ",
        "BlockBroken", "LeftGround", "ScreenOpened", "DamageBlocked", "BlockInteracted");
    record Call(long id, String name, int line, String file) {}
    record Origin(String event, long block, int line, List<Call> call_stack) {}
    record ListenerState(String event, long block, int line, String state, List<Call> call_stack) {}
    record ActiveCall(long block, List<Call> call_stack, Integer last_line, String next, Integer next_line) {}
    record Entry(long block, int line, String source, String remaining_ticks, Origin listener, List<Call> call_stack) {}

    static class ValueFailure extends RuntimeException {
        private final long block;
        private final String file;
        private final int line;
        private final List<Call> callStack;

        private ValueFailure(long block, Run run, List<Call> callStack, String message) {
            super("block " + block + ": " + run.location() + ": " + message
                + (callStack == null ? "" : "; call stack " + String.join(" -> ",
                    callStack.stream().map(c -> "$" + c.name + " at " + location(c.file, c.line)).toList())));
            this.block = block;
            this.file = run.file;
            this.line = run.line;
            this.callStack = callStack == null ? List.of() : callStack;
        }

        long block() { return block; }
        String file() { return file; }
        int line() { return line; }
        List<Call> call_stack() { return callStack; }
    }

    static final class FunctionLimit extends ValueFailure {
        private FunctionLimit(long block, Run run, List<Call> stack, String limit) {
            super(block, run, stack, limit + " entering $" + run.call);
        }
    }

    private static final class Listener {
        final long order;
        final Frame owner;
        final Origin origin;
        final List<Run> body;
        final ScriptValues.Scope captured;
        final String binding;
        boolean handling;

        Listener(long order, Frame owner, Run run) {
            this.order = order;
            this.owner = owner;
            this.origin = new Origin(run.event, owner.block.submission.id, run.line, owner.stack);
            this.body = run.body;
            this.captured = owner.scope.child();
            this.binding = run.values.binding();
        }
    }
    record Tick(Set<String> held, MouseDispatch.Actions mouse, int line, MouseDispatch.Prepared prepared) {}

    private record Run(int line, String file, String source, long ticks, Set<String> legacyHeld,
                       Map<String,String> keys, MouseDispatch.Actions mouse,
                       boolean waitMouse, boolean clear, boolean pause, String event, List<Run> body,
                       boolean clearListeners, Path screenshot, String call, boolean returned, InputValues values) {
        String location() { return InputQueue.location(file, line); }

        static Run read(JsonObject json, Set<String> knownKeys, boolean incremental, boolean inHandler,
                        Map<String,List<String>> params) {
            int line = json.get("line").getAsInt();
            if (line < 1) throw new IllegalArgumentException("invalid source line");
            String file = json.has("file") ? ScriptValues.string(json.get("file")) : null;
            try {
                long ticks = Long.parseUnsignedLong(json.get("ticks").getAsString());
                boolean wait = flag(json, "wait_mouse"), clear = flag(json, "clear"), pause = flag(json, "pause");
                int controls = (wait ? 1 : 0) + (clear ? 1 : 0) + (pause ? 1 : 0);
                String event = json.has("event") ? json.get("event").getAsString() : null;
                var body = new ArrayList<Run>();
                if (event != null) {
                    if (inHandler || !EVENT_NAMES.contains(event))
                        throw new IllegalArgumentException("invalid listener declaration");
                    for (var row : json.getAsJsonArray("body"))
                        body.add(read(row.getAsJsonObject(), knownKeys, incremental, true, params));
                    controls++;
                }
                String call = null;
                if (json.has("call")) {
                    var target = json.get("call");
                    if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("expected a function name");
                    call = target.getAsString();
                    if (!params.containsKey(call)) throw new IllegalArgumentException("undefined function $" + call);
                    controls++;
                }
                boolean returned = json.has("return");
                if (returned) {
                    var value = json.get("return");
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean() || !value.getAsBoolean())
                        throw new IllegalArgumentException("expected return: true");
                }
                if (returned) controls++;
                boolean clearListeners = flag(json, "clear_listeners");
                var screenshot = json.has("screenshot") ? Path.of(json.get("screenshot").getAsString()) : null;
                if (screenshot != null && !screenshot.isAbsolute())
                    throw new IllegalArgumentException("expected absolute screenshot path");
                if (clearListeners) controls++;
                if (screenshot != null) controls++;
                var values = InputValues.read(json.getAsJsonObject("v"), event, call, returned);
                if (call != null && values.args().size() != params.get(call).size())
                    throw new IllegalArgumentException("$" + call + " expects " + params.get(call).size() + " arguments");
                if ((values.assignment() && call == null) || values.op().equals("print")) controls++;
                if (controls != (ticks == 0 ? 1 : 0)) throw new IllegalArgumentException("invalid control duration");
                if (!incremental && (pause || call != null || returned || json.has("key_actions") || json.has("v")))
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
                boolean hasStaticInput = !actions.isEmpty() || !mouse.moves().isEmpty() || !mouse.buttons().isEmpty();
                if (ticks != 1 && (hasStaticInput || values.input() || values.delay()))
                    throw new IllegalArgumentException("input actions require one tick");
                if (hasStaticInput && (values.input() || values.delay()))
                    throw new IllegalArgumentException("cannot combine static and dynamic inputs in the same entry");
                String source;
                if (json.has("source")) source = json.get("source").getAsString();
                else if (clear) source = "CLEAR";
                else if (wait) source = "WaitMouseMove()";
                else if (pause) source = "PAUSE";
                else if (call != null) source = "$" + call + "()";
                else if (returned) source = "RETURN";
                else if (!incremental) source = "legacy input, held " + held;
                else source = "input at line " + line;
                return new Run(line, file, source, ticks, incremental ? null : held, actions, mouse, wait, clear, pause,
                    event, List.copyOf(body), clearListeners, screenshot, call, returned, values);
            } catch (RuntimeException error) {
                throw new IllegalArgumentException(InputQueue.location(file, line) + ": " + error.getMessage(), error);
            }
        }

        private static boolean flag(JsonObject obj, String name) {
            return obj.has(name) && obj.get(name).getAsBoolean();
        }
    }

    private static String location(String file, int line) {
        return (file == null ? "" : file + ": ") + "line " + line;
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

    /** All branches of a submission use the same definitions and zero-tick budget. */
    private static final class Submission {
        final Map<String,List<Run>> functions = new HashMap<>();
        final Map<String,List<String>> params = new HashMap<>();
        long id, budgetTick;
        int entries;
    }

    private record ReturnAssignment(Frame caller, Run run, Consumer<JsonElement> write) {}

    private static final class Frame {
        final Block block;
        final Frame parent; // Calling frame, or the listener's registration frame.
        final List<Call> stack;
        final List<Run> code;
        final ScriptValues.Scope scope;
        final ReturnAssignment destination;
        int pc;
        Integer lastLine;
        long remaining;
        boolean registeredListener;
        boolean waitStarted;

        Frame(Block block, Frame parent, List<Call> stack, List<Run> code, ScriptValues.Scope scope,
              ReturnAssignment destination) {
            this.block = block;
            this.parent = parent;
            this.stack = stack;
            this.code = code;
            this.scope = scope;
            this.destination = destination;
            loadRemaining();
        }

        Run head() { return pc == code.size() ? null : code.get(pc); }

        void next() {
            lastLine = head().line;
            pc++;
            loadRemaining();
        }

        private void loadRemaining() {
            remaining = pc == code.size() ? 0 : code.get(pc).ticks;
            waitStarted = false;
        }

        boolean descendsFrom(Frame ancestor) {
            for (Frame f = this; f != null; f = f.parent)
                if (f == ancestor) return true;
            return false;
        }
    }

    static final class Block {
        final Submission submission;
        final Set<String> legacyFinal;
        final Listener listener;
        final ArrayDeque<Frame> frames = new ArrayDeque<>();
        final boolean onlyClearsListeners;
        long id, observation, group;
        boolean detached;

        private Block(Submission submission, Set<String> legacyFinal, Listener listener, List<Run> body,
                      ScriptValues.Scope scope) {
            this.submission = submission;
            this.legacyFinal = legacyFinal;
            this.listener = listener;
            var owner = listener == null ? null : listener.owner;
            frames.push(new Frame(this, owner, owner == null ? null : owner.stack, body, scope, null));
            onlyClearsListeners = !body.isEmpty() && body.stream().allMatch(Run::clearListeners);
        }

        Frame current() { return frames.getFirst(); }

        static Block read(JsonObject json, Set<String> knownKeys) {
            boolean incremental = json.has("queue_version");
            if (incremental && !"1".equals(json.get("queue_version").getAsString()))
                throw new IllegalArgumentException("unsupported queue_version");
            var finalKeys = names(json, "persistent", knownKeys);
            var submission = new Submission();
            var definitions = json.has("functions") ? json.getAsJsonObject("functions") : new JsonObject();
            if (!incremental && (json.has("functions") || json.has("params") || json.has("checks")))
                throw new IllegalArgumentException("functions require queue_version 1");
            for (var name : definitions.keySet()) {
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*"))
                    throw new IllegalArgumentException("invalid function name $" + name);
                submission.params.put(name, List.of());
            }
            if (json.has("params")) for (var entry : json.getAsJsonObject("params").entrySet()) {
                var name = entry.getKey();
                if (!submission.params.containsKey(name))
                    throw new IllegalArgumentException("undefined function $" + name + " in parameter definitions");
                var parameters = new LinkedHashSet<String>();
                for (var value : entry.getValue().getAsJsonArray()) {
                    String parameter = ScriptValues.name(value);
                    if (!parameters.add(parameter))
                        throw new IllegalArgumentException("duplicate parameter $" + parameter + " of $" + name);
                }
                submission.params.put(name, List.copyOf(parameters));
            }
            for (var entry : definitions.entrySet()) {
                var code = readRuns(entry.getValue().getAsJsonArray(), knownKeys, incremental, submission.params);
                submission.functions.put(entry.getKey(), code);
            }
            if (json.has("checks")) for (var rows : json.getAsJsonArray("checks"))
                readRuns(rows.getAsJsonArray(), knownKeys, incremental, submission.params);
            var body = readRuns(json.getAsJsonArray("runs"), knownKeys, incremental, submission.params);
            return new Block(submission, incremental ? null : finalKeys, null, body, new ScriptValues.Scope());
        }

        private static List<Run> readRuns(JsonArray rows, Set<String> knownKeys, boolean incremental,
                                          Map<String,List<String>> params) {
            var runs = new ArrayList<Run>(rows.size());
            for (var row : rows) runs.add(Run.read(row.getAsJsonObject(), knownKeys, incremental, false, params));
            return List.copyOf(runs);
        }
    }

    private final SequencedSet<Block> blocks = new LinkedHashSet<>();
    private final TreeSet<String> persistent = new TreeSet<>();
    private long lastId, lastRegistration, lastCall, lastGroup, tick;
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Block> pending = new ArrayList<>();
    private List<Origin> pauseListeners = List.of();
    private final List<Path> screenshots = new ArrayList<>();

    // Use the first handler block ID to identify all handlers of one observed notification.
    // Zero denotes a notification for which no listener was eligible.
    long observe(String event) {
        return observe(event, null, null);
    }

    long observe(String event, JsonObject fields, EventJournal journal) {
        var accepted = journal != null && journal.isOpen() ? new ArrayList<Origin>() : null;
        long observation = 0;
        for (var listener : listeners) {
            if (!listener.handling && listener.origin.event.equals(event)) {
                listener.handling = true;
                var scope = listener.captured.child();
                if (listener.binding != null) {
                    var snapshot = fields == null ? new JsonObject() : fields.deepCopy();
                    snapshot.addProperty("event", event);
                    scope.declare(listener.binding, snapshot, true);
                }
                var block = new Block(listener.owner.block.submission, null, listener, listener.body, scope);
                block.id = ++lastId;
                if (observation == 0) observation = block.id;
                block.observation = observation;
                pending.add(block);
                if (accepted != null) accepted.add(listener.origin);
            }
        }
        if (accepted != null && !accepted.isEmpty()) journal.record(event, fields, accepted);
        return observation;
    }

    /** Called at observation time, before the new handlers have entered the tick queue. */
    boolean foregroundObserved(long observation) {
        return pending.stream().anyMatch(b -> b.observation == observation && b.submission == foreground);
    }

    boolean hasObservation(long observation) {
        return pending.stream().anyMatch(b -> b.observation == observation)
            || blocks.stream().anyMatch(b -> b.observation == observation);
    }

    /** A respawn may replace the dimension before this death can finish dispatching. */
    void retainObservation(long observation) {
        blocks.removeIf(b -> b.observation != observation);
        pending.removeIf(b -> b.observation != observation);
        if (completingForeground != null && completingForeground.observation != observation) completingForeground = null;
        for (var block : blocks) block.detached = true;
        for (var block : pending) block.detached = true;
        listeners.clear();
        persistent.clear();
    }

    boolean hasObserved() { return !pending.isEmpty(); }

    // An event can arrive mid-tick. Enlist its block only after the dispatched tick advances.
    private void activateObserved() {
        if (pending.isEmpty()) return;
        long group = ++lastGroup;
        for (var block : pending) block.group = group;
        pending.sort(Comparator.comparingLong(block -> block.listener.order));
        blocks.addAll(pending);
        pending.clear();
    }

    List<ListenerState> listeners() {
        return listeners.stream().map(l -> new ListenerState(l.origin.event, l.origin.block,
            l.origin.line, l.handling ? "handling" : "ready", l.origin.call_stack)).toList();
    }

    List<Origin> pauseListeners() { return pauseListeners; }

    List<Path> takeScreenshots() {
        var paths = List.copyOf(screenshots);
        screenshots.clear();
        return paths;
    }
    /** Its END stops playback. After that END, older blocks can continue in free play. */
    private Submission foreground;
    // A handler that CLEARs the foreground finishes its own work before reporting END.
    private Block completingForeground;
    private JsonElement returnValue;

    private void forgetForeground() {
        foreground = null;
        completingForeground = null;
    }

    boolean isEmpty() { return blocks.isEmpty(); }
    Set<String> persistent() { return Set.copyOf(persistent); }

    long submit(Block block) {
        if (!blocks.isEmpty()) {
            boolean startsWithClear = block.current().head() != null && block.current().head().clear;
            if (block.legacyFinal != null || (!startsWithClear && blocks.stream().anyMatch(b -> b.legacyFinal != null)))
                throw new IllegalStateException("legacy keyboard targets cannot merge with a retained queue; use a queue-capable client with CLEAR");
        }
        activateObserved();
        block.id = ++lastId;
        block.submission.id = block.id;
        block.group = ++lastGroup;
        blocks.add(block);
        foreground = block.submission;
        completingForeground = null;
        returnValue = null;
        return block.id;
    }

    void reset() {
        blocks.clear();
        listeners.clear();
        pending.clear();
        screenshots.clear();
        pauseListeners = List.of();
        persistent.clear();
        forgetForeground();
        returnValue = null;
    }

    /** Java null means that the current submission has not explicitly returned a value. */
    JsonElement takeReturnValue() {
        var result = returnValue;
        returnValue = null;
        return result;
    }

    /** A newer submission runs its leading CLEAR first. Observations in one batch run in registration order. */
    String controls(BooleanSupplier moving, Runnable clearMoves) {
        return controls(moving, clearMoves, ignored -> {});
    }

    String controls(BooleanSupplier moving, Runnable clearMoves, Consumer<String> print) {
        activateObserved();
        if (!blocks.isEmpty()) {
            var newest = blocks.getLast();
            if (newest.submission == foreground && newest.listener == null && newest.onlyClearsListeners) {
                listeners.clear();
                blocks.removeLast();
                forgetForeground();
                pauseListeners = List.of();
                return "end";
            }
        }
        var boundary = new Boundary();
        var order = new ArrayList<>(blocks);
        order.sort(Comparator.comparingLong((Block b) -> b.group).reversed()
            .thenComparingLong(b -> b.listener == null ? 0 : b.listener.order));
        do {
            boundary.revisit = false;
            for (var block : order) {
                if (!blocks.contains(block) || boundary.paused.contains(block)) continue;
                execute(block, boundary, moving, clearMoves, print);
            }
        } while (boundary.revisit);
        if (completingForeground != null && !blocks.contains(completingForeground)) {
            forgetForeground();
            boundary.end = true;
        }
        pauseListeners = boundary.paused.stream().map(b -> b.listener).filter(Objects::nonNull)
            .sorted(Comparator.comparingLong(l -> l.order)).map(l -> l.origin).toList();
        if (!boundary.paused.isEmpty()) return "pause";
        return boundary.end ? "end" : null;
    }

    private static final class Boundary {
        final Set<Block> paused = new HashSet<>();
        final Set<Frame> deferReturn = new HashSet<>();
        boolean end, revisit;

        void pause(Block block) {
            paused.add(block);
            if (block.listener != null)
                for (Frame f = block.listener.owner; f != null; f = f.parent) deferReturn.add(f);
        }
    }

    private void execute(Block block, Boundary boundary, BooleanSupplier moving, Runnable clearMoves,
                         Consumer<String> print) {
        while (true) {
            var frame = block.current();
            var run = frame.head();
            // The natural END of the entry keeps observed handlers, so it cannot cancel a PAUSE.
            boolean returnsFromOwner = run == null ? frame.parent != null : run.returned;
            if (returnsFromOwner && boundary.deferReturn.contains(frame)) return;
            if (run == null) {
                if (!leave(block, frame, false, null, boundary)) return;
                continue;
            }
            if (run.ticks != 0 || (run.waitMouse && moving.getAsBoolean())) return;
            frame.next();
            try {
                if (run.call != null) enter(block, frame, run);
                else if (run.returned) {
                    var value = run.values.expr() == null ? null : ScriptValues.evaluate(run.values.expr(), frame.scope);
                    if (block.listener != null && block.frames.size() == 1) {
                        returnFromHandler(block, value, boundary);
                        return;
                    }
                    if (!leave(block, frame, true, value, boundary)) return;
                } else if (run.values.assignment()) {
                    var destination = assignment(frame, run);
                    destination.write.accept(ScriptValues.evaluate(run.values.expr(), frame.scope));
                } else if (run.values.op().equals("print")) {
                    print.accept(ScriptValues.format(run.values.format(), run.values.evaluateArgs(frame.scope)));
                } else if (run.event != null) {
                    frame.registeredListener = true;
                    listeners.add(new Listener(++lastRegistration, frame, run));
                } else if (run.clearListeners) listeners.clear();
                else if (run.screenshot != null) screenshots.add(run.screenshot);
                else if (run.pause) {
                    boundary.pause(block);
                    if (frame.head() == null && block.frames.size() == 1) expireListeners(frame);
                    return;
                } else if (run.clear) {
                    clearBefore(block, boundary);
                    clearMoves.run();
                    boundary.revisit = true;
                }
            } catch (IllegalArgumentException error) {
                throw fail(frame, run, error);
            }
        }
    }

    private static ReturnAssignment assignment(Frame frame, Run run) {
        if (run.values.op().equals("let")) {
            String name = run.values.declarationName();
            return new ReturnAssignment(frame, run, value -> frame.scope.declare(name, value, false));
        }
        var target = ScriptValues.target(run.values.target(), frame.scope);
        return new ReturnAssignment(frame, run, target::write);
    }

    private void clearBefore(Block block, Boundary boundary) {
        boolean removedForeground = false, removedEntry = false, removedCompletion = false;
        for (var older : List.copyOf(blocks)) {
            if (older == block) break;
            if (older == completingForeground) removedCompletion = true;
            if (older.submission == foreground) {
                removedForeground = true;
                if (older.listener == null) removedEntry = true;
            }
            boundary.paused.remove(older);
            removeBlock(older);
        }
        if (removedCompletion || removedEntry ||
                (removedForeground && blocks.stream().noneMatch(b -> b.submission == foreground))) {
            completingForeground = block;
        }
    }

    private void enter(Block block, Frame parent, Run run) {
        var stack = parent.stack == null ? new ArrayList<Call>() : new ArrayList<>(parent.stack);
        var call = new Call(++lastCall, run.call, run.line, run.file);
        stack.add(call);
        if (block.submission.budgetTick != tick) {
            block.submission.budgetTick = tick;
            block.submission.entries = 0;
        }
        String limit = null;
        if (stack.size() > 32) limit = "function depth limit 32";
        else if (++block.submission.entries > 4096) limit = "function entry limit 4096";
        if (limit != null) {
            abort(block.submission);
            throw new FunctionLimit(block.submission.id, run, List.copyOf(stack), limit);
        }
        var destination = run.values.assignment() ? assignment(parent, run) : null;
        var scope = new ScriptValues.Scope();
        var arguments = run.values.evaluateArgs(parent.scope);
        var params = block.submission.params.get(run.call);
        for (int i = 0; i < arguments.size(); ++i) scope.declare(params.get(i), arguments.get(i), false);
        block.frames.push(new Frame(block, parent, List.copyOf(stack), block.submission.functions.get(run.call),
            scope, destination));
    }

    /** RETURN in a handler targets its registration, even when the owner is currently in a child call. */
    private void returnFromHandler(Block block, JsonElement value, Boundary boundary) {
        Frame owner = block.listener.owner;
        if (block.detached || !blocks.contains(owner.block)) {
            if (!block.detached && owner.parent == null) cancelChildren(owner);
            removeBlock(block);
            if (block.submission == foreground && completingForeground == null) {
                if (owner.parent == null) returnValue = value;
                forgetForeground();
                boundary.end = true;
            }
            return;
        }
        while (owner.block.current() != owner) {
            var child = owner.block.frames.pop();
            expireListeners(child);
        }
        leave(owner.block, owner, true, value, boundary);
        boundary.revisit = true;
    }

    /** True when this branch continues in its caller at the same boundary. */
    private boolean leave(Block block, Frame frame, boolean explicit, JsonElement value, Boundary boundary) {
        boolean root = block.frames.size() == 1;
        if (explicit || !root) cancelChildren(frame);
        expireListeners(frame);
        if (!root) {
            block.frames.pop();
            if (frame.destination != null) {
                var destination = frame.destination;
                try {
                    destination.write.accept(value == null ? JsonNull.INSTANCE : value);
                } catch (IllegalArgumentException error) {
                    throw fail(destination.caller, destination.run, error);
                }
            }
            return true;
        }
        removeBlock(block);
        if (block.legacyFinal != null) {
            persistent.clear();
            persistent.addAll(block.legacyFinal);
        }
        if (block.listener != null) boundary.end = true;
        else if (foreground == block.submission) {
            returnValue = value;
            forgetForeground();
            boundary.end = true;
        }
        return false;
    }

    private void cancelChildren(Frame owner) {
        if (!owner.registeredListener) return;
        for (var other : List.copyOf(blocks))
            if (other.listener != null && other.listener.owner.descendsFrom(owner)) removeBlock(other);
        pending.removeIf(other -> other.listener.owner.descendsFrom(owner));
    }

    private void expireListeners(Frame frame) {
        if (frame.registeredListener) listeners.removeIf(l -> l.owner == frame);
    }

    private void removeBlock(Block block) {
        blocks.remove(block);
        listeners.removeIf(l -> l.owner.block == block);
        if (block.listener != null) block.listener.handling = false;
    }

    private ValueFailure fail(Frame frame, Run run, RuntimeException error) {
        abort(frame.block.submission);
        var failure = new ValueFailure(frame.block.submission.id, run, frame.stack, error.getMessage());
        failure.initCause(error);
        return failure;
    }

    private void abort(Submission submission) {
        for (var other : List.copyOf(blocks))
            if (other.submission == submission) removeBlock(other);
        pending.removeIf(b -> b.submission == submission);
        listeners.removeIf(l -> l.owner.block.submission == submission);
        if (foreground == submission ||
                completingForeground != null && completingForeground.submission == submission) forgetForeground();
    }

    Tick input() {
        return input(KeyDispatch::resolve, rows -> null);
    }

    Tick input(ToIntFunction<String> resolveKey, Function<List<MouseDispatch.Row>,MouseDispatch.Prepared> prepareMouse) {
        var keys = new TreeMap<String,String>();
        var buttons = new TreeMap<Integer,String>();
        var moves = new ArrayList<MouseDispatch.Input>();
        var mouseRows = new ArrayList<MouseDispatch.Row>();
        var mouseSources = new ArrayList<Frame>();
        var durations = new HashMap<Frame,Long>();
        Set<String> legacyHeld = null;
        int line = 0;
        for (var block : blocks) {
            var frame = block.current();
            var run = frame.head();
            if (run == null) continue;
            try {
                if (run.values.delay() && !frame.waitStarted) {
                    long delay = ScriptValues.waitTicks(ScriptValues.evaluate(run.values.expr(), frame.scope));
                    durations.put(frame, delay);
                }
                List<MouseDispatch.Input> rowMoves;
                Map<Integer,String> rowButtons;
                boolean dynamic = run.values.input();
                if (dynamic) {
                    var resolved = run.values.evaluateInput(frame.scope, resolveKey);
                    keys.putAll(resolved.keys);
                    rowButtons = resolved.buttons;
                    rowMoves = resolved.moves;
                } else {
                    keys.putAll(run.keys);
                    rowButtons = run.mouse.buttons();
                    rowMoves = run.mouse.moves();
                }
                buttons.putAll(rowButtons);
                moves.addAll(rowMoves);
                if (dynamic || !rowMoves.isEmpty() || !rowButtons.isEmpty()) {
                    mouseRows.add(new MouseDispatch.Row(rowMoves, !rowButtons.isEmpty(), dynamic));
                    mouseSources.add(frame);
                }
            } catch (IllegalArgumentException error) {
                throw fail(frame, run, error);
            }
            if (run.legacyHeld != null) legacyHeld = run.legacyHeld;
            line = run.line;
        }
        MouseDispatch.Prepared prepared;
        try {
            prepared = prepareMouse.apply(mouseRows);
        } catch (MouseDispatch.PreflightFailure error) {
            var frame = mouseSources.get(error.row);
            throw fail(frame, frame.head(), error);
        }
        // A sibling's failure leaves the candidate tick unconsumed, including first-time waits.
        durations.forEach((frame, duration) -> {
            frame.remaining = duration;
            frame.waitStarted = true;
        });
        if (legacyHeld != null) {
            // Before legacy END we only know which previously persistent keys were definitely released.
            persistent.retainAll(legacyHeld);
            return new Tick(legacyHeld, new MouseDispatch.Actions(moves, buttons), line, prepared);
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
        return new Tick(wanted, new MouseDispatch.Actions(moves, buttons), line, prepared);
    }

    void advance() {
        tick++;
        for (var block : blocks) {
            var frame = block.current();
            if (frame.remaining != 0) {
                frame.lastLine = frame.head().line;
                if (--frame.remaining == 0) frame.next();
            }
        }
    }

    List<ActiveCall> activeCalls() {
        var calls = new ArrayList<ActiveCall>();
        for (var block : blocks) inspectCall(block, calls);
        pending.stream().sorted(Comparator.comparingLong(b -> b.listener.order))
            .forEach(block -> inspectCall(block, calls));
        return calls.isEmpty() ? null : calls;
    }

    private static void inspectCall(Block block, List<ActiveCall> calls) {
        var frame = block.current();
        if (frame.stack == null) return;
        var next = frame.head();
        calls.add(new ActiveCall(block.id, frame.stack, frame.lastLine, next == null ? "END" : next.source,
            next == null ? null : next.line));
    }

    List<Entry> inspect() {
        var rows = new ArrayList<Entry>();
        for (var block : blocks) inspectBlock(block, rows);
        pending.stream().sorted(Comparator.comparingLong(b -> b.listener.order))
            .forEach(block -> inspectBlock(block, rows));
        return rows;
    }

    private static void inspectBlock(Block block, List<Entry> rows) {
        for (var frame : block.frames) {
            for (int i = frame.pc; i < frame.code.size(); i++) {
                var run = frame.code.get(i);
                String remaining = run.ticks == 0 || (run.values.delay() && (i != frame.pc || !frame.waitStarted))
                    ? null : Long.toUnsignedString(i == frame.pc ? frame.remaining : run.ticks);
                rows.add(new Entry(block.id, run.line, run.source, remaining,
                    block.listener == null ? null : block.listener.origin, frame.stack));
            }
        }
    }
}
