package mcagent.mixin;

import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
abstract class PlayerHealth {
    @Unique private float previousAgentHealth = Float.NaN;

    @Inject(method = "onSyncedDataUpdated", at = @At("RETURN"))
    private void healthFieldChanged(EntityDataAccessor<?> field, CallbackInfo ci) {
        if (HealthField.minecraftAgentHealth().equals(field)) observeAgentHealth();
    }

    // An initial full-health packet can equal the default, producing no field-change callback.
    @Inject(method = "hurtTo", at = @At("RETURN"))
    private void healthReceived(float value, CallbackInfo ci) { observeAgentHealth(); }

    @Unique private void observeAgentHealth() {
        var player = (LocalPlayer)(Object)this;
        var mc = Minecraft.getInstance();
        if (mc.player != player) return;
        float value = player.getHealth();
        if (value < previousAgentHealth) Playback.INSTANCE.observe(mc, "HpLost");
        previousAgentHealth = value;
    }
}
