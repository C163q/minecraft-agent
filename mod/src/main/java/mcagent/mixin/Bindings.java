package mcagent.mixin;

import java.util.Arrays;
import mcagent.PauseBinding;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Options.class)
abstract class Bindings {
    @Shadow @Final @Mutable public KeyMapping[] keyMappings;

    // The Options constructor calls load() after assembling the vanilla Controls list.
    @Inject(method = "load", at = @At("HEAD"))
    private void registerPauseBinding(CallbackInfo ci) {
        if (Arrays.asList(keyMappings).contains(PauseBinding.KEY)) return;
        keyMappings = Arrays.copyOf(keyMappings, keyMappings.length + 1);
        keyMappings[keyMappings.length - 1] = PauseBinding.KEY;
    }
}
