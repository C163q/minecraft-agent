package mcagent;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/** Projection belonging to the level drawn into this frame, on the render thread. */
public final class FrameCamera {
    private static final Matrix4f clip = new Matrix4f();
    private static boolean available;
    private static float yaw, pitch;

    public static void beginFrame() { available = false; }

    public static void record(GameRenderer renderer, Matrix4f projection) {
        var camera = renderer.gameRenderState().levelRenderState.cameraRenderState;
        if (!camera.initialized || !camera.isFirstPerson || camera.isPanoramicMode) return;
        clip.set(projection).mul(camera.viewRotationMatrix);
        yaw = camera.yRot;
        pitch = camera.xRot;
        available = true;
    }

    record View(float[] rayBasis, float yaw, float pitch) {}

    static View snapshot(Minecraft mc) {
        if (!available || mc.player == null || mc.gui.screen() != null || mc.gui.overlay() != null
            || mc.gameRenderer.mainCamera().entity() != mc.player) return null;
        // For a world direction (w=0), these rows produce (clipX, clipY, clipW).
        // Inverting gives the ray direction through (ndcX, ndcY, 1), independent of depth convention.
        var basis = new Matrix3f(clip.m00(), clip.m01(), clip.m03(),
                                 clip.m10(), clip.m11(), clip.m13(),
                                 clip.m20(), clip.m21(), clip.m23()).invert();
        return basis.isFinite() ? new View(basis.get(new float[9]), yaw, pitch) : null;
    }
}
