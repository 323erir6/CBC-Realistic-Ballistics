package dev.cbcrealisticballistics;

public record NuclearBlastProfile(
        int craterRadius,
        int craterDepth,
        int rimWidth,
        int rimHeight,
        int constructDamageRadius,
        int glassRadius
) {
    private static final NuclearBlastProfile[] PROFILES = {
            new NuclearBlastProfile(50, 12, 8, 3, 56, 400),
            new NuclearBlastProfile(80, 18, 12, 5, 88, 650),
            new NuclearBlastProfile(90, 22, 14, 6, 100, 850),
            new NuclearBlastProfile(240, 40, 28, 12, 260, 1800)
    };

    public static NuclearBlastProfile byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < PROFILES.length ? PROFILES[ordinal] : PROFILES[0];
    }

    public int outerTerrainRadius() {
        return craterRadius + rimWidth;
    }

    public int craterFloorY(int groundY, double horizontalDistance) {
        double u = Math.min(1.0, horizontalDistance / craterRadius);
        double bowl = Math.pow(Math.max(0.0, 1.0 - u * u), 1.5);
        return groundY - Math.max(0, (int) Math.round(craterDepth * bowl));
    }

    public int rimLayers(double horizontalDistance) {
        if (horizontalDistance < craterRadius || horizontalDistance > outerTerrainRadius()) return 0;
        double u = (horizontalDistance - craterRadius) / rimWidth;
        double bell = Math.sin(Math.PI * u);
        return Math.max(0, (int) Math.round(rimHeight * bell * bell));
    }
}
