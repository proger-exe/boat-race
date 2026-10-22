package dev.sh3lby.race;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The square test track mirrors the shipped config: finish at x=0 crossed towards +X, three checkpoints. */
class LapTrackerTest {

    private static final Zone FINISH = Zone.ofBlocks(0, 62, 46, 0, 70, 54);
    private static final List<Zone> CHECKPOINTS = List.of(
            Zone.ofBlocks(46, 62, 0, 54, 70, 0),
            Zone.ofBlocks(0, 62, -54, 0, 70, -46),
            Zone.ofBlocks(-54, 62, 0, -46, 70, 0));
    private static final Vector START = at(-3, 50);
    private static final Vector SOUTH_EAST = at(50, 50);
    private static final Vector NORTH_EAST = at(50, -50);
    private static final Vector NORTH_WEST = at(-50, -50);
    private static final Vector SOUTH_WEST = at(-50, 50);

    private static Vector at(double x, double z) {
        return new Vector(x, 63, z);
    }

    private static LapTracker tracker() {
        return new LapTracker(new LapTracker.Track(CHECKPOINTS, FINISH, new Vector(1, 0, 0)));
    }

    /** Drives the waypoints in one-block steps, like a boat sampled every tick. */
    private static int drive(LapTracker tracker, Vector... waypoints) {
        int laps = 0;
        Vector from = waypoints[0];
        for (int i = 1; i < waypoints.length; i++) {
            Vector to = waypoints[i];
            int steps = Math.max(1, (int) Math.ceil(from.distance(to)));
            Vector leg = to.clone().subtract(from);
            for (int step = 1; step <= steps; step++) {
                Vector next = from.clone().add(leg.clone().multiply((double) step / steps));
                if (tracker.move(from.clone().add(leg.clone().multiply((double) (step - 1) / steps)), next)) laps++;
            }
            from = to;
        }
        return laps;
    }

    @Test
    void fullRouteCountsEveryLap() {
        LapTracker tracker = tracker();
        assertEquals(2, drive(tracker,
                START, SOUTH_EAST,
                NORTH_EAST, NORTH_WEST, SOUTH_WEST, SOUTH_EAST,
                NORTH_EAST, NORTH_WEST, SOUTH_WEST, SOUTH_EAST));
        assertEquals(2, tracker.laps());
    }

    @Test
    void ridingBackAndForthOverFinishCountsNothing() {
        LapTracker tracker = tracker();
        assertEquals(0, drive(tracker, START, at(10, 50), START, at(10, 50), START, at(10, 50)));
    }

    @Test
    void skippedCheckpointCountsNothing() {
        LapTracker tracker = tracker();
        // Straight across the middle: only the first checkpoint on the way, the others missed.
        assertEquals(0, drive(tracker, START, SOUTH_EAST, NORTH_EAST, SOUTH_WEST, SOUTH_EAST));
        assertEquals(1, tracker.nextCheckpoint());
    }

    @Test
    void sittingInsideTheFinishCountsNothing() {
        LapTracker tracker = tracker();
        drive(tracker, START, SOUTH_EAST, NORTH_EAST, NORTH_WEST, SOUTH_WEST);
        Vector inside = at(0.5, 50);
        int laps = 0;
        for (int tick = 0; tick < 40; tick++) {
            if (tracker.move(inside, inside)) laps++;
        }
        assertEquals(0, laps);
    }

    @Test
    void finishCrossedTheWrongWayCountsNothing() {
        LapTracker tracker = tracker();
        drive(tracker, START, SOUTH_EAST, NORTH_EAST, NORTH_WEST, SOUTH_WEST);
        assertFalse(tracker.move(at(5, 50), at(-5, 50)), "крест финиша против направления");
        assertTrue(tracker.move(at(-5, 50), at(5, 50)), "крест финиша по направлению");
        assertEquals(1, tracker.laps());
    }

    @Test
    void oneTickJumpStillHitsANarrowCheckpoint() {
        LapTracker tracker = tracker();
        // 40 blocks in a single step, the checkpoint plane is one block thick.
        assertEquals(0, drive(tracker, START, SOUTH_EAST));
        assertEquals(0, tracker.nextCheckpoint());
        tracker.move(at(50, 20), at(50, -20));
        assertEquals(1, tracker.nextCheckpoint(), "узкий чекпоинт пройден одним шагом");
    }

}
