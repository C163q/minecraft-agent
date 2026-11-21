package mcagenttest.mixin;

import mcagenttest.DamageStimulus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
abstract class StopHealth {
    @Inject(method = "handlePing", at = @At("RETURN"))
    private void observeLateDrop(ClientboundPingPacket packet, CallbackInfo ci) {
        if (!DamageStimulus.dropAtNextMarker) return;
        var mc = Minecraft.getInstance();
        if (!mc.isPaused()) return;
        DamageStimulus.dropAtNextMarker = false;
        mc.player.hurtTo(16);
        mc.player.hurtTo(20);
        System.out.println("AGENT_LATE_DROP");
    }
}
