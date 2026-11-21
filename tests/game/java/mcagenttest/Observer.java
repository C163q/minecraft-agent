package mcagenttest;

import com.mojang.blaze3d.platform.InputConstants;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

public final class Observer implements ClientModInitializer {
    public static volatile boolean recording, slow;
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
                    if (Files.exists(Path.of("reset-view")) && !Files.exists(Path.of("view-reset"))) {
                        mc.submit(() -> { mc.player.setXRot(0); mc.player.setYRot(0); }).join();
                        try { Files.createFile(Path.of("view-reset")); }
                        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    }
                    slow = Files.exists(Path.of("slow"));
                    return Files.exists(Path.of("done"));
                }, 450);
            } catch (Throwable e) { e.printStackTrace(); }
            finally { mc.execute(mc::stop); }
        });
    }
}
