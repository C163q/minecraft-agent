package mcagent;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;

/** Playback belongs to the client thread. The server uses the fence, and IPC tracks transition delivery. */
public final class Playback {
    public static final Playback INSTANCE = new Playback();
    public static final Identifier HURT_SIGNAL = Identifier.fromNamespaceAndPath("minecraft_agent", "hurt");
    private static final double DEFAULT_MAX_SPEED = 50;
    private final HeldToggleBindings toggles = new HeldToggleBindings();
    private final KeyDispatch keys = new KeyDispatch(toggles);
    private final MouseDispatch mouse = new MouseDispatch(toggles);
    private final InputQueue queue = new InputQueue();
    private final Map<String,Integer> codes = new HashMap<>();
    private ClientLevel level;
    private static final int CONTACT_X = 1, CONTACT_Z = 2;
    private LocalPlayer contactPlayer;
    private int previousContacts, currentContacts;
    private boolean simulatingPlayer;

    private double maxSpeed = DEFAULT_MAX_SPEED;

    // A request waits for its stopped frame. Manual playback has no requesting peer.
    private Request active;
    private volatile WorldTransition transition;
    private long nextTransitionToken, inputGeneration;
    private boolean running, inTick, appliedInput, hotkeyStop, resumeAfterCapture;
    private String pauseReason;
    private String lastError;
    private List<InputQueue.Origin> pauseListeners = List.of();
    private List<Path> screenshotPaths = List.of();
    private long captureGeneration;
    private boolean renderReady, capturing;
    private boolean freeze, pauseConfirmed;
    // A native-menu END returns ownership to that menu once its stopped frame is delivered.
    private boolean endInNativeMenu;
    private final AtomicReference<Fence> fence = new AtomicReference<>();
    private int nextMarker = new Random().nextInt();

    record Snapshot(ClientLevel level, Set<String> persistent) {}
    record Transition(String direction, String status, boolean input_cleared) {}
    record ScriptError(String message, long block, String file, int line, List<InputQueue.Call> call_stack) {}
    record Output(String stdout, JsonElement returnValue, ScriptError error, String captureError) {}
    record Observation(long ticks, String world, String pauseReason, List<InputQueue.Origin> listeners,
                       EventJournal.Report events, byte[] png, FrameCamera.View camera,
                       Transition transition, String screen, Output output) {
        Observation(long ticks, String world, String pauseReason, List<InputQueue.Origin> listeners,
                    EventJournal.Report events, byte[] png, FrameCamera.View camera) {
            this(ticks, world, pauseReason, listeners, events, png, camera, null, null, null);
        }
    }
    record Held(Map<String,String> keys, Map<String,String> buttons) {}
    record InputState(String world, Held held, List<Movement.Pending<?>> moves, double max_speed_px_per_tick,
                      String pause_reason, String last_error, List<InputQueue.ActiveCall> active_calls,
                      List<InputQueue.ListenerState> listeners,
                      List<InputQueue.Entry> queue_entries) {}

    private record Fence(IntegratedServer server, int id) {}
    private static final class Request {
        final CompletableFuture<Observation> result;
        final EventJournal events = new EventJournal();
        final boolean includeScreenshot;
        final long maxTicks;
        final StringBuilder stdout = new StringBuilder();
        JsonElement returnValue;
        ScriptError error;
        String captureError;

        void print(String line) {
            if (line.length() > ScriptValues.MAX_OUTPUT_CHARS - stdout.length())
                throw new IllegalArgumentException("stdout exceeds the per-call size limit");
            stdout.append(line);
        }

        Output output() { return new Output(stdout.toString(), returnValue, error, captureError); }
        long executed;
        long deathObservation;

        Request(JsonObject program, CompletableFuture<Observation> result) {
            this.result = result;
            var limit = program.get("max_ticks");
            try {
                maxTicks = limit == null ? 600 : Long.parseLong(limit.getAsString());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("max_ticks must be a positive 64-bit integer", invalid);
            }
            if (maxTicks <= 0 || (limit != null && !(limit.isJsonPrimitive() && limit.getAsJsonPrimitive().isNumber())))
                throw new IllegalArgumentException("max_ticks must be a positive 64-bit integer");
            var option = program.get("include_screenshot");
            if (option != null && !(option.isJsonPrimitive() && option.getAsJsonPrimitive().isBoolean()))
                throw new IllegalArgumentException("include_screenshot must be a boolean");
            includeScreenshot = option == null || option.getAsBoolean();
        }
    }

