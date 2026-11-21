package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
abstract class PlayerDeath {
    // Vanilla has matched the packet to the local player and is about to choose screen or respawn.
    @Inject(method = "handlePlayerCombatKill", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/player/LocalPlayer;shouldShowDeathScreen()Z"))
    private void localPlayerDied(ClientboundPlayerCombatKillPacket packet, CallbackInfo ci) {
        var mc = Minecraft.getInstance();
        if (mc.getConnection() == (Object)this) Playback.INSTANCE.observe(mc, "Death");
    }
}
