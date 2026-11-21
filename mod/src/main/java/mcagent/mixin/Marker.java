package mcagent.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import mcagent.Playback;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
abstract class Marker {
    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
        at = @At("HEAD"), cancellable = true)
    private void hurtSignal(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        if (!packet.payload().type().id().equals(Playback.HURT_SIGNAL)) return;
        var mc = Minecraft.getInstance();
        var listener = (ClientCommonPacketListenerImpl)(Object)this;
        PacketUtils.ensureRunningOnSameThread(packet, listener, mc.packetProcessor());
        if (mc.getConnection() == listener) Playback.INSTANCE.observe(mc, "Hurt");
        ci.cancel();
    }

    @Inject(method = "handlePing", at = @At("RETURN"))
    private void synced(ClientboundPingPacket packet, CallbackInfo ci) {
        Playback.INSTANCE.marker(packet.getId());
    }
}
