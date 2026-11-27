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

    static int button(String name) {
        Integer code = BUTTONS.get(name);
        if (code == null) throw new IllegalArgumentException("unknown mouse button " + name);
        return code;
    }

    record Quantity(String unit, double value) {
        static Quantity read(JsonObject json) {
            String unit = json.get("unit").getAsString();
            double value = json.get("value").getAsDouble();
            validateUnit(unit);
            if (!Double.isFinite(value)) throw new IllegalArgumentException("invalid mouse quantity");
            return new Quantity(unit, value);
        }

        static void validateUnit(String unit) {
            if (!Set.of("px", "rotate", "percentage").contains(unit))
                throw new IllegalArgumentException("invalid mouse unit " + unit);
        }

        double pixels(double extent, double degreesPerUnit) {
            return switch (unit) {
                case "percentage" -> value / 100 * extent;
                case "rotate" -> value / degreesPerUnit;
                default -> value;
            };
        }
    }

    record Input(boolean absolute, List<Quantity> args) {
        // Constant preflight leaves operand slots empty when their values depend on variables.
        static void validateSpeeds(boolean absolute, List<Quantity> args) {
            for (int i = 2; i < args.size(); ++i) {
                var speed = args.get(i);
                if (speed == null) continue;
                var displacement = args.get(i - 2);
                boolean requiresSpeed = args.size() == 3 || (!absolute && displacement != null && displacement.value != 0);
                if (speed.value < 0 || (requiresSpeed && speed.value == 0))
                    throw new IllegalArgumentException("invalid mouse speed");
            }
        }
    }
    record Actions(List<Input> moves, Map<Integer,String> buttons) {
        static Actions read(JsonObject run) {
            var moves = new ArrayList<Input>();
            if (run.has("moves")) for (var element : run.getAsJsonArray("moves")) {
                var move = element.getAsJsonObject();
                var args = new ArrayList<Quantity>();
                for (var arg : move.getAsJsonArray("args")) args.add(Quantity.read(arg.getAsJsonObject()));
                if (args.size() < 2 || args.size() > 4) throw new IllegalArgumentException("expected two to four mouse arguments");
                boolean absolute = move.get("absolute").getAsBoolean();
                Input.validateSpeeds(absolute, args);
                moves.add(new Input(absolute, args));
            }
            var buttons = new TreeMap<Integer,String>();
            if (run.has("buttons")) run.getAsJsonObject("buttons").entrySet().forEach(entry -> {
                int code = button(entry.getKey());
                String action = entry.getValue().getAsString();
                if (!Set.of("temporary", "press", "release").contains(action))
                    throw new IllegalArgumentException("unknown mouse action " + action);
                buttons.put(code, action);
            });
            return new Actions(moves, buttons);
        }
    }

    record Row(List<Input> moves, boolean buttons, boolean dynamic) {}
    record Prepared(Movement motion, Movement.Delta step) {}

    static final class PreflightFailure extends RuntimeException {
        final int row;

        PreflightFailure(int row, RuntimeException cause) {
            super(cause.getMessage(), cause);
            this.row = row;
        }
    }

    record Geometry(double width, double height, double windowWidth, double windowHeight,
                    double cursorX, double cursorY, boolean grabbed, boolean smooth,
                    double degrees, boolean invertX, boolean invertY) {
        static Geometry read(Minecraft mc) {
            var mouse = mc.mouseHandler;
            var window = mc.getWindow();
            var target = mc.gameRenderer.mainRenderTarget();
            // MouseHandler.turnPlayer followed by Entity.turn in 26.3.
            double f = mc.options.sensitivity().get() * 0.6f + 0.2f;
            boolean scoped = mc.player != null && mc.options.getCameraType().isFirstPerson() && mc.player.isScoping();
            boolean smooth = mc.options.smoothCamera;
            double degrees = f * f * f * (scoped && !smooth ? 1 : 8) * 0.15f;
            return new Geometry(target.width, target.height, window.getScreenWidth(), window.getScreenHeight(),
                mouse.xpos(), mouse.ypos(), mouse.isMouseGrabbed(), smooth, degrees,
                mc.options.invertMouseX().get(), mc.options.invertMouseY().get());
        }
    }

    private Movement movement = new Movement();
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

    private static void start(Movement motion, Geometry geometry, Input input, double cap) {
        double width = geometry.width, height = geometry.height;
        if (width <= 0 || height <= 0 || geometry.windowWidth <= 0 || geometry.windowHeight <= 0)
            throw new IllegalArgumentException("mouse input requires a window with nonzero dimensions");
        if (input.absolute && geometry.grabbed)
            throw new IllegalArgumentException("MouseMoveTo needs a free cursor; use MouseMove in first person");

        var args = input.args;
        if (geometry.grabbed && geometry.smooth && args.stream().anyMatch(q -> q.unit.equals("rotate")))
            throw new IllegalArgumentException("Rotate needs cinematic camera smoothing disabled");

        double degrees = geometry.degrees;
        double x = args.get(0).pixels(width, geometry.invertX ? -degrees : degrees);
        double y = args.get(1).pixels(height, geometry.invertY ? -degrees : degrees);
        if (input.absolute) {
            x = absoluteDelta(x, geometry.cursorX * width / geometry.windowWidth, width);
            y = absoluteDelta(y, geometry.cursorY * height / geometry.windowHeight, height);
        }
        if (args.size() == 4) {
            motion.addPerAxis(x, y, args.get(2).pixels(width, degrees), args.get(3).pixels(height, degrees));
        } else {
            double speed = cap;
            if (args.size() == 3) {
                double length = Math.hypot(x, y);
                double extent = length == 0 ? width : 1 / Math.hypot((x / length) / width, (y / length) / height);
                speed = args.get(2).pixels(extent, degrees);
            }
            motion.addStraight(x, y, speed);
        }
    }

    Prepared prepare(Minecraft mc, List<Row> rows, double cap) {
        if (rows.stream().noneMatch(Row::dynamic)) return null;
        boolean hasMove = rows.stream().anyMatch(row -> !row.moves.isEmpty());
        return prepare(hasMove ? Geometry.read(mc) : null, mc.isWindowActive(), rows, cap);
    }

    Prepared prepare(Geometry geometry, boolean windowActive, List<Row> rows, double cap) {
        int lastDynamic = -1, lastDynamicMove = -1;
        boolean hasWork = active();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            hasWork |= !row.moves.isEmpty() || row.buttons;
            if (row.dynamic) {
                lastDynamic = index;
                if (!row.moves.isEmpty()) lastDynamicMove = index;
            }
        }
        if (lastDynamic < 0 || !hasWork) return null;
        int responsible = lastDynamicMove < 0 ? lastDynamic : lastDynamicMove;
        if (!windowActive) throw new PreflightFailure(responsible,
            new IllegalStateException("mouse input requires an active game window"));

        var candidate = movement.copy();
        int buffered = candidate.size();
        var added = new ArrayList<Integer>();
        for (int index = 0; index < rows.size(); index++) {
            var row = rows.get(index);
            for (var move : row.moves) {
                try {
                    int previous = candidate.size();
                    start(candidate, geometry, move, cap);
                    if (candidate.size() != previous) added.add(index);
                } catch (IllegalArgumentException error) {
                    throw new PreflightFailure(index, error);
                }
            }
        }
        try {
            return new Prepared(candidate, candidate.advance(cap));
        } catch (Movement.AdvanceFailure error) {
            // A failed shared step may implicate an existing move. Keep its owner and report the new dynamic row.
            int row = error.move < buffered ? -1 : added.get(error.move - buffered);
            throw new PreflightFailure(row < 0 ? responsible : row, error);
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

    // Apply the sampled step while the cursor still belongs to the screen used for conversion.
    // A key on this same tick can synchronously close that screen and grab the mouse.
    void applyPreparedMotion(Minecraft mc, Prepared prepared, BooleanSupplier valid) {
        movement = prepared.motion;
        move(mc, prepared.step, valid);
    }

    void tick(Minecraft mc, Actions actions, boolean motionApplied, double cap, int modifiers, BooleanSupplier valid) {
        if (!valid.getAsBoolean()) return;
        if (!pending() && held.isEmpty() && actions.moves.isEmpty() && actions.buttons.isEmpty()) return;
        if (!motionApplied) {
            if (!mc.isWindowActive()) throw new IllegalStateException("mouse input requires an active game window");
            if (!actions.moves.isEmpty()) {
                var geometry = Geometry.read(mc);
                for (var input : actions.moves) start(movement, geometry, input, cap);
            }
            if (pending()) move(mc, movement.advance(cap), valid);
        }
        if (!valid.getAsBoolean()) return;

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
