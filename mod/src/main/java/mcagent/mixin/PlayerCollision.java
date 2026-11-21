package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Entity.class)
abstract class PlayerCollision {
    // Entity.move passes the vector after stuck-speed and edge-backoff adjustments.
    @Inject(method = "collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;", at = @At("RETURN"))
    private void resolvedMovement(Vec3 requested, CallbackInfoReturnable<Vec3> ci) {
        if ((Object)this instanceof LocalPlayer player)
            Playback.INSTANCE.collision(Minecraft.getInstance(), player, requested, ci.getReturnValue());
    }
}
