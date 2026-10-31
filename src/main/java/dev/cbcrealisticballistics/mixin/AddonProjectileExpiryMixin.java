package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.ProjectileLifetimeController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Shared compatibility for Military Supplement projectile bases which own an
 * independent lifetime counter. Optional targets let one build support both
 * the current release and older/newer addon layouts without a hard dependency.
 */
@Pseudo
@Mixin(targets = {
        "com.cainiao1053.cbcmoreshells.munitions.big_cannon.ShellessFuzedBigCannonProjectile",
        "com.cainiao1053.cbcmoreshells.munitions.big_cannon.FuzedCannonTorpedoProjectile",
        "com.cainiao1053.cbcmoreshells.munitions.dual_cannon.FuzedDualCannonProjectile",
        "com.cainiao1053.cbcmoreshells.munitions.racked_projectile.FuzedRackedProjectile",
        "com.cainiao1053.cbcmoreshells.munitions.racked_projectile.FuzedDualRackedProjectile"
})
public abstract class AddonProjectileExpiryMixin {
    @Inject(method = "expireProjectile", at = @At("HEAD"), cancellable = true, require = 0)
    private void cbcRealisticBallistics$useBallisticsLifetime(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (ProjectileLifetimeController.preventNativeExpiry(projectile)) {
            ci.cancel();
        }
    }
}
