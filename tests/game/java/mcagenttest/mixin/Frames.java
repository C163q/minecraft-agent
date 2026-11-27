package mcagenttest.mixin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.locks.LockSupport;
import mcagenttest.Observer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class Frames {
    @Unique private boolean capturedRunningFrame;

    @Inject(method="tick", at=@At("HEAD"))
    private void countTick(CallbackInfo ci) {
        Observer.clientTick++;
    }

    @Inject(method="renderFrame", at=@At("HEAD"))
    private void closeTransientMenu(CallbackInfo ci) throws IOException {
        var mc = (Minecraft)(Object)this;
        if (!mc.isPaused() && mc.gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen
                && Files.deleteIfExists(Path.of("respawn-after-resume"))) mc.player.respawn();
        if (Observer.recording && mc.gui.screen() instanceof PauseScreen
                && Files.deleteIfExists(Path.of("close-before-fence"))) {
            // Drop vanilla's pause cause before Minecraft samples it at the end of this frame.
            mc.gui.screen().onClose();
        }
    }

    @Inject(method="renderFrame", at=@At("RETURN"))
    private void captureResumedFrame(CallbackInfo ci) {
        var mc = (Minecraft)(Object)this;
        if (!Observer.slow || mc.isPaused() || capturedRunningFrame) return;
        capturedRunningFrame = true;
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
            try (image) { image.writeToFile(Path.of("resumed.png")); }
            catch (IOException error) { throw new UncheckedIOException(error); }
        });
    }

    @Inject(method="runTick", at=@At("HEAD"))
    private void frame(boolean render, CallbackInfo ci) {
        Observer.frame++;
        if (Observer.slow) LockSupport.parkNanos(180_000_000L);
    }
}
