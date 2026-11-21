package mcagent.mixin;

import java.util.function.BooleanSupplier;
import mcagent.Playback;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
abstract class ServerTick {
    @Inject(method = "tickServer", at = @At("RETURN"))
    private void confirmPause(BooleanSupplier budget, CallbackInfo ci) {
        Playback.INSTANCE.serverTick((IntegratedServer)(Object)this);
    }
}
