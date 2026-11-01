package dev.cbcrealisticballistics;

public final class NuclearBlastProfileTest {
    private NuclearBlastProfileTest() {
    }

    public static void main(String[] args) {
        for (int index = 0; index < 4; ++index) {
            NuclearBlastProfile profile = NuclearBlastProfile.byOrdinal(index);
            int center = profile.craterFloorY(100, 0.0);
            int middle = profile.craterFloorY(100, profile.craterRadius() * 0.5);
            int edge = profile.craterFloorY(100, profile.craterRadius());
            if (!(center < middle && middle < edge && edge == 100)) {
                throw new AssertionError("Crater bowl must rise continuously towards the edge");
            }
            int lowerTerrain = profile.craterFloorY(70, profile.craterRadius() * 0.5);
            if (lowerTerrain != middle - 30 || lowerTerrain > 70) {
                throw new AssertionError("Crater floor must follow each column's local terrain");
            }
            if (profile.rimLayers(profile.craterRadius()) != 0
                    || profile.rimLayers(profile.outerTerrainRadius()) != 0
                    || profile.rimLayers(profile.craterRadius() + profile.rimWidth() * 0.5) <= 0) {
                throw new AssertionError("Raised rim must peak between its inner and outer edges");
            }
        }
        if (NuclearBlastProfile.byOrdinal(3).craterRadius() != 240) {
            throw new AssertionError("Largest blast must preserve the addon's former 240-block area");
        }
        NuclearBlastProfile mini = NuclearBlastProfile.byOrdinal(2);
        if (mini.thermalFlashRadius() != 425
                || mini.severeStructureRadius() != 425
                || mini.heavyStructureRadius() != 680) {
            throw new AssertionError("Mini H-bomb aftermath radii are inconsistent");
        }
    }
}
