package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundPingPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonPacketListenerImpl.class)
abstract class Marker {
    @Inject(method = "handlePing", at = @At("RETURN"))
    private void synced(ClientboundPingPacket packet, CallbackInfo ci) {
        Playback.INSTANCE.marker(packet.getId());
    }
}
