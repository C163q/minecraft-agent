package mcagent;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import javax.imageio.ImageIO;

/** Encodes a readback once for both the MCP image and explicitly named files. */
final class FrameFiles {
    static byte[] encode(NativeImage image, List<Path> destinations) throws IOException {
        var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getPixels(), 0, image.getWidth());
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(rgb, "PNG", buffer);
        byte[] png = buffer.toByteArray();
        for (Path path : destinations) {
            Path temp = Files.createTempFile(path.getParent(), ".minecraft-agent-", ".png");
            try {
                Files.write(temp, png);
                Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
        return png;
    }
}
