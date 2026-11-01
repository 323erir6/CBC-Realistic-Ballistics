package dev.cbcrealisticballistics;

public final class NuclearDamageWaveMathTest {
    private NuclearDamageWaveMathTest() {
    }

    public static void main(String[] args) {
        assertClose(NuclearDamageWaveMath.radius(0, 850), 17.15, "first sound-speed step");
        assertClose(NuclearDamageWaveMath.radius(49, 850), 850.0, "mini H-bomb wave edge");
        assertClose(NuclearDamageWaveMath.radius(104, 1800), 1800.0, "H-bomb wave edge");
        assertClose(NuclearDamageWaveMath.damage(0.0, 90.0), 15_000.0, "centre damage");
        assertClose(NuclearDamageWaveMath.damage(45.0, 90.0), 11_250.0, "quadratic damage");
        assertClose(NuclearDamageWaveMath.damage(90.0, 90.0), 0.0, "edge damage");
        System.out.println("PASS: sound-speed nuclear wave and quadratic attenuation.");
    }

    private static void assertClose(double actual, double expected, String name) {
        if (Math.abs(actual - expected) > 1.0e-6) {
            throw new AssertionError(name + ": expected " + expected + ", got " + actual);
        }
    }
}
