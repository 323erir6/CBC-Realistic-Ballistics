package dev.cbcrealisticballistics;

import java.util.ArrayList;
import java.util.List;

public final class ChunkCorridorTest {
    public static void main(String[] args) {
        expect(15, 17, 16, 16, "0,1", "1,1");
        expect(17, 15, 16, 16, "1,0", "1,1");
        expect(1, 47, 32, 16, "0,2", "1,1", "2,1");
        expect(32, 16, 1, 47, "2,1", "1,1", "0,2");
        expect(-33, -31, -32, -32, "-3,-2", "-2,-2");
        expect(1, 1, 33, 33, "0,0", "1,1", "2,2");
        expect(1, 1, 65, 1, "0,0", "1,0", "2,0", "3,0", "4,0");
        expect(0.0, 3, -0.0, 3, "0,0");
        expect(0.0, 31, -0.0, -17, "0,1", "0,0", "0,-1", "0,-2");

        if (ChunkCorridor.trace(0, 0, Double.NaN, 20, (x, z) -> {
            throw new AssertionError("Invalid coordinates reached the visitor");
        })) throw new AssertionError("Invalid segment reported as covered");

        int[] visited = {0};
        boolean complete = ChunkCorridor.trace(1, 1, 1000, 1, (x, z) -> ++visited[0] < 3);
        if (complete || visited[0] != 3) throw new AssertionError("Visitor could not stop the scan");
    }

    private static void expect(double x0, double z0, double x1, double z1, String... expected) {
        List<String> cells = new ArrayList<>();
        boolean complete = ChunkCorridor.trace(x0, z0, x1, z1, (x, z) -> {
            cells.add(x + "," + z);
            return cells.size() <= expected.length;
        });
        if (!complete || !cells.equals(List.of(expected))) {
            throw new AssertionError("Expected " + List.of(expected) + " but visited " + cells);
        }
    }
}
