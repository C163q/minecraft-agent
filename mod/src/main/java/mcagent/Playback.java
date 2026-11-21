package mcagent;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;

/** Playback state belongs to the client thread. Only the END fence crosses to the server thread. */
public final class Playback {
    public static final Playback INSTANCE = new Playback();
    private final KeyDispatch keys = new KeyDispatch();
    private Set<String> persistent = Set.of();
    private ClientLevel level;
    private Request active;
    private boolean freeze;
    private final AtomicReference<Fence> fence = new AtomicReference<>();
    private int nextMarker = new Random().nextInt();

    record Snapshot(ClientLevel level, Set<String> persistent) {}
    record Observation(long ticks, String world, NativeImage image) {}
    private record Run(long ticks, Set<Integer> held) {}
    private record Fence(IntegratedServer server, int id) {}
    private static final class Request {
        final ArrayDeque<Run> runs = new ArrayDeque<>();
        final CompletableFuture<Observation> result = new CompletableFuture<>();
        Set<String> persistent;
        long remaining, executed;
        boolean inTick, ended, renderReady;
    }

    private static boolean supports(Minecraft mc) {
        return mc.level == null || (mc.hasSingleplayerServer() && !mc.getSingleplayerServer().isPublished());
    }

    Snapshot snapshot(Minecraft mc) {
        checkContext(mc);
        if (!supports(mc)) throw new IllegalStateException("requires a local single-player world or menu");
        return new Snapshot(mc.level, persistent);
    }

    CompletableFuture<Observation> start(Minecraft mc, Snapshot snapshot, JsonObject program) {
        checkContext(mc);
        if (active != null || snapshot.level != mc.level || !supports(mc))
            throw new IllegalStateException("game context changed; inspect the game before resubmitting");
        var codes = new HashMap<String,Integer>();
        program.getAsJsonObject("keys").entrySet().forEach(entry -> {
            try { codes.put(entry.getKey(), KeyDispatch.resolve(entry.getKey())); }
            catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("line " + entry.getValue().getAsInt() + ": " + error.getMessage());
            }
        });
        for (var name : persistent) codes.computeIfAbsent(name, KeyDispatch::resolve);
        var request = new Request();
        for (var item : program.getAsJsonArray("runs")) {
            var run = item.getAsJsonObject();
            long ticks = Long.parseUnsignedLong(run.get("ticks").getAsString());
            if (ticks == 0) throw new IllegalArgumentException("zero length run");
            var target = new TreeSet<Integer>();
            for (var key : run.getAsJsonArray("held")) target.add(Objects.requireNonNull(codes.get(key.getAsString())));
            request.runs.add(new Run(ticks, target));
        }
        var next = new TreeSet<String>();
        for (var key : program.getAsJsonArray("persistent")) {
            String name = key.getAsString();
            if (!codes.containsKey(name)) throw new IllegalArgumentException("unresolved persistent key");
            next.add(name);
        }
        request.persistent = Set.copyOf(next);
        active = request;
        freeze = false;
        if (request.runs.isEmpty()) finish(mc, request);
        return request.result;
    }

    public boolean frozen() { return freeze; }
    public boolean keyDown(int code) { return keys.isDown(code); }

    public void checkContext(Minecraft mc) {
        if (mc.level != level || ((freeze || active != null || !keys.isEmpty()) && !supports(mc))) {
            reset(mc, "world changed during keyboard session");
            level = mc.level;
        }
    }

    void cancel(Minecraft mc, CompletableFuture<Observation> result) {
        if (active != null && active.result == result) reset(mc, "client disconnected");
    }

    private void reset(Minecraft mc, String reason) {
        var old = active;
        active = null;
        freeze = false;
        fence.set(null);
        persistent = Set.of();
        keys.apply(mc, Set.of());
        if (old != null) old.result.completeExceptionally(new IllegalStateException(reason));
    }

    public boolean beforeTick(Minecraft mc) {
        checkContext(mc);
        if (freeze && !mc.isPaused()) return false; // Remaining catch-up ticks in the END frame.
        var r = active;
        if (r == null || r.ended || (mc.isPaused() && !mc.gui.isPausing())) return true;
        var run = r.runs.getFirst();
        if (r.remaining == 0) {
            r.remaining = run.ticks;
            keys.apply(mc, run.held);
        }
        r.inTick = true;
        return true;
    }

    public void afterTick(Minecraft mc) {
        var r = active;
        if (r == null || !r.inTick) return;
        r.inTick = false;
        r.executed++;
        if (--r.remaining == 0) r.runs.removeFirst();
        if (r.runs.isEmpty()) finish(mc, r);
    }

    private void finish(Minecraft mc, Request r) {
        r.ended = true;
        persistent = r.persistent;
        freeze = mc.level != null && !mc.gui.isPausing();
        if (mc.level == null) r.renderReady = true;
        else fence.set(new Fence(mc.getSingleplayerServer(), ++nextMarker));
    }

    public void serverTick(IntegratedServer server) {
        var end = fence.get();
        if (end == null || server != end.server || !server.isPaused()) return;
        var players = server.getPlayerList().getPlayers();
        if (players.isEmpty()) return;
        var player = players.getFirst();
        player.containerMenu.broadcastChanges();
        var food = player.getFoodData();
        player.connection.send(new ClientboundSetHealthPacket(player.getHealth(), food.getFoodLevel(), food.getSaturationLevel()));
        player.connection.send(new ClientboundPingPacket(end.id));
        // Client-side marker matching ignores delayed packets from a cancelled request.
        fence.compareAndSet(end, null);
    }

    public void marker(int id) {
        if (active != null && active.ended && nextMarker == id) active.renderReady = true;
    }

    public void afterRender(Minecraft mc) {
        checkContext(mc);
        var r = active;
        if (r == null || !r.renderReady) return;
        r.renderReady = false;
        String world = freeze ? "paused" : "not_needed";
        try {
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
                mc.execute(() -> {
                    if (active != r) { image.close(); return; }
                    active = null;
                    r.result.complete(new Observation(r.executed, world, image));
                });
            });
        } catch (RuntimeException error) {
            reset(mc, "END capture failed: " + error.getMessage());
        }
    }
}
