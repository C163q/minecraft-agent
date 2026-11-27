package mcagent;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.MouseButtonInfo;

/** Converts at activation, then feeds each combined step through the native input handlers. */
final class MouseDispatch {
    private static final Map<String,Integer> BUTTONS = Map.of(
        "left", InputConstants.MOUSE_BUTTON_LEFT, "right", InputConstants.MOUSE_BUTTON_RIGHT,
        "middle", InputConstants.MOUSE_BUTTON_MIDDLE);

    record Quantity(String unit, double value) {
        static Quantity read(JsonObject json) {
            String unit = json.get("unit").getAsString();
            double value = json.get("value").getAsDouble();
            if (!Set.of("px", "rotate", "percentage").contains(unit) || !Double.isFinite(value))
                throw new IllegalArgumentException("invalid mouse quantity");
            return new Quantity(unit, value);
        }

        double pixels(double extent, double degreesPerUnit) {
            return switch (unit) {
                case "percentage" -> value / 100 * extent;
                case "rotate" -> value / degreesPerUnit;
                default -> value;
            };
        }
    }

    record Input(boolean absolute, List<Quantity> args) {}
    record Actions(List<Input> moves, Map<Integer,String> buttons) {
        static Actions read(JsonObject run) {
            var moves = new ArrayList<Input>();
            if (run.has("moves")) for (var element : run.getAsJsonArray("moves")) {
                var move = element.getAsJsonObject();
                var args = new ArrayList<Quantity>();
                for (var arg : move.getAsJsonArray("args")) args.add(Quantity.read(arg.getAsJsonObject()));
                if (args.size() < 2 || args.size() > 4) throw new IllegalArgumentException("expected two to four mouse arguments");
                boolean absolute = move.get("absolute").getAsBoolean();
                for (int i = 2; i < args.size(); ++i) {
                    boolean requiresSpeed = args.size() == 3 || (!absolute && args.get(i - 2).value != 0);
                    if (args.get(i).value < 0 || (requiresSpeed && args.get(i).value == 0))
                        throw new IllegalArgumentException("invalid mouse speed");
                }
                moves.add(new Input(absolute, args));
            }
            var buttons = new TreeMap<Integer,String>();
            if (run.has("buttons")) run.getAsJsonObject("buttons").entrySet().forEach(entry -> {
                Integer code = BUTTONS.get(entry.getKey());
                if (code == null) throw new IllegalArgumentException("unknown mouse button " + entry.getKey());
                String action = entry.getValue().getAsString();
                if (!Set.of("temporary", "press", "release").contains(action))
                    throw new IllegalArgumentException("unknown mouse action " + action);
                buttons.put(code, action);
            });
            return new Actions(moves, buttons);
        }
    }

    private final Movement movement = new Movement();
    private final HeldToggleBindings toggles;
    private final Set<Integer> persistent = new TreeSet<>(), held = new TreeSet<>();

    MouseDispatch(HeldToggleBindings toggles) { this.toggles = toggles; }

    Map<String,String> inspectButtons() {
        var result = new TreeMap<String,String>();
        BUTTONS.forEach((name, code) -> {
            if (held.contains(code)) result.put(name, persistent.contains(code) ? "persistent" : "temporary");
        });
        return result;
    }

    List<Movement.Pending<?>> inspectMoves() { return movement.inspect(); }

    boolean pending() { return movement.pending(); }
    boolean active() { return pending() || !held.isEmpty(); }
    void clearMoves() { movement.clear(); }

    private static double absoluteDelta(double target, double cursor, double extent) {
        // Summing native GUI steps and converting window coordinates back to framebuffer
        // pixels can leave an integral displacement a few ulps either side of its value.
        double delta = target - cursor;
        double nearest = Math.rint(delta);
        return Math.abs(delta - nearest) <= 4 * Math.ulp(extent) ? nearest : delta;
    }

