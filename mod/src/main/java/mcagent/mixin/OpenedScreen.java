package mcagent.mixin;

import com.google.gson.JsonObject;
import mcagent.Playback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
abstract class OpenedScreen {
    @Shadow private Screen screen;
    @Unique private Screen lastAssignedScreen;

    // Vanilla resolves implicit screens (such as death) before assigning this field.
    @Inject(method = "setScreen", at = @At(value = "FIELD", opcode = org.objectweb.asm.Opcodes.PUTFIELD,
        target = "Lnet/minecraft/client/gui/Gui;screen:Lnet/minecraft/client/gui/screens/Screen;",
        shift = At.Shift.AFTER))
    private void observeOpenedScreen(Screen requested, CallbackInfo ci) {
        if (screen == lastAssignedScreen) return;
        lastAssignedScreen = screen;
        if (screen == null) return;
        var fields = new JsonObject();
        fields.addProperty("screen", screen.getClass().getSimpleName());
        Playback.INSTANCE.observe(Minecraft.getInstance(), "ScreenOpened", fields);
    }
}
