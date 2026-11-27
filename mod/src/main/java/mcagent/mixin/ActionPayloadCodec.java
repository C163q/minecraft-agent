package mcagent.mixin;

import java.util.ArrayList;
import java.util.List;
import mcagent.ServerActionEvents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.TypeAndCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(ClientboundCustomPayloadPacket.class)
abstract class ActionPayloadCodec {
    @ModifyArg(method = "<clinit>", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;codec("
            + "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$FallbackProvider;"
            + "Ljava/util/List;)Lnet/minecraft/network/codec/StreamCodec;"), index = 1)
    private static List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> registerGameplayCodec(
        List<TypeAndCodec<? super RegistryFriendlyByteBuf, ?>> vanillaCodecs) {
        var codecs = new ArrayList<>(vanillaCodecs);
        codecs.add(new TypeAndCodec<>(ServerActionEvents.Payload.TYPE, ServerActionEvents.Payload.CODEC));
        return codecs;
    }
}
