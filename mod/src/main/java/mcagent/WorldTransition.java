package mcagent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;

/** Owns one script request from the start of its native transition until delivery. */
final class WorldTransition {
    private static final long TRANSITION_TIMEOUT = TimeUnit.SECONDS.toNanos(30);
    private static final long OBSERVATION_TIMEOUT = TRANSITION_TIMEOUT + TimeUnit.SECONDS.toNanos(5);

    private enum Delivery { PENDING, SENT, FAILED }

    final long token;
    final String direction;
    final CompletableFuture<Playback.Observation> result;
    private final long started;
    private final AtomicReference<Delivery> delivery = new AtomicReference<>(Delivery.PENDING);
    private String outcome;
    boolean observed, releasingPause;
    Screen expectedScreen;
    IntegratedServer entryServer;

    WorldTransition(long token, String direction, CompletableFuture<Playback.Observation> result, long started) {
        this.token = token;
        this.direction = direction;
        this.result = result;
        this.started = started;
    }

    boolean accepts(long owner, long now) {
        return owner == token && !observed && !result.isDone() && delivery.get() == Delivery.PENDING
            && now - started < TRANSITION_TIMEOUT;
    }

    boolean abandoned() { return delivery.get() == Delivery.FAILED || result.isCompletedExceptionally(); }

    boolean deliveryConfirmed() { return delivery.get() == Delivery.SENT; }

    void finishDelivery(CompletableFuture<Playback.Observation> owner, boolean delivered) {
        if (owner != result || (delivered && (!result.isDone() || result.isCompletedExceptionally()))) return;
        delivery.compareAndSet(Delivery.PENDING, delivered ? Delivery.SENT : Delivery.FAILED);
    }

    boolean signal(long owner, String status, long now) {
        if (!accepts(owner, now)) return false;
        outcome = status;
        return true;
    }

    boolean nativeCompleted() { return "completed".equals(outcome); }

    String frameStatus(long now, boolean ready, boolean outcomeVisible) {
        if (outcomeVisible && outcome != null && !nativeCompleted()) return outcome;
        if (now - started >= TRANSITION_TIMEOUT) return "in_progress";
        return ready && nativeCompleted() ? "completed" : null;
    }

    // The IPC thread can release the peer even if the client stops supplying frames.
    void expire(CompletableFuture<Playback.Observation> owner, long now) {
        if (result == owner && !result.isDone() && now - started >= OBSERVATION_TIMEOUT) {
            result.completeExceptionally(new IllegalStateException("timed out observing a rendered world transition frame"));
        }
    }
}
