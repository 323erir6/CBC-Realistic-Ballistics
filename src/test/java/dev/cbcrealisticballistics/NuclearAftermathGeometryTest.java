package dev.cbcrealisticballistics;

import net.minecraft.world.level.ChunkPos;

public final class NuclearAftermathGeometryTest {
    private NuclearAftermathGeometryTest() {
    }

    public static void main(String[] args) {
        NuclearBlastHistory.BlastRecord blast = new NuclearBlastHistory.BlastRecord(
                7L, "nuke_mk1", 0,
                0.0, 64.0, 0.0, 100L,
                50, 400
        );
        if (!blast.intersects(new ChunkPos(24, 0))) {
            throw new AssertionError("Chunk touching the persistent glass radius must be processed");
        }
        if (blast.intersects(new ChunkPos(26, 0))) {
            throw new AssertionError("Chunk outside the persistent glass radius must not be processed");
        }

        double near = NuclearAftermathManager.inverseSquareEnergy(blast, 100.0 * 100.0);
        double far = NuclearAftermathManager.inverseSquareEnergy(blast, 200.0 * 200.0);
        if (Math.abs(near / far - 4.0) > 1.0e-9) {
            throw new AssertionError("Pressure must decay with the inverse square of distance");
        }
        double straight = NuclearAftermathManager.bendAttenuation(0.0, 0);
        double oneTurn = NuclearAftermathManager.bendAttenuation(0.4, 1);
        double twoTurns = NuclearAftermathManager.bendAttenuation(0.8, 2);
        if (!(straight == 1.0 && straight > oneTurn && oneTurn > twoTurns)) {
            throw new AssertionError("Every wave turn must consume additional energy");
        }
    }
}
