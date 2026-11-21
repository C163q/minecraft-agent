package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.gui.Gui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
abstract class Pause {
    @Inject(method = "isPausing", at = @At("HEAD"), cancellable = true)
    private void pauseWorld(CallbackInfoReturnable<Boolean> ci) {
        if (Playback.INSTANCE.frozen()) ci.setReturnValue(true);
    }
}
