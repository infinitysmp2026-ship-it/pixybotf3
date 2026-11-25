package com.pixy.bot;

/**
 * Finds the yaw/pitch that makes a thrown projectile (ender pearl, wind charge, ...) land on a target, by simulating
 * the flight tick by tick the way vanilla does (move, drag, gravity) and stopping at the first solid block.
 * Pure Java: the world is passed in as a {@link Solid} lambda.
 */
public final class ProjectileAim {
    private ProjectileAim() { }

    public interface Solid { boolean at(double x, double y, double z); }

    public static final class Result {
        public final float yaw, pitch;      // degrees, Minecraft convention (pitch > 0 = looking down)
        public final double ix, iy, iz;     // where it comes down
        public final boolean hit;           // true if it stopped on a block
        public final double miss;           // distance from the aimed point
        Result(float yaw, float pitch, double ix, double iy, double iz, boolean hit, double miss) {
            this.yaw = yaw; this.pitch = pitch; this.ix = ix; this.iy = iy; this.iz = iz; this.hit = hit; this.miss = miss;
        }
    }

    public static float yawTo(double dx, double dz) { return (float) -Math.toDegrees(Math.atan2(dx, dz)); }

    /** Simulates one throw at the given pitch; returns {impactX, impactY, impactZ, hitFlag}. */
    static double[] fly(double sx, double sy, double sz, float yawDeg, float pitchDeg, double speed,
                        double gravity, double drag, Solid solid, double floorY) {
        double yaw = Math.toRadians(yawDeg), pit = Math.toRadians(pitchDeg);
        double vx = -Math.sin(yaw) * Math.cos(pit) * speed;
        double vy = -Math.sin(pit) * speed;
        double vz = Math.cos(yaw) * Math.cos(pit) * speed;
        double x = sx, y = sy, z = sz;
        for (int t = 0; t < 240; t++) {
            double nx = x + vx, ny = y + vy, nz = z + vz;
            if (solid.at(nx, ny, nz)) return new double[] { x, y, z, 1 };   // last free position
            x = nx; y = ny; z = nz;
            vx *= drag; vy *= drag; vy -= gravity;
            if (y < floorY) break;
        }
        return new double[] { x, y, z, 0 };
    }

    public static Result solve(double sx, double sy, double sz, double tx, double ty, double tz,
                               double speed, double gravity, double drag, Solid solid) {
        float yaw = yawTo(tx - sx, tz - sz);
        double floorY = Math.min(sy, ty) - 40;
        Result best = null;
        for (float p = -75f; p <= 50f; p += 0.5f) {
            double[] r = fly(sx, sy, sz, yaw, p, speed, gravity, drag, solid, floorY);
            double miss = Math.sqrt((r[0] - tx) * (r[0] - tx) + (r[1] - ty) * (r[1] - ty) + (r[2] - tz) * (r[2] - tz));
            if (best == null || miss < best.miss) best = new Result(yaw, p, r[0], r[1], r[2], r[3] > 0, miss);
        }
        return best;
    }
}
