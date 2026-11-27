package mcagent.mixin;

import mcagent.WorldOpenTransition;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CreateWorldScreen.class)
abstract class LocalWorldCreation implements WorldOpenTransition.Creation {
    @Unique private WorldOpenTransition worldCreation;

    @Override
    public void mcagent$beginWorldCreation(Minecraft minecraft) {
        worldCreation = new WorldOpenTransition(minecraft);
    }

    @Override
    public WorldOpenTransition mcagent$worldCreation() {
        return worldCreation;
    }

    @Inject(method = "createNewWorld", at = @At("RETURN"))
    private void completeWorldCreation(CallbackInfoReturnable<Boolean> cir) {
        if (worldCreation != null) {
            // A directory or pack-copy failure returns false. Vanilla then pops the creation screen.
            worldCreation.outcome(cir.getReturnValueZ() ? "completed" : "failed", null);
        }
    }
}
