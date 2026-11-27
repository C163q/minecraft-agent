package mcagent;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import org.slf4j.LoggerFactory;

public final class GameBridge implements ClientModInitializer {
    private static final Gson JSON = new Gson();
    private static final int IPC_TIMEOUT_SECONDS = 30;
    private static final AtomicReference<CompletableFuture<Playback.Snapshot>> PENDING_HANDSHAKE = new AtomicReference<>();

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
            logger.info("Input bridge listening on {}", path);
            while (listener.isOpen()) {
                try (var peer = listener.accept()) {
                    serve(peer);
                } catch (Exception e) {
                    logger.warn("Input connection ended: {}", e.toString());
                }
            }
        } catch (IOException e) {
            logger.error("Could not start input bridge at {}", path, e);
        }
    }

    private static void send(SocketChannel peer, String message) throws IOException, InterruptedException {
        peer.configureBlocking(false);
        ByteBuffer bytes = StandardCharsets.UTF_8.encode(message + "\n");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
        while (bytes.hasRemaining()) {
            if (deadline - System.nanoTime() <= 0) throw new IOException("timed out sending game response");
            if (peer.write(bytes) == 0) Thread.sleep(10);
        }
    }

    private static JsonObject readProgram(SocketChannel peer) throws Exception {
        peer.configureBlocking(false);
        var data = new ByteArrayOutputStream();
        var buf = ByteBuffer.allocate(8192);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
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
        throw new IOException("timed out receiving input program");
    }

    private static void serve(SocketChannel peer) throws Exception {
        var mc = Minecraft.getInstance();
        var engine = Playback.INSTANCE;
        String response;
        try {
            var snapshot = handshakeSnapshot();
            var hello = new JsonObject();
            hello.addProperty("mouse_input", true);
            hello.addProperty("inspect_input", true);
            hello.addProperty("inspect_game", true);
            hello.addProperty("pause_queue", true);
            hello.addProperty("max_ticks", true);
            hello.addProperty("input_events", true);
            hello.addProperty("input_functions", true);
            hello.add("event_names", JSON.toJsonTree(InputQueue.EVENT_NAMES));
            hello.add("persistent", JSON.toJsonTree(snapshot.persistent()));
            send(peer, JSON.toJson(hello));
            var program = readProgram(peer);
            if (program.has("op")) {
                response = switch (program.get("op").getAsString()) {
                    case "inspect_game" -> inspectGame(peer, program).toString(); // Preserve explicit JSON nulls.
                    case "inspect_input" -> JSON.toJson(inspectInput(mc, engine, program));
                    default -> throw new IllegalArgumentException("unknown operation");
                };
            } else {
                play(mc, engine, peer, snapshot, program);
                return;
            }
        } catch (Exception e) {
            Throwable cause = e;
            while (cause.getCause() != null) cause = cause.getCause();
            var reply = new JsonObject();
            reply.addProperty("error", cause.toString());
            response = JSON.toJson(reply);
        }
        send(peer, response);
    }

    private static Playback.Snapshot handshakeSnapshot() throws Exception {
        var result = new CompletableFuture<Playback.Snapshot>();
        PENDING_HANDSHAKE.set(result);
        try {
            return result.get(IPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException pending) {
            throw new IOException("timed out waiting for the game client handshake");
        } finally {
            PENDING_HANDSHAKE.compareAndSet(result, null);
            result.cancel(false);
        }
    }

    // Sample before normal client ticks, or after a transition frame that does not process tasks.
    public static void sampleHandshake(Minecraft mc) {
        var result = PENDING_HANDSHAKE.get();
        if (result == null || !PENDING_HANDSHAKE.compareAndSet(result, null) || result.isDone()) return;
        try {
            result.complete(Playback.INSTANCE.snapshot(mc));
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
    }

    private static JsonObject inspectInput(Minecraft mc, Playback engine, JsonObject program) throws Exception {
        var state = mc.submit(() -> engine.inspect(mc)).get(IPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        var reply = JSON.toJsonTree(state).getAsJsonObject();
        if (!program.has("queue_version")) {
            reply.remove("queue_entries");
            reply.remove("pause_reason");
        }
        return reply;
    }

    private static JsonObject inspectGame(SocketChannel peer, JsonObject request) throws Exception {
        var option = request.get("include_screenshot");
        if (option != null && !(option.isJsonPrimitive() && option.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("include_screenshot must be a boolean");
        var result = GameInspection.request(option != null && option.getAsBoolean());
        var probe = ByteBuffer.allocate(1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
        try {
            while (deadline - System.nanoTime() > 0) {
                try {
                    var frame = result.get(100, TimeUnit.MILLISECONDS);
                    var reply = new JsonObject();
                    reply.add("state", frame.state());
                    if (frame.png() != null) addImage(reply, frame.png(), frame.camera());
                    return reply;
                } catch (TimeoutException pending) {
                    if (peer.read(probe) != 0) throw new EOFException("client disconnected or sent a second submission");
                }
            }
            throw new IOException("timed out waiting for a rendered game frame");
        } finally {
            result.cancel(false);
        }
    }

    private static void play(Minecraft mc, Playback engine, SocketChannel peer,
                               Playback.Snapshot snapshot, JsonObject program) throws Exception {
        var result = new CompletableFuture<Playback.Observation>();
        var waitingToStart = new AtomicBoolean(true);
        // A native world transition may drop queued tasks before acceptance.
        long startDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(IPC_TIMEOUT_SECONDS);
        mc.execute(() -> {
            if (!waitingToStart.compareAndSet(true, false) || result.isDone()) return;
            try {
                engine.start(mc, snapshot, program, result);
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        });
        var probe = ByteBuffer.allocate(1);
        boolean delivered = false;
        try {
            while (true) {
                Playback.Observation frame;
                try {
                    frame = result.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException pending) {
                    if (peer.read(probe) != 0) throw new EOFException("client disconnected or sent a second submission");
                    if (System.nanoTime() - startDeadline >= 0 && waitingToStart.compareAndSet(true, false))
                        throw new IOException("timed out before the game accepted the input script");
                    engine.expireTransition(result);
                    continue;
                }
                if (frame.transition() != null && peer.read(probe) != 0)
                    throw new EOFException("client closed before the transition frame was returned");
                send(peer, playReply(frame));
                delivered = true;
                return;
            }
        } finally {
            result.cancel(false);
            engine.finishDelivery(result, delivered);
        }
    }

    static String playReply(Playback.Observation frame) {
        var reply = new JsonObject();
        reply.addProperty("world", frame.world());
        reply.addProperty("ticks", frame.ticks());
        reply.addProperty("pause_reason", frame.pauseReason());
        if (frame.transition() != null) {
            reply.add("transition", JSON.toJsonTree(frame.transition()));
            reply.addProperty("screen", frame.screen());
        }
        if (!frame.listeners().isEmpty()) reply.add("pause_listeners", JSON.toJsonTree(frame.listeners()));
        var events = new JsonArray();
        for (var event : frame.events().entries()) {
            var entry = new JsonObject();
            entry.addProperty("event", event.event());
            if (event.fields() != null) event.fields().entrySet().forEach(field -> entry.add(field.getKey(), field.getValue()));
            entry.add("listeners", JSON.toJsonTree(event.listeners()));
            events.add(entry);
        }
        reply.add("events", events);
        if (frame.events().omitted() > 0) reply.addProperty("events_omitted", frame.events().omitted());
        if (frame.png() != null) addImage(reply, frame.png(), frame.camera());
        return reply.toString(); // Preserve explicit JSON nulls in event fields.
    }

    private static void addImage(JsonObject reply, byte[] png, FrameCamera.View camera) {
        if (camera != null) reply.add("camera", JSON.toJsonTree(camera));
        reply.addProperty("png", Base64.getEncoder().encodeToString(png));
    }
}
