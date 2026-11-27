package mcagent.mixin;

import mcagent.Playback;
import mcagent.ServerActionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketUtils;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
abstract class Marker {
    @Shadow @Final protected Connection connection;

    @Inject(method = "handleCustomPayload(Lnet/minecraft/network/protocol/common/ClientboundCustomPayloadPacket;)V",
        at = @At("HEAD"), cancellable = true)
    private void actionSignal(ClientboundCustomPayloadPacket packet, CallbackInfo ci) {
        var payload = packet.payload();
        if (!(payload instanceof ServerActionEvents.Payload) && !payload.type().id().equals(Playback.HURT_SIGNAL)) return;
        var mc = Minecraft.getInstance();
        var listener = (ClientCommonPacketListenerImpl)(Object)this;
        PacketUtils.ensureRunningOnSameThread(packet, listener, mc.packetProcessor());
        if (connection.isMemoryConnection() && mc.getConnection() == listener) {
            if (payload instanceof ServerActionEvents.Payload action) {
                Playback.INSTANCE.observe(mc, action.event(), action.data());
            } else {
                Playback.INSTANCE.observe(mc, "Hurt");
            }
        }
        ci.cancel();
    }

    @Inject(method = "handlePing", at = @At("RETURN"))
    private void synced(ClientboundPingPacket packet, CallbackInfo ci) {
        Playback.INSTANCE.marker(packet.getId());
    }
}
