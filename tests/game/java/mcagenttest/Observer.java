package mcagenttest;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDLKeyboard;
import org.lwjgl.sdl.SDLVideo;
import static org.lwjgl.sdl.SDLEvents.*;

public final class Observer implements ClientModInitializer {
    public static volatile boolean recording, slow, measureWorld;
    public static long frame;

    private void await(BooleanSupplier condition) throws Exception {
        await(condition, 100);
    }

    private void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("setup timed out");
            Thread.sleep(25);
        }
    }

    private static void pushKey(Minecraft mc, int code, boolean down, boolean repeat) {
        try (var event = SDL_Event.calloc()) {
            short modifiers = SDLKeyboard.SDL_GetModState();
            event.key().type(down ? SDL_EVENT_KEY_DOWN : SDL_EVENT_KEY_UP)
                .windowID(SDLVideo.SDL_GetWindowID(mc.getWindow().handle()))
                .scancode(code).key(SDLKeyboard.SDL_GetKeyFromScancode(code, modifiers, false))
                .mod(modifiers).down(down).repeat(repeat);
            if (!SDL_PushEvent(event)) throw new IllegalStateException("SDL rejected test key");
        }
    }

    private void observerInput(Minecraft mc) {
        var request = Path.of("observer-input");
        if (!Files.exists(request)) return;
        try {
            var command = Files.readString(request).strip().split(" ");
            var key = InputConstants.getKey("key.keyboard." + command[1]);
            var controls = mc.submit(() -> {
                if (command[0].equals("bind")) {
                    var binding = java.util.Arrays.stream(mc.options.keyMappings)
                        .filter(k -> k.getName().equals("key.minecraft_agent.pause")).findFirst().orElseThrow();
                    if (!I18n.get(binding.getName()).equals("Pause / Resume Minecraft Agent"))
                        throw new AssertionError("missing Controls label: " + I18n.get(binding.getName()));
                    var screen = new KeyBindsScreen(mc.gui.screen(), mc.options);
                    mc.gui.setScreen(screen);
                    screen.selectedKey = binding;
                    pushKey(mc, key.getValue(), true, false);
                    pushKey(mc, key.getValue(), false, false);
                    return screen;
                }
                switch (command[0]) {
                    case "press" -> pushKey(mc, key.getValue(), true, false);
                    case "repeat" -> pushKey(mc, key.getValue(), true, true);
                    case "release" -> pushKey(mc, key.getValue(), false, false);
                    default -> throw new IllegalArgumentException("unknown observer input " + command[0]);
                }
                return null;
            }).join();
            await(() -> mc.submit(() -> !SDL_HasEvents(SDL_EVENT_KEY_DOWN, SDL_EVENT_KEY_UP)).join(), 3);
            if (controls != null) mc.submit(() -> {
                var binding = KeyMapping.get("key.minecraft_agent.pause");
                if (controls.selectedKey != null || !binding.matches(key))
                    throw new AssertionError("Controls did not bind " + key);
                controls.onClose(); // The vanilla screen saves options when removed.
                binding.setKey(InputConstants.UNKNOWN);
                mc.options.load();
                if (!binding.matches(key)) throw new AssertionError("Options did not restore " + key);
            }).join();
            Files.delete(request);
        } catch (Exception error) {
            throw new IllegalStateException("observer input failed", error);
        }
    }

    @Override public void onInitializeClient() {
        Thread.ofPlatform().daemon().start(() -> {
            var mc = Minecraft.getInstance();
            try {
                await(() -> mc.submit(() -> mc.gui != null && mc.gui.screen() != null && mc.gui.overlay() == null).join());
                mc.submit(() -> {
                    mc.options.pauseOnLostFocus = false;
                    mc.options.keyLeft.setKey(InputConstants.getKey("key.keyboard.k"));
                    KeyMapping.resetMapping();
                    var settings = new LevelSettings("Keyboard test", GameType.SURVIVAL,
                        LevelSettings.DifficultySettings.DEFAULT, true, WorldDataConfiguration.DEFAULT);
                    mc.createWorldOpenFlows().createFreshLevel("test", settings, new WorldOptions(123, false, false),
                        WorldPresets::createTestWorldDimensions, mc.gui.screen());
                }).join();
                await(() -> mc.submit(() -> mc.player != null && mc.player.onGround() && mc.gui.screen() == null).join());
                var server = mc.submit(mc::getSingleplayerServer).join();
                var landmark = server.submit(() -> {
                    var player = server.getPlayerList().getPlayers().getFirst();
                    var pos = player.blockPosition().above().south(5).east(2);
                    player.level().setBlockAndUpdate(pos, Blocks.REDSTONE_BLOCK.defaultBlockState());
                    player.level().setBlockAndUpdate(player.blockPosition().above().south(3).west(), Blocks.CHEST.defaultBlockState());
                    player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
                    player.inventoryMenu.broadcastChanges();
                    return pos;
                }).join();
                await(() -> mc.submit(() -> mc.level.getBlockState(landmark).is(Blocks.REDSTONE_BLOCK)
                    && mc.mouseHandler.isMouseGrabbed() && mc.isWindowActive()).join());
                mc.submit(() -> {
                    mc.player.setYRot(0);
                    mc.player.setXRot(0);
                    mc.options.bobView().set(false);
                    mc.options.sensitivity().set(0.5);
                }).join();
                Thread.sleep(800);
                recording = true;
                Files.createFile(Path.of("ready"));
                await(() -> {
                    observerInput(mc);
                    if (Files.exists(Path.of("inspect-player"))) {
                        String state = mc.submit(() -> {
                            var p = mc.player;
                            boolean deathScreen = mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen;
                            return p.getX() + " " + p.getZ() + " " + (p.getZ() - p.zo) + " " + deathScreen + " " + p.isDeadOrDying();
                        }).join();
                        try {
                            Files.writeString(Path.of("player-state"), state);
                            Files.delete(Path.of("inspect-player"));
                        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    }
                    if (Files.exists(Path.of("request-respawn"))) {
                        mc.submit(() -> mc.player.respawn()).join();
                        try { Files.delete(Path.of("request-respawn")); }
                        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    }
                    if (Files.exists(Path.of("reset-view")) && !Files.exists(Path.of("view-reset"))) {
                        mc.submit(() -> { mc.player.setXRot(0); mc.player.setYRot(0); }).join();
                        try { Files.createFile(Path.of("view-reset")); }
                        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    }
                    measureWorld = Files.exists(Path.of("measure-world"));
                    slow = Files.exists(Path.of("slow"));
                    return Files.exists(Path.of("done"));
                }, 450);
            } catch (Throwable e) { e.printStackTrace(); }
            finally { mc.execute(mc::stop); }
        });
    }
}
