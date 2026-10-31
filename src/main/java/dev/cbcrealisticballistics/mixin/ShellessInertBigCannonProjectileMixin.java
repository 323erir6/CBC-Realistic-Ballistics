package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.ProjectileLifetimeController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Military Supplement's shell-less rounds (notably APFSDS) own a second
 * lifetime counter which ordinary CBC HE shells do not have. Its configured
 * short lifetime must not override the ballistics mod's authoritative timer.
 * Real impacts, overcharge failure and detonation use different paths.
 */
@Pseudo
@Mixin(targets =
        "com.cainiao1053.cbcmoreshells.munitions.big_cannon.ShellessInertBigCannonProjectile")
public abstract class ShellessInertBigCannonProjectileMixin {
    @Inject(method = "expireProjectile", at = @At("HEAD"), cancellable = true, require = 0)
    private void cbcRealisticBallistics$keepVirtualShellessRoundAlive(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (ProjectileLifetimeController.preventNativeExpiry(projectile)) {
            ci.cancel();
        }
    }
}
