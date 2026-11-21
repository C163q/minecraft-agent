package mcagenttest.mixin;

import mcagenttest.Observer;
import net.minecraft.client.player.KeyboardInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
abstract class Sample {
    @Inject(method="tick", at=@At("RETURN"))
    private void sample(CallbackInfo ci) {
        if (!Observer.recording) return;
        mcagenttest.DamageStimulus.tick();
        var keys = ((KeyboardInput)(Object)this).keyPresses;
        System.out.printf("AGENT_SAMPLE %d%d%d frame=%d%n", keys.forward()?1:0, keys.jump()?1:0, keys.left()?1:0, Observer.frame);
    }
}
