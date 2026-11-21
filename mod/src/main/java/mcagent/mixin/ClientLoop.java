package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class ClientLoop {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void beforeTick(CallbackInfo ci) {
        if (!Playback.INSTANCE.beforeTick((Minecraft)(Object)this)) ci.cancel();
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void afterTick(CallbackInfo ci) {
        Playback.INSTANCE.afterTick((Minecraft)(Object)this);
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V", shift = At.Shift.AFTER))
    private void afterRender(boolean render, CallbackInfo ci) {
        Playback.INSTANCE.afterRender((Minecraft)(Object)this);
    }
}
