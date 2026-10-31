package dev.cbcrealisticballistics;

import net.minecraft.world.level.ChunkPos;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class NuclearScorchChunkSupportTest {
    private NuclearScorchChunkSupportTest() {
    }

    public static void main(String[] args) {
        verifyCircleCoverage(0.25, 0.75, 140);
        verifyCircleCoverage(-319.4, -1002.8, 96);
        verifyCircleCoverage(15.9, -16.1, 17);
        if (NuclearVisualTracker.VISUAL_RANGE != 16384.0) {
            throw new AssertionError("Nuclear visual range must be exactly 1024 chunks");
        }
        System.out.println("PASS: nuclear scorch chunk coverage and 1024-chunk visual range.");
    }

    private static void verifyCircleCoverage(double centerX, double centerZ, int radius) {
        List<Long> chunks = NuclearScorchChunkSupport.intersectingChunks(
                centerX, centerZ, radius
        );
        Set<Long> covered = new HashSet<>(chunks);
        if (covered.size() != chunks.size()) {
            throw new AssertionError("Duplicate chunk tickets were generated");
        }
        int minX = (int) Math.floor(centerX - radius);
        int maxX = (int) Math.ceil(centerX + radius);
        int minZ = (int) Math.floor(centerZ - radius);
        int maxZ = (int) Math.ceil(centerZ + radius);
        double radiusSquared = (double) radius * radius;
        for (int x = minX; x <= maxX; ++x) {
            double dx = x - centerX;
            for (int z = minZ; z <= maxZ; ++z) {
                double dz = z - centerZ;
                if (dx * dx + dz * dz > radiusSquared) {
                    continue;
                }
                long packed = ChunkPos.asLong(x >> 4, z >> 4);
                if (!covered.contains(packed)) {
                    throw new AssertionError(
                            "Scorch circle contains an uncovered column at " + x + ", " + z
                    );
                }
            }
        }
    }
}
