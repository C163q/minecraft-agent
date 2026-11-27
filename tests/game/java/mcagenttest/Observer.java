package mcagenttest;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.*;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.multiplayer.ClientLevel;
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
    private final InspectFixture inspectFixture = new InspectFixture();

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

    private void mouseInput(Minecraft mc, String[] command) throws Exception {
        if (command[0].equals("motion")) {
            float dx = Float.parseFloat(command[1]), dy = Float.parseFloat(command[2]);
            long start = mc.submit(() -> {
                try (var event = SDL_Event.calloc()) {
                    event.motion().type(SDL_EVENT_MOUSE_MOTION)
                        .windowID(SDLVideo.SDL_GetWindowID(mc.getWindow().handle()))
                        .x((float)mc.mouseHandler.xpos() + dx).y((float)mc.mouseHandler.ypos() + dy)
                        .xrel(dx).yrel(dy);
                    if (!SDL_PushEvent(event)) throw new IllegalStateException("SDL rejected test motion");
                }
                if (command.length == 4 && command[3].equals("resume")) {
                    int key = InputConstants.getKey("key.keyboard.f8").getValue();
                    pushKey(mc, key, true, false);
                    pushKey(mc, key, false, false);
                }
                return frame;
            }).join();
            await(() -> mc.submit(() -> frame >= start + 3).join(), 3);
        }
        Files.writeString(Path.of("mouse-state"), mc.submit(() ->
            mc.player.getYRot() + " " + mc.player.getXRot() + " " +
            mc.mouseHandler.xpos() + " " + mc.mouseHandler.ypos()).join());
    }

    private void observerInput(Minecraft mc) {
        var request = Path.of("observer-input");
        if (!Files.exists(request)) return;
        try {
            var command = Files.readString(request).strip().split(" ");
            if (command[0].equals("read-text")) {
                Files.writeString(Path.of("focused-text"), mc.submit(() ->
                    mc.gui.screen() != null && mc.gui.screen().getFocused() instanceof EditBox field
                        ? field.getValue() : "<no focused text field>").join());
                Files.delete(request);
                return;
            }
            if (command[0].equals("motion") || command[0].equals("view")) {
                mouseInput(mc, command);
                Files.delete(request);
                return;
            }
            if (command[0].equals("cinematic")) {
                mc.submit(() -> { mc.options.smoothCamera = Boolean.parseBoolean(command[1]); }).join();
                Files.delete(request);
                return;
            }
            if (command[0].equals("inspect-fixture")) {
                inspectFixture.apply(mc, command[1]);
                Files.delete(request);
                return;
            }
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

    private void worldNameFixture(Minecraft mc) throws Exception {
        var title = mc.submit(() -> mc.gui.screen()).join();
        mc.submit(() -> CreateWorldScreen.openFresh(mc, () -> mc.gui.setScreen(title))).join();
        await(() -> mc.submit(() -> mc.gui.screen() instanceof CreateWorldScreen && mc.gui.overlay() == null).join());
        mc.submit(() -> {
            var screen = (CreateWorldScreen)mc.gui.screen();
            var field = screen.children().stream().filter(EditBox.class::isInstance)
                .map(EditBox.class::cast).findFirst().orElseThrow();
            screen.setFocused(field);
            field.moveCursorToEnd(false);
            screen.getUiState().addListener(state -> System.out.println("AGENT_NAME [" + state.getName() + "]"));
        }).join();
        Files.createFile(Path.of("name-ready"));
        await(() -> Files.exists(Path.of("name-done")) || Files.exists(Path.of("done")));
        mc.submit(() -> mc.gui.screen().onClose()).join();
    }

    private void leaveWorldFixture(Minecraft mc) {
        var request = Path.of("leave-world");
        if (!Files.exists(request)) return;
        mc.submit(() -> {
            if (mc.level == null || mc.getSingleplayerServer() == null)
                throw new IllegalStateException("missing test world before disconnect");
            try {
                Files.delete(request);
                Files.createFile(Path.of("disconnect-ready"));
            } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
            // Let stdio begin the query while the client is still about to clear vanilla queued tasks.
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(750));
            mc.disconnectFromWorld(ClientLevel.DEFAULT_QUIT_MESSAGE);
        }).join();
    }

    @Override public void onInitializeClient() {
        Thread.ofPlatform().daemon().start(() -> {
            var mc = Minecraft.getInstance();
            try {
                await(() -> mc.submit(() -> mc.gui != null && mc.gui.screen() instanceof TitleScreen
                    && mc.gui.overlay() == null).join());
                Files.createFile(Path.of("title-ready"));
                await(() -> Files.exists(Path.of("title-done")) || Files.exists(Path.of("done")));
                if (Files.exists(Path.of("done"))) return;
                worldNameFixture(mc);
                if (Files.exists(Path.of("done"))) return;
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
                    leaveWorldFixture(mc);
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
