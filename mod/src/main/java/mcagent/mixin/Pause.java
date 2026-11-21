package mcagent.mixin;

import mcagent.Playback;
import mcagent.PauseIcon;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Gui.class)
abstract class Pause {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GuiRenderState guiRenderState;

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void drawPauseIndicator(CallbackInfo ci) {
        if (!Playback.INSTANCE.showPauseIndicator(minecraft)) return;
        var graphics = new GuiGraphicsExtractor(minecraft, guiRenderState, 0, 0);
        PauseIcon.draw(minecraft, graphics);
    }

    @Inject(method = "isPausing", at = @At("HEAD"), cancellable = true)
    private void pauseWorld(CallbackInfoReturnable<Boolean> ci) {
        if (Playback.INSTANCE.frozen()) ci.setReturnValue(true);
    }
}
