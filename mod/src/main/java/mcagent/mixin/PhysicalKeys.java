package mcagent.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import mcagent.Playback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(InputConstants.class)
abstract class PhysicalKeys {
    @Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
    private static void scriptedKey(int code, CallbackInfoReturnable<Boolean> ci) {
        if (Playback.INSTANCE.keyDown(code)) ci.setReturnValue(true);
    }
}
