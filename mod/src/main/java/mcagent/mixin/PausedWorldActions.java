package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
abstract class PausedWorldActions {
    // Vanilla still polls held attack/use bindings on client ticks while the world is paused.
    @Inject(method = {"startUseItem", "continueAttack"}, at = @At("HEAD"), cancellable = true)
    private void suppressHeldAction(CallbackInfo ci) {
        if (Playback.INSTANCE.worldActionsPaused((Minecraft)(Object)this)) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void suppressAttack(CallbackInfoReturnable<Boolean> ci) {
        if (Playback.INSTANCE.worldActionsPaused((Minecraft)(Object)this)) ci.setReturnValue(false);
    }

    // Releasing an item such as a drawn bow can itself send a world action.
    @Redirect(method = "handleKeybinds", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;releaseUsingItem(Lnet/minecraft/world/entity/player/Player;)V"))
    private void releaseItemOnResume(MultiPlayerGameMode mode, Player player) {
        if (!Playback.INSTANCE.worldActionsPaused((Minecraft)(Object)this)) mode.releaseUsingItem(player);
    }
}
