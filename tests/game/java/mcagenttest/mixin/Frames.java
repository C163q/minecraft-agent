package mcagenttest.mixin;

import java.util.concurrent.locks.LockSupport;
import mcagenttest.Observer;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class Frames {
    @Inject(method="runTick", at=@At("HEAD"))
    private void frame(boolean render, CallbackInfo ci) {
        Observer.frame++;
        if (Observer.slow) LockSupport.parkNanos(180_000_000L);
    }
}
