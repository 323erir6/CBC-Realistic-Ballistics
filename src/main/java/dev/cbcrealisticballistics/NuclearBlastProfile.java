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

    public int thermalFlashRadius() {
        return Math.max(outerTerrainRadius(), glassRadius / 2);
    }

    public double thermalAltitudeFactor(double burstHeight) {
        double scale = Math.max(24.0, craterRadius * 0.75);
        return 1.0 - Math.exp(-Math.max(0.0, burstHeight) / scale);
    }

    public double thermalFlashRadius(double burstHeight) {
        double expanded = thermalFlashRadius()
                * (1.0 + 0.75 * thermalAltitudeFactor(burstHeight));
        return Math.min(glassRadius / 1.15, expanded);
    }

    public int thermalFlashMaximumRadius(double burstHeight) {
        if (burstHeight <= -surfaceBreakoutDepth()) return 0;
        return Math.min(glassRadius, (int) Math.ceil(thermalFlashRadius(burstHeight) * 1.15));
    }

    public double thermalScorchProbability(
            double horizontalDistance, double burstHeight, double boundaryNoise
    ) {
        double altitude = thermalAltitudeFactor(burstHeight);
        double containment = burstHeight >= 0.0
                ? 1.0
                : Math.max(0.0, 1.0 + burstHeight / surfaceBreakoutDepth());
        double density = (1.0 - 0.60 * altitude) * containment;
        if (density <= 0.0) return 0.0;
        double noise = Math.max(0.0, Math.min(1.0, boundaryNoise));
        double noisyRadius = thermalFlashRadius(burstHeight) * (0.88 + 0.24 * noise);
        if (horizontalDistance >= noisyRadius) return 0.0;
        double solidRadius = noisyRadius * 0.55;
        if (horizontalDistance <= solidRadius) return density;
        double t = (horizontalDistance - solidRadius) / (noisyRadius - solidRadius);
        double smoothFade = 1.0 - t * t * (3.0 - 2.0 * t);
        return density * smoothFade;
    }

    public int severeStructureRadius() {
        return Math.max(outerTerrainRadius(), (int) Math.round(glassRadius * 0.50));
    }

    public int heavyStructureRadius() {
        return Math.max(severeStructureRadius(), (int) Math.round(glassRadius * 0.80));
    }

    public int undergroundCavityRadius() {
        return Math.max(8, Math.max(craterDepth * 2, (int) Math.round(craterRadius / 3.0)));
    }

    public int undergroundCavityVerticalRadius() {
        return Math.max(craterDepth, (int) Math.round(undergroundCavityRadius() * 0.65));
    }

    public int surfaceBreakoutDepth() {
        return undergroundCavityVerticalRadius() + craterDepth / 2;
    }

    public int undergroundCavityHalfHeight(double horizontalDistance) {
        double u = horizontalDistance / undergroundCavityRadius();
        if (u > 1.0) return 0;
        return Math.max(1, (int) Math.ceil(
                undergroundCavityVerticalRadius() * Math.sqrt(Math.max(0.0, 1.0 - u * u))));
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
