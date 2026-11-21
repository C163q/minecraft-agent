package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.DiscardedPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
abstract class AcceptedHurt {
    @Inject(method = "hurtServer", at = @At("RETURN"))
    private void notifyAcceptedHit(ServerLevel level, DamageSource source, float amount, CallbackInfoReturnable<Boolean> ci) {
        if (ci.getReturnValueZ() && level.getServer() instanceof IntegratedServer server && !server.isPublished()) {
            var player = (ServerPlayer)(Object)this;
            player.connection.send(new ClientboundCustomPayloadPacket(new DiscardedPayload(Playback.HURT_SIGNAL)));
        }
    }
}
