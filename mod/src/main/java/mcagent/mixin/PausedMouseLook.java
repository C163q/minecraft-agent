package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.MouseHandler;
import net.minecraft.util.SmoothDouble;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class PausedMouseLook {
    @Shadow private boolean mouseGrabbed;
    @Shadow private double accumulatedDX;
    @Shadow private double accumulatedDY;
    @Shadow @Final private SmoothDouble smoothTurnX;
    @Shadow @Final private SmoothDouble smoothTurnY;

    @Inject(method = "onMove", at = @At("TAIL"))
    private void discardPausedMotion(CallbackInfo ci) {
        // A resume key can follow this motion before vanilla processes the accumulated deltas.
        if (mouseGrabbed && Playback.INSTANCE.frozen()) {
            accumulatedDX = 0;
            accumulatedDY = 0;
        }
    }

    @Inject(method = "turnPlayer", at = @At("HEAD"), cancellable = true)
    private void freezeMouseLook(CallbackInfo ci) {
        if (Playback.INSTANCE.frozen()) {
            smoothTurnX.reset();
            smoothTurnY.reset();
            ci.cancel();
        }
    }
}
