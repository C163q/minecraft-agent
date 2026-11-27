package mcagenttest.mixin;

import mcagenttest.ActionFixture;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
abstract class BlockUses {
    @Inject(method = "handleUseItemOn", at = @At("RETURN"))
    private void countUse(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        ActionFixture.recordUse(packet.hitResult().getBlockPos());
    }
}