    static boolean supports(Minecraft mc) {
        return mc.level == null || (mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished());
    }

    Snapshot snapshot(Minecraft mc) {
        settleRequest(mc);
        if (!supports(mc)) throw new IllegalStateException("requires a local single-player world or menu");
        return new Snapshot(mc.level, level == mc.level ? queue.persistent() : Set.of());
    }

    InputState inspect(Minecraft mc) {
        settleRequest(mc);
        if (!supports(mc)) throw new IllegalStateException("requires a local single-player world or menu");
        boolean sameLevel = level == mc.level;
        String world = worldState(mc);
        if (!sameLevel) return new InputState(world, new Held(Map.of(), Map.of()), List.of(), DEFAULT_MAX_SPEED,
            null, null, null, List.of(), List.of());
        return new InputState(world, new Held(keys.inspect(queue.persistent()), mouse.inspectButtons()),
            mouse.inspectMoves(), maxSpeed, nativeEndReleased(mc) ? null : pauseReason, lastError,
            queue.activeCalls(), queue.listeners(), queue.inspect());
    }

    void start(Minecraft mc, Snapshot snapshot, JsonObject program, CompletableFuture<Observation> result) {
        if (result.isDone()) return;
        checkContext(mc);
        if (!screenshotPaths.isEmpty() || capturing) throw new IllegalStateException("a frame capture is completing");
        if (active != null || snapshot.level != mc.level || !supports(mc))
            throw new IllegalStateException("game context changed; inspect the game before resubmitting");
        if (!program.has("queue_version") && !queue.persistent().equals(snapshot.persistent))
            throw new IllegalStateException("keyboard state changed during legacy compilation; resubmit the block");
        var resolved = new HashMap<String,Integer>();
        program.getAsJsonObject("keys").entrySet().forEach(entry -> {
            try { resolved.put(entry.getKey(), KeyDispatch.resolve(entry.getKey())); }
            catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("line " + entry.getValue().getAsInt() + ": " + error.getMessage());
            }
        });
        // Legacy target sets can include keys held at handshake, even if manual play has since released them.
        for (var name : snapshot.persistent) resolved.computeIfAbsent(name, KeyDispatch::resolve);
        double cap = program.has("max_speed") ? program.get("max_speed").getAsDouble() : DEFAULT_MAX_SPEED;
        if (!Double.isFinite(cap) || cap <= 0) throw new IllegalArgumentException("max_speed must be finite and positive");
        var block = InputQueue.Block.read(program, resolved.keySet());
        var request = new Request(program, result);
        if (result.isDone()) return;
        queue.submit(block);
        codes.putAll(resolved);
        maxSpeed = cap;
        active = request;
        resume(mc);
    }

    String worldState(Minecraft mc) {
        if (mc.level == null || mc.gui.isPausing()) return "not_needed";
        if (level == mc.level && frozen() && mc.isPaused()) return "paused";
        return mc.isPaused() ? "not_needed" : "unpaused";
    }

    public boolean frozen() {
        var wait = transition;
        return freeze && (wait == null || !wait.abandoned());
    }
    public boolean worldActionsPaused(Minecraft mc) {
        // After resume(), vanilla's pause flag can survive until the next frame.
        return mc.level != null && (frozen() || (running && mc.isPaused() && !mc.gui.isPausing()));
    }
    public boolean showPauseIndicator(Minecraft mc) {
        return pauseConfirmed && frozen() && level == mc.level && mc.isPaused() && !mc.gui.isPausing();
    }

    private boolean nativeEndReleased(Minecraft mc) {
        return endInNativeMenu && !freeze && !mc.gui.isPausing();
    }

    public boolean keyDown(int code) { return keys.isDown(code); }

    private void settleRequest(Minecraft mc) {
        var wait = transition;
        if (wait == null) {
            if (active != null && active.result.isCompletedExceptionally()) reset(mc, "input request ended");
            return;
        }
        if (wait.abandoned()) {
            reset(mc, "input request ended");
        } else if (wait.deliveryConfirmed()) {
            active = null;
            transition = null;
            if (!freeze) pauseReason = null;
            if (resumeAfterCapture) resume(mc);
        }
    }

    public void checkContext(Minecraft mc) {
        settleRequest(mc);
        if (transition != null) {
            if (!supports(mc)) {
                reset(mc, "world changed during input session");
            } else {
                if (mc.level != level) {
                    releaseTransitionPause();
                    level = mc.level;
                    contactPlayer = null;
                }
                return;
            }
        }
        if (mc.level != level || ((running || pauseReason != null || active != null) && !supports(mc))) {
            reset(mc, "world changed during input session");
            level = mc.level;
            contactPlayer = null;
        } else if (endInNativeMenu && !mc.gui.isPausing()) {
            endInNativeMenu = false;
            if (!freeze) resume(mc);
        }
    }

    void expireTransition(CompletableFuture<Observation> result) {
        var wait = transition;
        if (wait != null) wait.expire(result, System.nanoTime());
    }

    void finishDelivery(CompletableFuture<Observation> result, boolean delivered) {
        if (!delivered) result.cancel(false);
        var wait = transition;
        if (wait != null) wait.finishDelivery(result, delivered);
    }

    private void reset(Minecraft mc, String reason) {
        var old = active;
        active = null;
        transition = null;
        clearInput(mc);
        pauseReason = null;
        if (old != null) old.result.completeExceptionally(new IllegalStateException(reason));
    }

    private void clearInput(Minecraft mc) {
        running = inTick = appliedInput = hotkeyStop = resumeAfterCapture = false;
        simulatingPlayer = false;
        inputGeneration++;
        freeze = pauseConfirmed = endInNativeMenu = false;
        lastError = null;
        pauseListeners = List.of();
        screenshotPaths = List.of();
        renderReady = capturing = false;
        captureGeneration++;
        fence.set(null);
        queue.reset();
        codes.clear();
        maxSpeed = DEFAULT_MAX_SPEED;
        mouse.reset(mc, keys.modifiers());
        keys.apply(mc, Set.of());
    }

    /** Claims a native operation invoked during input dispatch or the resulting vanilla tick. */
    public long beginWorldTransition(Minecraft mc, String direction) {
        var request = active;
        if (transition != null || request == null || request.result.isDone() || !appliedInput) return 0;
        var wait = new WorldTransition(++nextTransitionToken, direction, request.result, System.nanoTime());
        transition = wait;
        request.executed++;
        pauseReason = "world_changed";
        toggles.clearForWorldChange();
        clearInput(mc);
        return wait.token;
    }

    public boolean transitionActive(long token) {
        var wait = transition;
        return wait != null && wait.accepts(token, System.nanoTime());
    }

    public void transitionOutcome(long token, String status) {
        transitionOutcome(token, status, null);
    }

    public void transitionOutcome(long token, String status, Screen expectedScreen) {
        var wait = transition;
        if (wait == null || !wait.signal(token, status, System.nanoTime())) return;
        wait.expectedScreen = expectedScreen;
        if (wait.direction.equals("enter") && wait.nativeCompleted()) {
            wait.entryServer = Minecraft.getInstance().getSingleplayerServer();
        }
    }

    private void releaseTransitionPause() {
        freeze = pauseConfirmed = renderReady = false;
        fence.set(null);
        nextMarker++;
    }

    /** Called on the game thread for a physical or scripted press of the configured binding. */
    public void togglePause(Minecraft mc) {
        checkContext(mc);
        if (!supports(mc)) return;
        if (pauseReason != null) {
            // The outstanding peer must receive the confirmed frame even if the player resumes immediately.
            if (active != null || !screenshotPaths.isEmpty()) resumeAfterCapture = !resumeAfterCapture;
            else resume(mc);
        } else if (inTick) {
            hotkeyStop = !hotkeyStop;
        } else {
            stop(mc, "hotkey");
        }
    }

    private void resume(Minecraft mc) {
        running = true;
        renderReady = false;
        freeze = pauseConfirmed = endInNativeMenu = resumeAfterCapture = false;
        pauseReason = null;
        lastError = null;
        fence.set(null);
        resolveControls(mc);
    }

    public long observe(Minecraft mc, String event) {
        return observe(mc, event, null);
    }

    /** Called on the client thread after an event is observed locally or confirmed by the server. */
    public long observe(Minecraft mc, String event, JsonObject fields) {
        if (mc.level != level || (mc.level == null && !event.equals("ScreenOpened")) || !supports(mc)) return 0;
        long observation = queue.observe(event, fields, active == null ? null : active.events);
        if (active != null && event.equals("Death") && queue.foregroundObserved(observation))
            active.deathObservation = observation;
        return observation;
    }

    public void afterDeathRespawn(Minecraft mc, ClientLevel previousLevel, long observation) {
        if (level != previousLevel || mc.level == null || mc.level == previousLevel || !supports(mc)) return;
        if (observation == 0 || (active != null && active.deathObservation != observation)) return;
        if (!queue.hasObservation(observation) && (active == null || running)) return;
        queue.retainObservation(observation);
        level = mc.level;
        contactPlayer = null;
        mouse.reset(mc, keys.modifiers());
        keys.apply(mc, Set.of());
    }

    public void collision(Minecraft mc, LocalPlayer player, Vec3 requested, Vec3 allowed) {
        if (!simulatingPlayer || player != contactPlayer || player != mc.player) return;
        int blocked = 0;
        if (!Mth.equal(requested.x, allowed.x)) blocked |= CONTACT_X;
        if (!Mth.equal(requested.z, allowed.z)) blocked |= CONTACT_Z;
        // Several move calls can contribute to one simulation tick.
        int entered = blocked & ~(previousContacts | currentContacts);
        currentContacts |= blocked;
        if ((entered & CONTACT_X) != 0) observe(mc, "CollideX");
        if ((entered & CONTACT_Z) != 0) observe(mc, "CollideZ");
    }

    public boolean beforeTick(Minecraft mc) {
        checkContext(mc);
        if (running && queue.hasObserved()) resolveControls(mc);
        if (frozen() && !mc.isPaused()) return false; // Suppress catch-up ticks in the stopping frame.
        if (mc.isPaused() && !mc.gui.isPausing()) return true;
        if (!running || (queue.isEmpty() && keys.isEmpty() && !mouse.active())) {
            beginSimulationTick(mc);
            return true;
        }
        InputQueue.Tick input;
        try {
            input = queue.input(name -> codes.computeIfAbsent(name, KeyDispatch::resolve),
                rows -> mouse.prepare(mc, rows, maxSpeed));
        } catch (InputQueue.ValueFailure failure) {
            inTick = simulatingPlayer = false;
            stopWithError(mc, failure);
            return false;
        }
        beginSimulationTick(mc);
        var request = active;
        long generation = ++inputGeneration;
        BooleanSupplier valid = () -> appliedInput && inputGeneration == generation
            && (request == null || !request.result.isDone());
        appliedInput = true;
        try {
            var target = new TreeSet<Integer>();
            for (var name : input.held()) target.add(codes.get(name));
            boolean prepared = input.prepared() != null;
            if (prepared && valid.getAsBoolean()) mouse.applyPreparedMotion(mc, input.prepared(), valid);
            if (valid.getAsBoolean()) keys.apply(mc, target, valid);
            if (valid.getAsBoolean()) mouse.tick(mc, input.mouse(), prepared, maxSpeed, keys.modifiers(), valid);
        } catch (RuntimeException error) {
            if (valid.getAsBoolean()) reset(mc, "line " + input.line() + ": " + error.getMessage());
            else if (request != null) request.result.completeExceptionally(error);
        }
        return true;
    }

    private void beginSimulationTick(Minecraft mc) {
        simulatingPlayer = !mc.isPaused() && mc.player != null;
        if (simulatingPlayer) {
            previousContacts = contactPlayer == mc.player ? currentContacts : 0;
            currentContacts = 0;
            contactPlayer = mc.player;
        }
        inTick = true;
    }

    public void afterTick(Minecraft mc) {
        simulatingPlayer = false;
        if (active != null && active.result.isCompletedExceptionally()) {
            checkContext(mc);
            return;
        }
        if (!inTick) return;
        inTick = false;
        if (appliedInput) {
            appliedInput = false;
            queue.advance();
            if (active != null) active.executed++;
            resolveControls(mc);
        } else if (hotkeyStop) {
            hotkeyStop = false;
            stop(mc, "hotkey");
        }
    }

    private void resolveControls(Minecraft mc) {
        String boundary;
        try {
            var request = active;
            boundary = queue.controls(mouse::pending, mouse::clearMoves,
                request == null ? ignored -> {} : request::print);
            var value = queue.takeReturnValue();
            if (request != null && value != null) request.returnValue = value;
        } catch (InputQueue.ValueFailure failure) {
            stopWithError(mc, failure);
            return;
        }
        if (hotkeyStop) {
            hotkeyStop = false;
            if (queue.pauseListeners().isEmpty()) boundary = "hotkey";
        }
        screenshotPaths = queue.takeScreenshots();
        if (boundary == null && active != null && active.executed >= active.maxTicks) boundary = "max_ticks";
        if (boundary == null && !screenshotPaths.isEmpty()) boundary = "capture";
        if (boundary != null) stop(mc, boundary);
    }

    private void stopWithError(Minecraft mc, InputQueue.ValueFailure failure) {
        lastError = failure.getMessage();
        if (active != null) {
            var stack = failure.call_stack();
            active.error = new ScriptError(lastError, failure.block(), failure.file(), failure.line(),
                stack == null ? List.of() : stack);
            active.returnValue = queue.takeReturnValue();
        }
        hotkeyStop = false;
        screenshotPaths = queue.takeScreenshots();
        stop(mc, "error");
    }

    private void stop(Minecraft mc, String reason) {
        running = false;
        pauseReason = reason;
        pauseListeners = reason.equals("pause") ? queue.pauseListeners() : List.of();
        renderReady = false;
        captureGeneration++;
        freeze = mc.level != null;
        endInNativeMenu = freeze && reason.equals("end") && mc.gui.isPausing();
        pauseConfirmed = false;
        if (mc.level != null) fence.set(new Fence(mc.getSingleplayerServer(), ++nextMarker));
        else renderReady = true;
    }

    public void serverTick(IntegratedServer server) {
        var stop = fence.get();
        if (stop == null || server != stop.server || !server.isPaused()) return;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var player = players.getFirst();
        player.containerMenu.broadcastChanges();
        var food = player.getFoodData();
        player.connection.send(new ClientboundSetHealthPacket(player.getHealth(), food.getFoodLevel(), food.getSaturationLevel()));
        player.connection.send(new ClientboundPingPacket(stop.id));
        fence.compareAndSet(stop, null);
    }

    public void marker(int id) {
        if (frozen() && nextMarker == id) {
            pauseConfirmed = true;
            renderReady = true;
        }
    }

    private void afterTransitionRender(Minecraft mc, WorldTransition wait, Request request) {
        long now = System.nanoTime();
        wait.expire(request.result, now);
        if (wait.observed || wait.result.isDone()) return;
        boolean entering = wait.direction.equals("enter");
        var screen = mc.gui.screen();
        boolean visible = mc.gui.overlay() == null;
        if (entering && wait.nativeCompleted() && screen instanceof DisconnectedScreen) {
            wait.signal(wait.token, "failed", now);
        }
        boolean ready;
        if (entering) {
            var connection = mc.getConnection();
            ready = visible && screen == null && mc.level != null && mc.player != null && mc.gameMode != null
                && connection != null && connection.hasClientLoaded() && connection.getLevel() == mc.level
                && connection.getConnection().isConnected()
                && wait.entryServer != null && wait.entryServer == mc.getSingleplayerServer();
        } else {
            ready = visible && screen instanceof TitleScreen && mc.level == null && mc.player == null
                && mc.getSingleplayerServer() == null && (wait.expectedScreen == null || screen == wait.expectedScreen);
        }
        String status = wait.frameStatus(now, ready && (!entering || (freeze && renderReady && mc.isPaused())),
            visible && (wait.expectedScreen == null || screen == wait.expectedScreen));
        if (status == null) {
            if (entering && ready && wait.nativeCompleted() && !freeze) stop(mc, "world_changed");
            else if (!ready && freeze) releaseTransitionPause();
            return;
        }
        if (!status.equals("completed") && freeze) {
            releaseTransitionPause();
            wait.releasingPause = true;
        }
        // runTick updates vanilla's pause flag after rendering. Wait for a frame after that update.
        if (wait.releasingPause && mc.level != null && mc.isPaused() && !mc.gui.isPausing()) return;
        wait.observed = true;
        String world = worldState(mc);
        renderReady = false;
        var events = request.events.seal();
        var report = new Transition(wait.direction, status, true);
        String inputScreen = GameInspection.screenName(mc);
        Output output = request.output();
        if (!request.includeScreenshot) {
            request.result.complete(new Observation(request.executed, world, "world_changed", List.of(), events,
                null, null, report, inputScreen, output));
            return;
        }
        // Native loading and saving can discard queued client tasks. Only the frozen frame data crosses threads.
        long ticks = request.executed;
        try {
            var camera = FrameCamera.snapshot(mc);
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                if (request.result.isDone()) {
                    image.close();
                    return;
                }
                try {
                    CompletableFuture.runAsync(() -> {
                        try (image) {
                            if (!request.result.isDone()) {
                                byte[] png = FrameFiles.encode(image, List.of());
                                request.result.complete(new Observation(ticks, world, "world_changed", List.of(),
                                    events, png, camera, report, inputScreen, output));
                            }
                        } catch (IOException | RuntimeException failure) {
                            request.result.completeExceptionally(failure);
                        }
                    });
                } catch (RuntimeException failure) {
                    image.close();
                    request.result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            request.result.completeExceptionally(failure);
        }
    }

    public void afterWorldTransitionRender(Minecraft mc) {
        if (transition == null) return;
        checkContext(mc);
        if (transition != null) afterTransitionRender(mc, transition, active);
    }

    public void afterRender(Minecraft mc) {
        if (transition != null) return;
        checkContext(mc);
        var r = active;
        if (r == null && screenshotPaths.isEmpty()) {
            if (endInNativeMenu && pauseConfirmed) freeze = false;
            return;
        }
        if (!renderReady || (freeze && !mc.isPaused()) || capturing) return;
        renderReady = false;
        String world = worldState(mc);
        String reason = pauseReason;
        boolean intermediate = "capture".equals(reason);
        // Close the window at the stop frame before starting any readback and encoding.
        var events = r != null && !intermediate ? r.events.seal() : null;
        boolean returnImage = r != null && r.includeScreenshot && !intermediate;
        if (!returnImage && screenshotPaths.isEmpty()) {
            completeStop(mc, r, world, reason, events, null, null);
            return;
        }
        final long generation = captureGeneration;
        var paths = screenshotPaths;
        try {
            capturing = true;
            var camera = returnImage ? FrameCamera.snapshot(mc) : null;
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                CompletableFuture.supplyAsync(() -> {
                    try (image) {
                        return FrameFiles.encode(image, paths);
                    } catch (IOException error) {
                        throw new UncheckedIOException(error);
                    }
                }).whenComplete((png, failure) -> mc.execute(() -> {
                    if (captureGeneration != generation) return;
                    capturing = false;
                    screenshotPaths = List.of();
                    if (failure != null) captureFailed(mc, world, events, failure);
                    else if (intermediate) {
                        if (resumeAfterCapture) {
                            resumeAfterCapture = false;
                            stop(mc, "hotkey");
                        }
                        else resume(mc);
                    } else completeStop(mc, r, world, reason, events, returnImage ? png : null, camera);
                }));
            });
        } catch (RuntimeException error) {
            captureFailed(mc, world, events, error);
        }
    }

    private void captureFailed(Minecraft mc, String world, EventJournal.Report events, Throwable failure) {
        var request = active;
        screenshotPaths = List.of();
        capturing = resumeAfterCapture = false;
        if ("capture".equals(pauseReason)) pauseReason = "pause";
        String message = "screenshot failed: " + failure;
        lastError = lastError == null ? message : lastError + "; " + message;
        if (request != null) {
            request.captureError = message;
            completeStop(mc, request, world, pauseReason, events == null ? request.events.seal() : events, null, null);
        } else {
            LoggerFactory.getLogger("minecraft-agent").warn("Frame capture failed", failure);
        }
    }

    private void completeStop(Minecraft mc, Request r, String world, String reason, EventJournal.Report events,
                              byte[] png, FrameCamera.View camera) {
        active = null;
        if (endInNativeMenu && world.equals("not_needed") && mc.gui.isPausing()) freeze = false;
        if (r != null) {
            r.result.complete(new Observation(r.executed, world, reason, pauseListeners, events, png, camera,
                null, null, r.output()));
        }
        if (resumeAfterCapture) resume(mc);
    }
}
