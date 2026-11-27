package mcagent;

import java.nio.file.Path;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.resources.Identifier;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonObject;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;

/** Playback belongs to the client thread. Only the stop fence crosses to the server thread. */
public final class Playback {
    public static final Playback INSTANCE = new Playback();
    public static final Identifier HURT_SIGNAL = Identifier.fromNamespaceAndPath("minecraft_agent", "hurt");
    private static final double DEFAULT_MAX_SPEED = 50;
    private final KeyDispatch keys = new KeyDispatch();
    private final MouseDispatch mouse = new MouseDispatch();
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
    record Observation(long ticks, String world, String pauseReason, List<InputQueue.Origin> listeners,
                       byte[] png, FrameCamera.View camera) {}
    record Held(Map<String,String> keys, Map<String,String> buttons) {}
    record InputState(String world, Held held, List<Movement.Pending<?>> moves, double max_speed_px_per_tick,
                      String pause_reason, String last_error, List<InputQueue.ActiveCall> active_calls,
                      List<InputQueue.ListenerState> listeners,
                      List<InputQueue.Entry> queue_entries) {}

    private record Fence(IntegratedServer server, int id) {}
    private static final class Request {
        final CompletableFuture<Observation> result = new CompletableFuture<>();
        final boolean includeScreenshot;
        long executed;
        long deathObservation;

        Request(JsonObject program) {
            var option = program.get("include_screenshot");
            if (option != null && !(option.isJsonPrimitive() && option.getAsJsonPrimitive().isBoolean()))
                throw new IllegalArgumentException("include_screenshot must be a boolean");
            includeScreenshot = option == null || option.getAsBoolean();
        }
    }

    private static boolean supports(Minecraft mc) {
        return mc.level == null || (mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished());
    }

    Snapshot snapshot(Minecraft mc) {
        if (!supports(mc)) throw new IllegalStateException("requires a local single-player world or menu");
        return new Snapshot(mc.level, level == mc.level ? queue.persistent() : Set.of());
    }

    InputState inspect(Minecraft mc) {
        if (!supports(mc)) throw new IllegalStateException("requires a local single-player world or menu");
        boolean sameLevel = level == mc.level;
        String world = worldState(mc);
        if (!sameLevel) return new InputState(world, new Held(Map.of(), Map.of()), List.of(), DEFAULT_MAX_SPEED,
            null, null, null, List.of(), List.of());
        return new InputState(world, new Held(keys.inspect(queue.persistent()), mouse.inspectButtons()),
            mouse.inspectMoves(), maxSpeed, nativeEndReleased(mc) ? null : pauseReason, lastError,
            queue.activeCalls(), queue.listeners(), queue.inspect());
    }

    CompletableFuture<Observation> start(Minecraft mc, Snapshot snapshot, JsonObject program) {
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
        var request = new Request(program);
        queue.submit(block);
        codes.putAll(resolved);
        maxSpeed = cap;
        active = request;
        resume(mc);
        return request.result;
    }

    private String worldState(Minecraft mc) {
        if (mc.level == null || mc.gui.isPausing()) return "not_needed";
        if (level == mc.level && freeze && mc.isPaused()) return "paused";
        return mc.isPaused() ? "not_needed" : "unpaused";
    }

    public boolean frozen() { return freeze; }
    public boolean showPauseIndicator(Minecraft mc) {
        return pauseConfirmed && freeze && level == mc.level && mc.isPaused() && !mc.gui.isPausing();
    }

    private boolean nativeEndReleased(Minecraft mc) {
        return endInNativeMenu && !freeze && !mc.gui.isPausing();
    }

    public boolean keyDown(int code) { return keys.isDown(code); }

    public void checkContext(Minecraft mc) {
        if (mc.level != level || ((running || pauseReason != null || active != null) && !supports(mc))) {
            reset(mc, "world changed during input session");
            level = mc.level;
            contactPlayer = null;
        } else if (endInNativeMenu && !mc.gui.isPausing()) {
            endInNativeMenu = false;
            if (!freeze) resume(mc);
        }
    }

    void cancel(Minecraft mc, CompletableFuture<Observation> result) {
        if (active != null && active.result == result) reset(mc, "client disconnected");
    }

