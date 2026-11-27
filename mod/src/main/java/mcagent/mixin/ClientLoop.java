package mcagent.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import mcagent.GameBridge;
import mcagent.GameInspection;
import mcagent.PauseBinding;
import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class ClientLoop {
    // Both SDL handlers reach this method only for a press, including in container screens.
    @Inject(method = "handleGlobalKeyPress", at = @At("HEAD"), cancellable = true)
    private void toggleAgentPause(InputConstants.Key key, boolean control, CallbackInfoReturnable<Boolean> ci) {
        if (!PauseBinding.KEY.matches(key)) return;
        var mc = (Minecraft)(Object)this;
        var server = mc.getSingleplayerServer();
        if (mc.level == null || server == null || server.isPublished()) return;
        var screen = mc.gui.screen();
        if (mc.gui.overlay() != null || (screen != null && screen.isPauseScreen())) return;
        Playback.INSTANCE.togglePause(mc);
        ci.setReturnValue(true);
    }

    // Keep Gui.isPausing() itself usable as the native screen/overlay predicate.
    @Redirect(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;isPausing()Z"))
    private boolean keepWorldPaused(Gui gui) {
        return gui.isPausing() || Playback.INSTANCE.frozen();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void beforeTick(CallbackInfo ci) {
        if (!Playback.INSTANCE.beforeTick((Minecraft)(Object)this)) ci.cancel();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void afterTick(CallbackInfo ci) {
        Playback.INSTANCE.afterTick((Minecraft)(Object)this);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
    private void sampleHandshake(boolean tick, CallbackInfo ci) {
        GameBridge.sampleHandshake((Minecraft)(Object)this);
    }

    // Ordinary stops wait for runTick to unwind, including any nested render during respawn.
    @Inject(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V", shift = At.Shift.AFTER))
    private void afterRender(boolean render, CallbackInfo ci) {
        Playback.INSTANCE.afterRender((Minecraft)(Object)this);
    }

    // This runs only when the renderer drew a frame, before Minecraft submits the GPU commands.
    @Inject(method = "renderFrame", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER))
    private void inspectRenderedFrame(boolean tick, CallbackInfo ci) {
        var mc = (Minecraft)(Object)this;
        Playback.INSTANCE.afterWorldTransitionRender(mc);
        GameInspection.afterRender(mc);
        if (!tick) GameBridge.sampleHandshake(mc);
    }
}
