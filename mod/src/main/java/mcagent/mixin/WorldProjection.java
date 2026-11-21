package mcagent.mixin;

import mcagent.FrameCamera;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class WorldProjection {
    @Inject(method = "render", at = @At("HEAD"))
    private void beginFrame(CallbackInfo ci) {
        FrameCamera.beginFrame();
    }

    // The level projection at upload includes dynamic FOV, bobbing and hurt effects.
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f rememberWorldProjection(Matrix4f projection) {
        FrameCamera.record((GameRenderer)(Object)this, projection);
        return projection;
    }
}
