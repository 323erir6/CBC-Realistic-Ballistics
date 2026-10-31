package dev.cbcrealisticballistics;

/** Geometry only; no Minecraft state, terrain queries or time step changes. */
public final class CollisionSegments {
    public static final double MAX_AXIS_STEP = 16.0;

    private CollisionSegments() {}

    public static int count(double dx, double dy, double dz) {
        double length = Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz)));
        if (!Double.isFinite(length) || length > Integer.MAX_VALUE * MAX_AXIS_STEP) return -1;
        return Math.max(1, (int) Math.ceil(length / MAX_AXIS_STEP));
    }

    public static net.minecraft.world.phys.AABB box(net.minecraft.world.phys.AABB start,
            net.minecraft.world.phys.Vec3 motion, int index, int count) {
        double from = (double) index / count;
        double to = (double) (index + 1) / count;
        double ax = motion.x * from;
        double ay = motion.y * from;
        double az = motion.z * from;
        double bx = motion.x * to;
        double by = motion.y * to;
        double bz = motion.z * to;
        double epsilon = 1.0E-7;
        return new net.minecraft.world.phys.AABB(
                start.minX + Math.min(ax, bx) - epsilon,
                start.minY + Math.min(ay, by) - epsilon,
                start.minZ + Math.min(az, bz) - epsilon,
                start.maxX + Math.max(ax, bx) + epsilon,
                start.maxY + Math.max(ay, by) + epsilon,
                start.maxZ + Math.max(az, bz) + epsilon
        );
    }
}
