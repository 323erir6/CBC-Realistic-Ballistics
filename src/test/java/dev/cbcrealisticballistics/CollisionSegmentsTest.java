package dev.cbcrealisticballistics;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.Random;

/** Run with gradlew collisionRegressionTest. Throws on every failed invariant. */
public final class CollisionSegmentsTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check(CollisionSegments.count(0, 0, 0) == 1, "stationary");
        check(CollisionSegments.count(16, 0, 0) == 1, "boundary");
        check(CollisionSegments.count(Math.nextUp(16.0), 0, 0) == 2, "boundary overflow");
        check(CollisionSegments.count(Double.NaN, 0, 0) == -1, "NaN fallback");
        check(CollisionSegments.count(0, Double.POSITIVE_INFINITY, 0) == -1, "infinity fallback");
        check(CollisionSegments.count(-1000, 1000, -1000) == 63, "linear diagonal cost");
        Random random = new Random(0xCBC191);
        int hits = 0;
        for (int sample = 0; sample < 20000; sample++) {
            Vec3 from = new Vec3(random.nextDouble() * 60000000 - 30000000,
                    random.nextDouble() * 20000 - 64, random.nextDouble() * 60000000 - 30000000);
            Vec3 delta = new Vec3(random.nextDouble() * 4000 - 2000,
                    random.nextDouble() * 4000 - 2000, random.nextDouble() * 4000 - 2000);
            if (sample % 4 == 0) delta = new Vec3(delta.x, 0, delta.z);
            if (sample % 7 == 0) delta = Vec3.ZERO;
            double width = 0.05 + random.nextDouble() * 5;
            double height = 0.05 + random.nextDouble() * 10;
            double reach = Math.max(width, height) / 2;
            AABB projectile = new AABB(from.x - width / 2, from.y, from.z - width / 2,
                    from.x + width / 2, from.y + height, from.z + width / 2);
            AABB original = projectile.expandTowards(delta).inflate(1);
            AABB base = projectile.inflate(Math.max(1, reach));
            int count = CollisionSegments.count(delta.x, delta.y, delta.z);
            double t = random.nextDouble();
            Vec3 point = from.add(delta.scale(t));
            // Random target next to the continuous ray; both small and large hitboxes.
            double extent = 0.02 + random.nextDouble() * 6;
            Vec3 center = point.add((random.nextDouble() - 0.5) * reach * 3,
                    (random.nextDouble() - 0.5) * reach * 3,
                    (random.nextDouble() - 0.5) * reach * 3);
            AABB target = new AABB(center, center).inflate(extent);
            boolean cbcHit = target.intersects(original)
                    && (target.intersects(projectile)
                    || target.inflate(reach).clip(from, from.add(delta)).isPresent());
            boolean candidate = false;
            for (int i = 0; i < count; i++) {
                AABB segment = CollisionSegments.box(base, delta, i, count);
                check(segment.getXsize() <= base.getXsize() + 16.000001, "bounded X");
                check(segment.getYsize() <= base.getYsize() + 16.000001, "bounded Y");
                check(segment.getZsize() <= base.getZsize() + 16.000001, "bounded Z");
                if (segment.intersects(original) && target.intersects(segment.intersect(original))) candidate = true;
            }
            if (cbcHit) {
                hits++;
                check(candidate, "missed CBC hit, sample " + sample);
            }
            // No gap at a segment boundary, including negative/world-edge coordinates.
            int segmentIndex = Math.min(count - 1, (int) (t * count));
            check(CollisionSegments.box(base, delta, segmentIndex, count).contains(point), "ray gap");
        }
        System.out.println("PASS: 20000 swept paths; preserved " + hits + " CBC hit candidates; boundaries and invalid inputs.");
    }
}
