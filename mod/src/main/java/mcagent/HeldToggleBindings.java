package mcagent;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import mcagent.mixin.ToggleReset;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ToggleKeyMapping;

/** Tracks native toggle bindings pressed in-world by an input that the script still holds. */
final class HeldToggleBindings {
    private final Map<InputConstants.Key, Set<ToggleKeyMapping>> held = new HashMap<>();

    void update(Minecraft mc, InputConstants.Key key, boolean press) {
        if (!press) {
            held.remove(key);
        } else if (mc.player != null && mc.gui.screen() == null && mc.gui.overlay() == null) {
            for (var binding : mc.options.keyMappings) {
                if (binding instanceof ToggleKeyMapping toggle && toggle.matches(key))
                    held.computeIfAbsent(key, ignored -> new HashSet<>()).add(toggle);
            }
        }
    }

    void clearForWorldChange() {
        for (var mappings : held.values()) {
            for (var toggle : mappings) {
                // The pause menu saved this toggle for restoration when the screen closes.
                toggle.shouldRestoreStateOnScreenClosed();
                ((ToggleReset)toggle).mcagent$resetToggle();
            }
        }
        held.clear();
    }
}
