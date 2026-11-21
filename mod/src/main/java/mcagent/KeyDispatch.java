package mcagent;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.lwjgl.sdl.SDLKeyboard;
import static org.lwjgl.sdl.SDLKeycode.*;

final class KeyDispatch {
    private final Set<Integer> held = new LinkedHashSet<>();

    static int resolve(String name) {
        // Minecraft also accepts arbitrary numeric scancodes; script digits refer to the number row.
        if (name.length() > 1 && name.chars().allMatch(Character::isDigit))
            throw new IllegalArgumentException("unknown keyboard key: " + name);
        String id = "key.keyboard." + name.replace('_', '.');
        try {
            var key = InputConstants.getKey(id);
            if (key.getValue() > 0 && key.getName().equals(id)) return key.getValue();
        } catch (IllegalArgumentException ignored) {
            // Unknown symbolic names can fall through to the numeric scancode parser.
        }
        throw new IllegalArgumentException("unknown keyboard key: " + name);
    }

    boolean isEmpty() { return held.isEmpty(); }

    boolean isDown(int code) { return held.contains(code); }

    private static int modifier(int code) {
        return switch (code) {
            case InputConstants.KEY_LSHIFT -> SDL_KMOD_LSHIFT;
            case InputConstants.KEY_RSHIFT -> SDL_KMOD_RSHIFT;
            case InputConstants.KEY_LCONTROL -> SDL_KMOD_LCTRL;
            case InputConstants.KEY_RCONTROL -> SDL_KMOD_RCTRL;
            case InputConstants.KEY_LALT -> SDL_KMOD_LALT;
            case InputConstants.KEY_RALT -> SDL_KMOD_RALT;
            case InputConstants.KEY_LGUI -> SDL_KMOD_LGUI;
            case InputConstants.KEY_RGUI -> SDL_KMOD_RGUI;
            default -> 0;
        };
    }

    void apply(Minecraft mc, Set<Integer> wanted) {
        if (wanted.equals(held)) return;
        // Release chord keys before modifiers. Press modifiers before chord keys.
        var released = held.stream().filter(k -> !wanted.contains(k))
            .sorted(Comparator.comparing(k -> modifier(k) != 0)).toList();
        var pressed = wanted.stream().filter(k -> !held.contains(k))
            .sorted(Comparator.comparing(k -> modifier(k) == 0)).toList();
        for (int code : released) event(mc, code, false);
        for (int code : pressed) event(mc, code, true);
    }

    short modifiers() {
        short mods = SDLKeyboard.SDL_GetModState();
        for (int key : held) mods |= (short) modifier(key);
        return mods;
    }

    private void event(Minecraft mc, int code, boolean press) {
        if (press) held.add(code); else held.remove(code);
        short mods = modifiers();
        int logical = SDLKeyboard.SDL_GetKeyFromScancode(code, mods, false);
        mc.keyboardHandler.keyPress(mc.getWindow().handle(), press ? InputConstants.PRESS : InputConstants.RELEASE,
            new KeyEvent(code, logical, mods));
    }
}