    private void start(Minecraft mc, Input input, double cap) {
        var m = mc.mouseHandler;
        var w = mc.getWindow();
        var target = mc.gameRenderer.mainRenderTarget();
        double width = target.width, height = target.height;
        if (width <= 0 || height <= 0 || w.getScreenWidth() <= 0 || w.getScreenHeight() <= 0)
            throw new IllegalArgumentException("mouse input requires a window with nonzero dimensions");
        if (input.absolute && m.isMouseGrabbed())
            throw new IllegalArgumentException("MouseMoveTo needs a free cursor; use MouseMove in first person");

        var args = input.args;
        if (m.isMouseGrabbed() && mc.options.smoothCamera && args.stream().anyMatch(q -> q.unit.equals("rotate")))
            throw new IllegalArgumentException("Rotate needs cinematic camera smoothing disabled");

        // MouseHandler.turnPlayer followed by Entity.turn in 26.3.
        double f = mc.options.sensitivity().get() * 0.6f + 0.2f;
        boolean scoped = mc.player != null && mc.options.getCameraType().isFirstPerson() && mc.player.isScoping();
        double degrees = f * f * f * (scoped && !mc.options.smoothCamera ? 1 : 8) * 0.15f;
        double x = args.get(0).pixels(width, mc.options.invertMouseX().get() ? -degrees : degrees);
        double y = args.get(1).pixels(height, mc.options.invertMouseY().get() ? -degrees : degrees);
        if (input.absolute) {
            x = absoluteDelta(x, m.xpos() * width / w.getScreenWidth(), width);
            y = absoluteDelta(y, m.ypos() * height / w.getScreenHeight(), height);
        }
        if (args.size() == 4) {
            movement.addPerAxis(x, y, args.get(2).pixels(width, degrees), args.get(3).pixels(height, degrees));
        } else {
            double speed = cap;
            if (args.size() == 3) {
                double length = Math.hypot(x, y);
                double extent = length == 0 ? width : 1 / Math.hypot((x / length) / width, (y / length) / height);
                speed = args.get(2).pixels(extent, degrees);
            }
            movement.addStraight(x, y, speed);
        }
    }

    private void move(Minecraft mc, Movement.Delta step, BooleanSupplier valid) {
        if (step.x() == 0 && step.y() == 0) return;
        var w = mc.getWindow();
        var m = mc.mouseHandler;
        // Vanilla discards the first movement after grabbing/resizing. Consume that baseline
        // with the current position so that this tick's actual displacement is preserved.
        m.onMove(w.handle(), m.xpos(), m.ypos(), 0, 0);
        if (!valid.getAsBoolean()) return;
        if (m.isMouseGrabbed()) {
            m.onMove(w.handle(), m.xpos(), m.ypos(), step.x(), step.y());
        } else {
            var target = mc.gameRenderer.mainRenderTarget();
            double scaleX = (double)w.getScreenWidth() / target.width;
            double scaleY = (double)w.getScreenHeight() / target.height;
            double x = Math.clamp(m.xpos() + step.x() * scaleX, 0, (target.width - 1) * scaleX);
            double y = Math.clamp(m.ypos() + step.y() * scaleY, 0, (target.height - 1) * scaleY);
            m.onMove(w.handle(), x, y, x - m.xpos(), y - m.ypos());
        }
        if (valid.getAsBoolean()) m.handleAccumulatedMovement();
    }

    void tick(Minecraft mc, Actions actions, double cap, int modifiers, BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return;
        if (!pending() && held.isEmpty() && actions.moves.isEmpty() && actions.buttons.isEmpty()) return;
        if (!mc.isWindowActive()) throw new IllegalStateException("mouse input requires an active game window");
        for (var input : actions.moves) start(mc, input, cap);
        if (pending()) {
            move(mc, movement.advance(cap), valid);
            if (!valid.getAsBoolean()) return;
        }

        var wanted = new TreeSet<>(persistent);
        actions.buttons.forEach((button, action) -> {
            if (action.equals("release")) {
                wanted.remove(button);
                persistent.remove(button);
            } else {
                wanted.add(button);
                if (action.equals("press")) persistent.add(button);
            }
        });
        applyButtons(mc, wanted, modifiers, valid);
    }

    void reset(Minecraft mc, int modifiers) {
        clearMoves();
        persistent.clear();
        applyButtons(mc, Set.of(), modifiers, () -> true);
    }

    private void applyButtons(Minecraft mc, Set<Integer> wanted, int modifiers, BooleanSupplier valid) {
        if (wanted.equals(held)) return;
        for (int button : new TreeSet<>(held)) {
            if (!valid.getAsBoolean()) return;
            if (!wanted.contains(button) && held.remove(button)) {
                toggles.update(mc, InputConstants.Type.MOUSE.getOrCreate(button), false);
                mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(button, modifiers), InputConstants.RELEASE);
            }
        }
        for (int button : wanted) {
            if (!valid.getAsBoolean()) return;
            if (!held.add(button)) continue;
            toggles.update(mc, InputConstants.Type.MOUSE.getOrCreate(button), true);
            mc.mouseHandler.onButton(mc.getWindow().handle(), new MouseButtonInfo(button, modifiers), InputConstants.PRESS);
        }
    }
}
