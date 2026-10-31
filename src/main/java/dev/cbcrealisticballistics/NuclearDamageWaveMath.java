package dev.cbcrealisticballistics;

/** Pure timing and attenuation math shared by the server wave and regressions. */
final class NuclearDamageWaveMath {
    static final float PEAK_DAMAGE = 15_000.0f;

    private NuclearDamageWaveMath() {
    }

    static double radius(int profileOrdinal, long elapsedTicks, double maximumRadius) {
        if (elapsedTicks < 0L) return -1.0;
        double radius = switch (profileOrdinal) {
            case 1 -> 16.0 + elapsedTicks * (16.0 / 3.0);
            case 2, 3 -> elapsedTicks < 17L ? -1.0 : (elapsedTicks - 17L) * 2.0;
            default -> 10.0 + elapsedTicks * (10.0 / 3.0);
        };
        return radius < 0.0 ? radius : Math.min(maximumRadius, radius);
    }

    static float damage(double distance, double maximumRadius) {
        if (maximumRadius <= 0.0 || distance >= maximumRadius) return 0.0f;
        double normalized = Math.max(0.0, distance) / maximumRadius;
        return (float) (PEAK_DAMAGE * (1.0 - normalized * normalized));
    }
}
