package mcagent;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;

/** Samples client state and optionally reads the same frame's pixels. */
public final class GameInspection {
    record Observation(JsonObject state, byte[] png, FrameCamera.View camera) {}
    private record Request(boolean includeScreenshot, CompletableFuture<Observation> result) {}

    // Minecraft drops queued client tasks during disconnect, but keeps drawing transition frames.
    private static final AtomicReference<Request> PENDING = new AtomicReference<>();

    static CompletableFuture<Observation> request(boolean includeScreenshot) {
        var result = new CompletableFuture<Observation>();
        var request = new Request(includeScreenshot, result);
        if (!PENDING.compareAndSet(null, request)) throw new IllegalStateException("a game inspection is pending");
        result.whenComplete((frame, failure) -> PENDING.compareAndSet(request, null));
        return result;
    }

    public static void afterRender(Minecraft mc) {
        var request = PENDING.get();
        if (request == null || !PENDING.compareAndSet(request, null) || request.result().isDone()) return;
        var result = request.result();
        try {
            if (!Playback.supports(mc))
                throw new IllegalStateException("requires a local single-player world or menu");
            var state = sample(mc);
            if (!request.includeScreenshot()) {
                result.complete(new Observation(state, null, null));
                return;
            }
            var camera = FrameCamera.snapshot(mc);
            Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> encode(image, state, camera, result));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private static void encode(NativeImage image, JsonObject state, FrameCamera.View camera,
                               CompletableFuture<Observation> result) {
        if (result.isDone()) {
            image.close();
            return;
        }
        try {
            CompletableFuture.runAsync(() -> {
                try (image) {
                    if (!result.isDone()) result.complete(new Observation(state, FrameFiles.encode(image, List.of()), camera));
                } catch (IOException | RuntimeException failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException failure) {
            image.close();
            result.completeExceptionally(failure);
        }
    }

    private static JsonObject sample(Minecraft mc) {
        var overlay = mc.gui.overlay();
        var screen = mc.gui.screen();
        String inputScreen = overlay != null ? overlay.getClass().getSimpleName()
            : screen != null ? screen.getClass().getSimpleName() : null;
        boolean hasPlayer = mc.level != null && mc.player != null;
        var state = new JsonObject();
        state.addProperty("world", Playback.INSTANCE.worldState(mc));
        state.addProperty("screen", inputScreen);
        state.add("player", hasPlayer ? player(mc.player) : JsonNull.INSTANCE);
        state.add("target", target(mc, hasPlayer, inputScreen != null));
        return state;
    }

    private static JsonObject player(LocalPlayer player) {
        var state = new JsonObject();
        state.addProperty("dimension", player.level().dimension().identifier().toString());
        var position = player.position();
        state.add("position", point(position.x(), position.y(), position.z()));
        var rotation = new JsonObject();
        rotation.addProperty("yaw", player.getYRot());
        rotation.addProperty("pitch", player.getXRot());
        state.add("rotation", rotation);
        state.add("main_hand", item(player.getMainHandItem()));
        state.add("off_hand", item(player.getOffhandItem()));
        var health = new JsonObject();
        health.addProperty("current", player.getHealth());
        health.addProperty("max", player.getMaxHealth());
        health.addProperty("absorption", player.getAbsorptionAmount());
        state.add("health", health);
        state.addProperty("food", player.getFoodData().getFoodLevel());
        var air = new JsonObject();
        air.addProperty("current", player.getAirSupply());
        air.addProperty("max", player.getMaxAirSupply());
        state.add("air", air);
        return state;
    }

    private static JsonElement item(ItemStack stack) {
        if (stack.isEmpty()) return JsonNull.INSTANCE;
        var item = new JsonObject();
        item.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        item.addProperty("count", stack.getCount());
        return item;
    }

    private static JsonObject point(Number x, Number y, Number z) {
        var point = new JsonObject();
        point.addProperty("x", x);
        point.addProperty("y", y);
        point.addProperty("z", z);
        return point;
    }

    private static JsonObject target(Minecraft mc, boolean hasPlayer, boolean screenOpen) {
        if (!hasPlayer) return unavailable("no_world");
        if (screenOpen) return unavailable("screen_open");
        var hit = mc.hitResult;
        if (hit == null) return unavailable("no_hit_result");
        var target = new JsonObject();
        switch (hit.getType()) {
            case MISS -> target.addProperty("kind", "miss");
            case BLOCK -> {
                var block = (BlockHitResult) hit;
                var position = block.getBlockPos();
                var id = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(position).getBlock());
                target.addProperty("kind", "block");
                target.addProperty("id", id.toString());
                target.add("position", point(position.getX(), position.getY(), position.getZ()));
                target.addProperty("face", block.getDirection().getName());
            }
            case ENTITY -> {
                var entity = ((EntityHitResult) hit).getEntity();
                target.addProperty("kind", "entity");
                target.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                target.addProperty("entity_id", entity.getId());
            }
        }
        return target;
    }

    private static JsonObject unavailable(String reason) {
        var target = new JsonObject();
        target.addProperty("kind", "unavailable");
        target.addProperty("reason", reason);
        return target;
    }
}
