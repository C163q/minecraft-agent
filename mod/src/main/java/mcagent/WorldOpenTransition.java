package mcagent;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** Carries request ownership along one vanilla open or create flow on the client thread. */
public final class WorldOpenTransition {
    private final Minecraft minecraft;
    private long token;

    public WorldOpenTransition(Minecraft minecraft) {
        this.minecraft = minecraft;
        token = Playback.INSTANCE.beginWorldTransition(minecraft, "enter");
    }

    public void resume() {
        if (Playback.INSTANCE.transitionActive(token)) {
            outcome(null, null);
        } else {
            token = Playback.INSTANCE.beginWorldTransition(minecraft, "enter");
        }
    }

    public void outcome(String status, Screen screen) {
        if (token != 0) Playback.INSTANCE.transitionOutcome(token, status, screen);
    }

    public void cancelled() {
        outcome("cancelled", minecraft.gui.screen());
    }

    public Runnable answer(Runnable callback, boolean proceed) {
        return () -> {
            resume();
            callback.run();
            if (!proceed) cancelled();
        };
    }

    public BooleanConsumer answer(BooleanConsumer callback) {
        return proceed -> {
            resume();
            callback.accept(proceed);
            if (!proceed) cancelled();
        };
    }

    public interface Creation {
        void mcagent$beginWorldCreation(Minecraft minecraft);
        WorldOpenTransition mcagent$worldCreation();
    }

    // The recovery screen keeps this callback for its follow-up confirmation.
    public record Recovery(WorldOpenTransition flow, BooleanConsumer callback) implements BooleanConsumer {
        @Override
        public void accept(boolean proceed) {
            flow.resume();
            callback.accept(proceed);
            if (!proceed) flow.cancelled();
        }
    }
}
