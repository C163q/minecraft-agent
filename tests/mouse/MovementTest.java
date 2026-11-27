package mcagent;

import java.util.List;

/** Run with: java -cp <compiled classes> mcagent.MovementTest */
public class MovementTest {
    static void step(Movement motion, double x, double y) {
        var delta = motion.advance(50);
        if (Math.abs(delta.x() - x) > 1e-8 || Math.abs(delta.y() - y) > 1e-8)
            throw new AssertionError(delta + " expected " + x + ", " + y);
    }
    static void done(Movement motion) {
        if (motion.pending()) throw new AssertionError("unexpected remainder");
    }

    static void failedPreviewKeepsBufferedProgress() {
        var buffered = new Movement();
        buffered.addStraight(9, 0, 0.3);
        for (int i = 0; i < 7; i++) step(buffered, 0.3, 0);
        List<Movement.Pending<?>> previous = buffered.inspect();
        var candidate = buffered.copy();
        candidate.addStraight(9e100, 0, 1);
        try {
            candidate.advance(50);
            throw new AssertionError("expected movement speed to be too small to consume distance");
        } catch (Movement.AdvanceFailure expected) {
            if (expected.move != 1) throw new AssertionError("wrong movement blamed", expected);
        }
        if (!previous.equals(buffered.inspect())) throw new AssertionError("preview altered buffered movement");
        for (int i = 0; i < 23; i++) step(buffered, 0.3, 0);
        done(buffered);
    }

    public static void main(String[] args) {
        failedPreviewKeepsBufferedProgress();
        var m = new Movement();
        m.addStraight(160, 0, 80);
        step(m, 50, 0); step(m, 50, 0); step(m, 50, 0); step(m, 10, 0); done(m);
        m.addStraight(80, 0, 80); m.addStraight(-40, 0, 40);
        step(m, 40, 0); done(m);
        m.addStraight(80, 0, 80); m.addStraight(-80, 0, 80);
        step(m, 0, 0); done(m);
        m.addStraight(80, 0, 80); m.addStraight(40, 0, 20);
        step(m, 50, 0); step(m, 50, 0); step(m, 20, 0); done(m);
        m.addStraight(30, 40, 10);
        for (int i = 0; i < 5; ++i) step(m, 6, 8);
        done(m);
        m.addStraight(1, 0, 0.1);
        for (int i = 0; i < 10; ++i) step(m, 0.1, 0);
        done(m);
        // Decimal script speeds whose binary products fall just short of the target.
        m.addStraight(9, 0, 0.3);
        for (int i = 0; i < 30; ++i) step(m, 0.3, 0);
        done(m);
        m.addPerAxis(0, -7, 0, 0.35);
        for (int i = 0; i < 20; ++i) step(m, 0, -0.35);
        done(m);
        // Shared cap halves both 0.6 requests. Neither may hang on a rounding residue.
        m.addStraight(9, 0, 0.6); m.addStraight(9, 0, 0.6);
        for (int i = 0; i < 30; ++i) m.advance(0.6);
        done(m);
        // A real remainder one ulp past the destination must still consume a tick.
        m.addStraight(Math.nextUp(9.0), 0, 0.3);
        for (int i = 0; i < 30; ++i) step(m, 0.3, 0);
        if (!m.pending() || !(m.advance(50).x() > 0)) throw new AssertionError("lost final positive step");
        done(m);
        m.addStraight(3 * Double.MIN_VALUE, 0, 2 * Double.MIN_VALUE);
        if (m.advance(50).x() != 2 * Double.MIN_VALUE || m.advance(50).x() != Double.MIN_VALUE)
            throw new AssertionError("lost subnormal movement");
        done(m);
        m.addPerAxis(30, -40, 10, 10);
        for (int i = 0; i < 3; ++i) step(m, 10, -10);
        step(m, 0, -10); done(m);
        m.addStraight(60, 80, 50); step(m, 30, 40);
        m.clear(); step(m, 0, 0); done(m);
        m.addPerAxis(20, 0, 10, 0); step(m,10,0); step(m,10,0); done(m);
        try { m.addPerAxis(20, 10, 0, 10); throw new AssertionError("unreachable movement accepted"); }
        catch (IllegalArgumentException expected) {}
    }
}
