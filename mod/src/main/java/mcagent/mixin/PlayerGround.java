package mcagent.mixin;

import com.google.gson.JsonObject;
import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class PlayerGround {
    @Unique private LocalPlayer groundedBeforeSimulation;

    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickEntities()V"))
    private void sampleGround(CallbackInfo ci) {
        var player = ((Minecraft)(Object)this).player;
        groundedBeforeSimulation = player != null && player.onGround() ? player : null;
    }

    // A piston can move the player during block entity simulation, after LocalPlayer.tick().
    @Inject(method = "tick", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/multiplayer/ClientLevel;tickBlockEntities()V", shift = At.Shift.AFTER))
    private void observeTakeoff(CallbackInfo ci) {
        var mc = (Minecraft)(Object)this;
        var player = groundedBeforeSimulation;
        groundedBeforeSimulation = null;
        if (player == null || mc.player != player || player.onGround()) return;
        var position = new JsonObject();
        position.addProperty("x", player.getX());
        position.addProperty("y", player.getY());
        position.addProperty("z", player.getZ());
        var fields = new JsonObject();
        fields.addProperty("dimension", player.level().dimension().identifier().toString());
        fields.add("position", position);
        Playback.INSTANCE.observe(mc, "LeftGround", fields);
    }
}
