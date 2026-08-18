package ua.ivan.cbcrealisticballistics.access;

/**
 * Implemented on CBC projectiles by the common projectile mixin.
 * Kept outside the configured mixin package so transformed CBC classes may
 * reference it safely at runtime.
 */
public interface ImpactVelocityAccess {
    void cbcRealisticBallistics$beginImpactVelocity();

    void cbcRealisticBallistics$endImpactVelocity();
}
