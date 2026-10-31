package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/**
 * Removes vanilla environment polling from an already-probed empty virtual
 * flight tick. Cancelling Projectile.tick does not cancel the overriding CBC
 * tick: it only replaces its super call with the tiny state update below.
 */
@Mixin(Projectile.class)
public abstract class ProjectileMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$lightweightEmptyAirBaseTick(CallbackInfo ci) {
        Projectile self = (Projectile) (Object) this;
        if (!(self instanceof AbstractCannonProjectile projectile)
                || !HighAltitudeProjectileTransit.isLightweightBaseTick(projectile)) {
            return;
        }

        // Preserve the timing/interpolation state which Entity.baseTick would
        // normally advance, without touching blocks, fluids, chunks or portals.
        projectile.xo = projectile.getX();
        projectile.yo = projectile.getY();
        projectile.zo = projectile.getZ();
        projectile.yRotO = projectile.getYRot();
        projectile.xRotO = projectile.getXRot();
        projectile.tickCount++;
        ci.cancel();
    }
}