    private void reset(Minecraft mc, String reason) {
        var old = active;
        active = null;
        running = inTick = appliedInput = hotkeyStop = resumeAfterCapture = false;
        freeze = pauseConfirmed = endInNativeMenu = false;
        pauseReason = null;
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
        if (old != null) old.result.completeExceptionally(new IllegalStateException(reason));
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
        if (mc.level == null || mc.level != level || !supports(mc)) return 0;
        long observation = queue.observe(event);
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
        if (freeze && !mc.isPaused()) return false; // Suppress catch-up ticks in the stopping frame.
        if (mc.isPaused() && !mc.gui.isPausing()) return true;
        simulatingPlayer = !mc.isPaused() && mc.player != null;
        if (simulatingPlayer) {
            previousContacts = contactPlayer == mc.player ? currentContacts : 0;
            currentContacts = 0;
            contactPlayer = mc.player;
        }
        inTick = true;
        if (!running || (queue.isEmpty() && keys.isEmpty() && !mouse.active())) return true;
        var input = queue.input();
        try {
            var target = new TreeSet<Integer>();
            for (var name : input.held()) target.add(codes.get(name));
            keys.apply(mc, target);
            mouse.tick(mc, input.mouse(), maxSpeed, keys.modifiers());
            appliedInput = true;
        } catch (RuntimeException error) {
            reset(mc, "line " + input.line() + ": " + error.getMessage());
        }
        return true;
    }

    public void afterTick(Minecraft mc) {
        simulatingPlayer = false;
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
            boundary = queue.controls(mouse::pending, mouse::clearMoves);
        } catch (InputQueue.FunctionLimit failure) {
            lastError = failure.getMessage();
            hotkeyStop = false;
            screenshotPaths = queue.takeScreenshots();
            stop(mc, "error");
            return;
        }
        if (hotkeyStop) {
            hotkeyStop = false;
            if (queue.pauseListeners().isEmpty()) boundary = "hotkey";
        }
        screenshotPaths = queue.takeScreenshots();
        if (boundary == null && !screenshotPaths.isEmpty()) boundary = "capture";
        if (boundary != null) stop(mc, boundary);
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
        if (pauseReason != null && nextMarker == id) {
            pauseConfirmed = freeze;
            renderReady = true;
        }
    }

    public void afterRender(Minecraft mc) {
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
        boolean returnImage = r != null && r.includeScreenshot && !intermediate && lastError == null;
        if (!returnImage && screenshotPaths.isEmpty()) {
            completeStop(mc, r, world, reason, null, null);
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
                    if (failure != null) captureFailed(mc, failure);
                    else if (intermediate) {
                        if (resumeAfterCapture) {
                            resumeAfterCapture = false;
                            stop(mc, "hotkey");
                        }
                        else resume(mc);
                    } else completeStop(mc, r, world, reason, returnImage ? png : null, camera);
                }));
            });
        } catch (RuntimeException error) {
            captureFailed(mc, error);
        }
    }

    private void captureFailed(Minecraft mc, Throwable failure) {
        var request = active;
        active = null;
        screenshotPaths = List.of();
        capturing = resumeAfterCapture = false;
        if ("capture".equals(pauseReason)) pauseReason = "pause";
        if (lastError != null) {
            lastError += "; screenshot failed: " + failure;
            var combined = new IllegalStateException(lastError);
            combined.addSuppressed(failure);
            failure = combined;
        }
        if (request != null) request.result.completeExceptionally(failure);
        else LoggerFactory.getLogger("minecraft-agent").warn("Frame capture failed", failure);
        if (endInNativeMenu && mc.gui.isPausing()) freeze = false;
    }

    private void completeStop(Minecraft mc, Request r, String world, String reason, byte[] png, FrameCamera.View camera) {
        active = null;
        if (endInNativeMenu && world.equals("not_needed") && mc.gui.isPausing()) freeze = false;
        if (r != null) {
            if (lastError != null) r.result.completeExceptionally(new IllegalStateException(lastError));
            else r.result.complete(new Observation(r.executed, world, reason, pauseListeners, png, camera));
        }
        if (resumeAfterCapture) resume(mc);
    }
}
