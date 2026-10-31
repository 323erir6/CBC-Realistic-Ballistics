package dev.cbcrealisticballistics;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/** Live entity lookups along a continuous ray; never caches empty cells. */
public final class ProjectileEntitySweep {
    private ProjectileEntitySweep() {}

    public static List<Entity> query(Level level, Entity source, AABB original, Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        int count = CollisionSegments.count(dx, dy, dz);
        if (source == null || count <= 1) return level.getEntities(source, original);
        Vec3 delta = new Vec3(dx, dy, dz);
        double reach = Math.max(source.getBbWidth(), source.getBbHeight()) * 0.5;
        Vec3 sourcePosition = source.position();
        AABB startBox = source.getBoundingBox().move(
                        from.x - sourcePosition.x,
                        from.y - sourcePosition.y,
                        from.z - sourcePosition.z)
                .inflate(Math.max(1.0, reach));
        AABB corridor = startBox.expandTowards(delta).inflate(1.0E-6);
        // Another mod may have replaced the movement region/ray convention.
        // Preserve its behavior instead of pruning an unrecognized query.
        if (original.minX < corridor.minX || original.maxX > corridor.maxX
                || original.minY < corridor.minY || original.maxY > corridor.maxY
                || original.minZ < corridor.minZ || original.maxZ > corridor.maxZ) {
            return level.getEntities(source, original);
        }
        List<Entity> result = null;
        IdentityHashMap<Entity, Boolean> seen = null;
        for (int i = 0; i < count; i++) {
            AABB segment = CollisionSegments.box(startBox, delta, i, count);
            if (!segment.intersects(original)) continue;
            for (Entity entity : level.getEntities(source, segment.intersect(original))) {
                if (seen == null) seen = new IdentityHashMap<>();
                if (seen.put(entity, Boolean.TRUE) == null) {
                    if (result == null) result = new ArrayList<>();
                    result.add(entity);
                }
            }
        }
        return result == null ? List.of() : result;
    }
}
