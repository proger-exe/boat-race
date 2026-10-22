package dev.sh3lby.race;

import org.bukkit.util.Vector;

/**
 * Axis-aligned box covering the two corner blocks inclusively.
 */
record Zone(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    /** Corners are block coordinates; both corner blocks are fully inside the zone. */
    static Zone ofBlocks(double x1, double y1, double z1, double x2, double y2, double z2) {
        return new Zone(
                Math.floor(Math.min(x1, x2)), Math.floor(Math.min(y1, y2)), Math.floor(Math.min(z1, z2)),
                Math.floor(Math.max(x1, x2)) + 1, Math.floor(Math.max(y1, y2)) + 1, Math.floor(Math.max(z1, z2)) + 1);
    }

    /**
     * Where the movement from -> to enters this zone, as a fraction of the movement,
     * or NaN when the movement never touches it. A move that starts inside returns 0.
     * Segment test, so a fast boat cannot skip a narrow zone between two ticks.
     */
    double crossT(Vector from, Vector to) {
        double tMin = 0, tMax = 1;
        for (int axis = 0; axis < 3; axis++) {
            double o = axis == 0 ? from.getX() : axis == 1 ? from.getY() : from.getZ();
            double d = (axis == 0 ? to.getX() : axis == 1 ? to.getY() : to.getZ()) - o;
            double lo = axis == 0 ? minX : axis == 1 ? minY : minZ;
            double hi = axis == 0 ? maxX : axis == 1 ? maxY : maxZ;
            if (Math.abs(d) < 1.0e-9) {
                if (o < lo || o > hi) return Double.NaN;
                continue;
            }
            double t1 = (lo - o) / d;
            double t2 = (hi - o) / d;
            if (t1 > t2) {
                double swap = t1;
                t1 = t2;
                t2 = swap;
            }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return Double.NaN;
        }
        return tMin;
    }
}
