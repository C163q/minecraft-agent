package mcagent;

import com.google.gson.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

public final class GameBridge implements ClientModInitializer {
    private static final Gson JSON = new Gson();

    @Override public void onInitializeClient() {
        String socketPath = System.getenv("MINECRAFT_AGENT_SOCKET");
        if (socketPath != null && !socketPath.isBlank()) {
            Thread.ofPlatform().daemon().name("minecraft-agent-ipc").start(() -> listen(Path.of(socketPath)));
        }
    }

    private static void listen(Path path) {
        var logger = LoggerFactory.getLogger("minecraft-agent");
        try (var listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            listener.bind(UnixDomainSocketAddress.of(path));
            // A second instance that fails to bind must never remove this socket.
            path.toFile().deleteOnExit();
            logger.info("Keyboard bridge listening on {}", path);
            while (listener.isOpen()) {
                try (var peer = listener.accept()) {
                    serve(peer);
                } catch (Exception e) {
                    logger.warn("Keyboard connection ended: {}", e.toString());
                }
            }
        } catch (IOException e) {
            logger.error("Could not start keyboard bridge at {}", path, e);
        }
    }

    private static void send(SocketChannel peer, JsonObject message) throws IOException {
        peer.configureBlocking(true);
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(JSON.toJson(message) + "\n");
        while (bytes.hasRemaining()) peer.write(bytes);
    }

    private static JsonObject readProgram(SocketChannel peer) throws Exception {
        peer.configureBlocking(false);
        var data = new ByteArrayOutputStream();
        var buf = ByteBuffer.allocate(8192);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            int count = peer.read(buf);
            if (count < 0) throw new EOFException("client closed before submission");
            if (count == 0) { Thread.sleep(10); continue; }
            for (int i = 0; i < count; i++) {
                if (buf.array()[i] == '\n') {
                    data.write(buf.array(), 0, i);
                    return JsonParser.parseString(data.toString(StandardCharsets.UTF_8)).getAsJsonObject();
                }
            }
            data.write(buf.array(), 0, count);
            buf.clear();
        }
        throw new IOException("timed out receiving keyboard program");
    }

    private static void serve(SocketChannel peer) throws Exception {
        var mc = Minecraft.getInstance();
        var engine = Playback.INSTANCE;
        try {
            var snapshot = mc.submit(() -> engine.snapshot(mc)).join();
            var hello = new JsonObject();
            hello.add("persistent", JSON.toJsonTree(snapshot.persistent()));
            send(peer, hello);
            var program = readProgram(peer);
            var result = mc.submit(() -> engine.start(mc, snapshot, program)).join();
            var probe = ByteBuffer.allocate(1);
            Playback.Observation frame;
            while (true) {
                try {
                    frame = result.get(100, TimeUnit.MILLISECONDS);
                    break;
                } catch (TimeoutException pending) {
                    try {
                        if (peer.read(probe) != 0) throw new EOFException("client disconnected or sent a second submission");
                    } catch (IOException error) {
                        mc.submit(() -> engine.cancel(mc, result)).join();
                        result.thenAccept(observation -> observation.image().close());
                        throw error;
                    }
                }
            }
            var reply = new JsonObject();
            reply.addProperty("world", frame.world());
            reply.addProperty("ticks", frame.ticks());
            try (var image = frame.image()) {
                var rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
                rgb.setRGB(0, 0, image.getWidth(), image.getHeight(), image.getPixels(), 0, image.getWidth());
                var png = new ByteArrayOutputStream();
                ImageIO.write(rgb, "PNG", png);
                reply.addProperty("png", Base64.getEncoder().encodeToString(png.toByteArray()));
            }
            send(peer, reply);
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            var reply = new JsonObject();
            reply.addProperty("error", cause.toString());
            send(peer, reply);
        }
    }
}
