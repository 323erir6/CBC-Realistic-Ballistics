package dev.cbcrealisticballistics;

public final class NuclearDamageWaveMathTest {
    private NuclearDamageWaveMathTest() {
    }

    public static void main(String[] args) {
        assertClose(NuclearDamageWaveMath.radius(2, 16, 90), -1.0, "mini delay");
        assertClose(NuclearDamageWaveMath.radius(2, 32, 90), 30.0, "mini first ring");
        assertClose(NuclearDamageWaveMath.radius(2, 62, 90), 90.0, "mini final ring");
        assertClose(NuclearDamageWaveMath.radius(3, 137, 240), 240.0, "H-bomb final ring");
        assertClose(NuclearDamageWaveMath.radius(0, 12, 50), 50.0, "nuke final ring");
        assertClose(NuclearDamageWaveMath.damage(0.0, 90.0), 15_000.0, "centre damage");
        assertClose(NuclearDamageWaveMath.damage(45.0, 90.0), 11_250.0, "quadratic damage");
        assertClose(NuclearDamageWaveMath.damage(90.0, 90.0), 0.0, "edge damage");
        System.out.println("PASS: nuclear damage timing and quadratic attenuation.");
    }

    private static void assertClose(double actual, double expected, String name) {
        if (Math.abs(actual - expected) > 1.0e-6) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }
}
