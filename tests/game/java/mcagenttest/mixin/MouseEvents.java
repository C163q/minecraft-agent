package mcagenttest.mixin;

import mcagenttest.Observer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
abstract class MouseEvents {
    @Inject(method="onButton", at=@At("HEAD"))
    private void button(long handle, MouseButtonInfo button, int action, CallbackInfo ci) {
        if (!Observer.recording) return;
        var mc = Minecraft.getInstance();
        var window = mc.getWindow();
        var target = mc.gameRenderer.mainRenderTarget();
        System.out.printf("AGENT_MOUSE %d %d at=%.3f,%.3f%n", button.button(), action,
            mc.mouseHandler.xpos() * target.width / window.getScreenWidth(),
            mc.mouseHandler.ypos() * target.height / window.getScreenHeight());
    }
}
