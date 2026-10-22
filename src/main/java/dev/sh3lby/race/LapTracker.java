package dev.sh3lby.race;

import org.bukkit.util.Vector;

import java.util.List;

/**
 * Lap progress of one racer. No Bukkit state, so it is unit testable.
 * A lap counts only after every checkpoint was crossed in order and the finish
 * is then crossed in the allowed direction.
 */
final class LapTracker {

    record Track(List<Zone> checkpoints, Zone finish, Vector finishDirection) {
    }

    private final Track track;
    private int nextCheckpoint;
    private int laps;

    LapTracker(Track track) {
        this.track = track;
    }

    int laps() {
        return laps;
    }

    int nextCheckpoint() {
        return nextCheckpoint;
    }

    /** Feeds one movement step; returns true when it completed a lap. */
    boolean move(Vector from, Vector to) {
        double lastCheckpointT = -1;
        while (nextCheckpoint < track.checkpoints().size()) {
            double t = track.checkpoints().get(nextCheckpoint).crossT(from, to);
            if (Double.isNaN(t)) break;
            lastCheckpointT = t;
            nextCheckpoint++;
        }
        if (nextCheckpoint < track.checkpoints().size()) return false;

        double finishT = track.finish().crossT(from, to);
        if (Double.isNaN(finishT) || finishT < lastCheckpointT) return false;
        if (to.clone().subtract(from).dot(track.finishDirection()) <= 0) return false;

        laps++;
        nextCheckpoint = 0;
        return true;
    }
}
