package mcagent;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

public final class PauseIcon {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath("minecraft_agent", "pause_indicator");
    private static boolean loaded;

    public static void draw(Minecraft mc, GuiGraphicsExtractor graphics) {
        // Fabric Loader alone doesn't add mod assets to the vanilla GUI atlas.
        if (!loaded) {
            try (var input = PauseIcon.class.getResourceAsStream("pause.png")) {
                if (input == null) throw new IOException("missing packaged pause.png");
                var texture = new DynamicTexture(TEXTURE::toString, NativeImage.read(input));
                mc.getTextureManager().register(TEXTURE, texture);
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
            loaded = true;
        }
        graphics.nextStratum();
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, 4, 4, 0, 0, 16, 16, 16, 16);
    }
}
