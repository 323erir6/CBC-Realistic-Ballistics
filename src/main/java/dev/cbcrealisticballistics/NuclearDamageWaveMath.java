package dev.cbcrealisticballistics;

/** Pure timing and attenuation math shared by the server wave and regressions. */
final class NuclearDamageWaveMath {
    static final float PEAK_DAMAGE = 15_000.0f;
    static final double WAVE_SPEED_BLOCKS_PER_TICK = 343.0 / 20.0;

    private NuclearDamageWaveMath() {
    }

    static double radius(long elapsedTicks, double maximumRadius) {
        if (elapsedTicks < 0L) return -1.0;
        // The first post-detonation server step represents the first 1/20 s.
        return Math.min(maximumRadius, (elapsedTicks + 1L) * WAVE_SPEED_BLOCKS_PER_TICK);
    }

    static float damage(double distance, double maximumRadius) {
        if (maximumRadius <= 0.0 || distance >= maximumRadius) return 0.0f;
        double normalized = Math.max(0.0, distance) / maximumRadius;
        return (float) (PEAK_DAMAGE * (1.0 - normalized * normalized));
    }
}
