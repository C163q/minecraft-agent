package mcagenttest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/** Holds only the disposable server, optionally suspending client draws for the no-frame case. */
public final class TransitionDelay {
    public static volatile boolean blockDraw;

    public static void hold(String phase) throws IOException {
        var marker = Path.of("delay-" + phase);
        if (!Files.exists(marker)) return;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(55);
        blockDraw = Files.exists(Path.of("delay-without-frame"));
        try {
            Files.writeString(Path.of("delay-started"), phase);
            while (Files.exists(marker) && !Files.exists(Path.of("done"))) {
                if (System.nanoTime() - deadline >= 0) throw new IllegalStateException("transition test timed out");
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            }
        } finally {
            blockDraw = false;
        }
    }
}
