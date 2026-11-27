package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class LocalWorldLeave {
    @Unique private long worldLeaveToken;

    @Inject(method = "disconnectFromWorld", at = @At("HEAD"))
    private void beginLocalLeave(Component reason, CallbackInfo ci) {
        var mc = (Minecraft)(Object)this;
        if (mc.isLocalServer() && mc.level != null) {
            worldLeaveToken = Playback.INSTANCE.beginWorldTransition(mc, "leave");
        }
    }

    @Inject(method = "disconnectFromWorld", at = @At("RETURN"))
    private void completeLocalLeave(Component reason, CallbackInfo ci) {
        if (worldLeaveToken == 0) return;
        var mc = (Minecraft)(Object)this;
        Playback.INSTANCE.transitionOutcome(worldLeaveToken, "completed", mc.gui.screen());
        worldLeaveToken = 0;
    }
}
