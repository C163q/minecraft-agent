package mcagenttest.mixin;

import mcagenttest.Observer;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
abstract class Events {
    @Inject(method="keyPress", at=@At("HEAD"))
    private void event(long window, int action, KeyEvent key, CallbackInfo ci) {
        if (Observer.recording) System.out.printf("AGENT_KEY %d %d mods=%d%n", key.key(), action, key.modifiers());
    }
}
