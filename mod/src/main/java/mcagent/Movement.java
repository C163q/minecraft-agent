package mcagent;

import java.util.ArrayList;
import java.util.List;

/** Unfinished relative moves in the script's pixel coordinates. Client-thread only. */
final class Movement {
    record Delta(double x, double y) {}
    static final class AdvanceFailure extends IllegalArgumentException {
        final int move;

        AdvanceFailure(String message, int move) {
            super(message);
            this.move = move;
        }
    }
    // The requested speed is a scalar for straight moves, or an (x,y) pair for per-axis moves.
    record Pending<S>(Delta remaining_px, S requested_speed_px_per_tick) {}

    List<Pending<?>> inspect() {
        var result = new ArrayList<Pending<?>>(active.size());
        for (var move : active) {
            var remaining = new Delta(move.x.remaining, move.y.remaining);
            if (move.straight) result.add(new Pending<>(remaining, move.speedX));
            else result.add(new Pending<>(remaining, new Delta(move.speedX, move.speedY)));
        }
        return result;
    }

    private static final class Axis {
        double remaining, roundoff;
        final double halfUlp;

        Axis(double remaining) {
            this.remaining = remaining;
            halfUlp = Math.ulp(remaining) / 2;
        }

        Axis(Axis source) {
            remaining = source.remaining;
            roundoff = source.roundoff;
            halfUlp = source.halfUlp;
        }

        void consume(double step) {
            if (step == 0) return;
            if (step == remaining) {
                remaining = 0;
                roundoff = 0;
                return;
            }
            // Compensated subtraction prevents fractional speeds from accumulating a phantom last tick.
            double corrected = step - roundoff;
            double next = remaining - corrected;
            roundoff = (remaining - next) - corrected;
            // Binary representations of decimal speeds (e.g. 0.35) can leave a
            // residue even after compensated subtraction. Use the target's own
            // precision so representable extra distance and tiny targets survive.
            remaining = Math.abs(next) > halfUlp && Math.signum(next) == Math.signum(remaining) ? next : 0;
        }
    }

    private static final class Move {
        final Axis x, y;
        double requestedX, requestedY;
        final double speedX, speedY;
        final boolean straight;

        Move(double x, double y, double speedX, double speedY, boolean straight) {
            this.x = new Axis(x);
            this.y = new Axis(y);
            this.speedX = speedX;
            this.speedY = speedY;
            this.straight = straight;
        }

        Move(Move source) {
            x = new Axis(source.x);
            y = new Axis(source.y);
            speedX = source.speedX;
            speedY = source.speedY;
            straight = source.straight;
        }

        void requestStep() {
            double remainingX = x.remaining, remainingY = y.remaining;
            if (straight) {
                double length = Math.hypot(remainingX, remainingY);
                if (length > speedX) {
                    requestedX = (remainingX / length) * speedX;
                    requestedY = (remainingY / length) * speedX;
                    return;
                }
                requestedX = remainingX;
                requestedY = remainingY;
            } else {
                requestedX = Math.copySign(Math.min(Math.abs(remainingX), speedX), remainingX);
                requestedY = Math.copySign(Math.min(Math.abs(remainingY), speedY), remainingY);
            }
        }
    }

    private final ArrayList<Move> active = new ArrayList<>();

    boolean pending() { return !active.isEmpty(); }
    int size() { return active.size(); }
    void clear() { active.clear(); }

    Movement copy() {
        var result = new Movement();
        for (var move : active) result.active.add(new Move(move));
        return result;
    }

    void addStraight(double x, double y, double speed) {
        if (!Double.isFinite(Math.hypot(x, y)) || !Double.isFinite(speed) || speed <= 0)
            throw new IllegalArgumentException("invalid straight movement or speed");
        if (x != 0 || y != 0) active.add(new Move(x, y, speed, speed, true));
    }

    void addPerAxis(double x, double y, double speedX, double speedY) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(speedX) || !Double.isFinite(speedY)
                || speedX < 0 || speedY < 0 || (x != 0 && speedX == 0) || (y != 0 && speedY == 0))
            throw new IllegalArgumentException("a moving axis needs a finite positive speed");
        if (x != 0 || y != 0) active.add(new Move(x, y, speedX, speedY, false));
    }

    Delta advance(double cap) {
        double sumX = 0, sumY = 0;
        for (var move : active) {
            move.requestStep();
            sumX += move.requestedX;
            sumY += move.requestedY;
        }
        double length = Math.hypot(sumX, sumY);
        if (!Double.isFinite(length)) throw new AdvanceFailure("combined movement is out of range", -1);
        double scale = length <= cap ? 1 : cap / length;
        for (int index = 0; index < active.size(); index++) {
            var move = active.get(index);
            double oldX = move.x.remaining, oldY = move.y.remaining;
            move.x.consume(move.requestedX * scale);
            move.y.consume(move.requestedY * scale);
            if (oldX == move.x.remaining && oldY == move.y.remaining)
                throw new AdvanceFailure("movement speed is too small for the requested distance", index);
        }
        active.removeIf(move -> move.x.remaining == 0 && move.y.remaining == 0);
        return new Delta(sumX * scale, sumY * scale);
    }
}
