package dev.cbcrealisticballistics.access;

/**
 * Exposes the already-computed, non-loading collision broad-phase result to
 * the low-altitude ticket planner. This avoids running a second ray test just
 * to decide whether route chunks need to be streamed.
 */
public interface CollisionProbeAccess {
    boolean cbcRealisticBallistics$wasCollisionProbeClear(int projectileTick);

    net.minecraft.world.phys.Vec3 cbcRealisticBallistics$getCollisionMovement();
}
