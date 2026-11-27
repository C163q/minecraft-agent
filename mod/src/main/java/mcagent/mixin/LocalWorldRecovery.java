package mcagent.mixin;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import mcagent.WorldOpenTransition;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.RecoverWorldDataScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RecoverWorldDataScreen.class)
abstract class LocalWorldRecovery {
    @Shadow @Final private BooleanConsumer callback;

    @Inject(method = "attemptRestore", at = @At("HEAD"))
    private void beginWorldRecovery(CallbackInfo ci) {
        if (callback instanceof WorldOpenTransition.Recovery recovery) recovery.flow().resume();
    }

    @Redirect(method = {"attemptRestore", "lambda$attemptRestore$1"}, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/Gui;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void showRecoveryResult(Gui gui, Screen screen) {
        if (callback instanceof WorldOpenTransition.Recovery recovery) {
            recovery.flow().outcome(screen instanceof ConfirmScreen ? "needs_input" : "failed", screen);
        }
        gui.setScreen(screen);
    }
}
