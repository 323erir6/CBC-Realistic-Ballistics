package dev.cbcrealisticballistics;

/** Visits the Minecraft chunk cells traversed by a finite horizontal segment. */
final class ChunkCorridor {
    @FunctionalInterface
    interface Visitor {
        boolean visit(int chunkX, int chunkZ);
    }

    private ChunkCorridor() {
    }

    static boolean trace(double fromX, double fromZ, double toX, double toZ, Visitor visitor) {
        if (!Double.isFinite(fromX) || !Double.isFinite(fromZ)
                || !Double.isFinite(toX) || !Double.isFinite(toZ)) return false;
        int x = ((int) Math.floor(fromX)) >> 4;
        int z = ((int) Math.floor(fromZ)) >> 4;
        int endX = ((int) Math.floor(toX)) >> 4;
        int endZ = ((int) Math.floor(toZ)) >> 4;
        if (!visitor.visit(x, z)) return false;

        double dx = toX - fromX;
        double dz = toZ - fromZ;
        int stepX = Integer.compare(endX, x);
        int stepZ = Integer.compare(endZ, z);
        double deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        double nextX = stepX == 0 ? Double.POSITIVE_INFINITY
                : ((stepX > 0 ? x + 1 : x) * 16.0 - fromX) / dx;
        double nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                : ((stepZ > 0 ? z + 1 : z) * 16.0 - fromZ) / dz;

        while (x != endX || z != endZ) {
            boolean crossX = nextX <= nextZ && x != endX;
            boolean crossZ = nextZ <= nextX && z != endZ;
            if (crossX) {
                x += stepX;
                // At a mixed-sign endpoint the axes may share a boundary
                // time even though only one should cross into a new cell.
                nextX = x == endX ? Double.POSITIVE_INFINITY : nextX + deltaX;
            }
            if (crossZ) {
                z += stepZ;
                nextZ = z == endZ ? Double.POSITIVE_INFINITY : nextZ + deltaZ;
            }
            if (!visitor.visit(x, z)) return false;
        }
        return true;
    }
}
