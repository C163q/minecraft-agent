package mcagenttest;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public final class Observer implements ClientModInitializer {
    public static volatile boolean recording, slow;
    public static long frame;

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(100);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) throw new IllegalStateException("setup timed out");
            Thread.sleep(25);
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
                recording = true;
                Files.createFile(Path.of("ready"));
                await(() -> {
                    slow = Files.exists(Path.of("slow"));
                    return Files.exists(Path.of("done"));
                });
            } catch (Throwable e) { e.printStackTrace(); }
            finally { mc.execute(mc::stop); }
        });
    }
}
