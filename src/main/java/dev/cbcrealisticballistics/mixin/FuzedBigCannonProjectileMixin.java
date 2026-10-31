package dev.cbcrealisticballistics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.munitions.big_cannon.FuzedBigCannonProjectile;

/** A live transit shell must not lose impact/timed/proximity fuze callbacks
 * merely because the terrain chunk under its position is absent. */
@Mixin(FuzedBigCannonProjectile.class)
public abstract class FuzedBigCannonProjectileMixin {
    /**
     * CBC evaluates every entity intersected by one swept projectile segment.
     * A fast HE shell can therefore call onImpact more than once before
     * clipAndDamage gets a chance to set removeNextTick. Each successful call
     * used to execute detonate(), producing two craters from one projectile.
     * Keep the first detonation authoritative and make later callbacks in the
     * same (or any following) tick report the already-decided removal without
     * replaying the explosion.
     */
    @Unique
    private boolean cbcRealisticBallistics$detonationCommitted;

    @WrapOperation(method = "canDetonate", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;hasChunkAt(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean cbcRealisticBallistics$allowTransitFuze(
            Level level, BlockPos position, Operation<Boolean> original
    ) {
        return original.call(level, position)
                || HighAltitudeProjectileTransit.isActive((FuzedBigCannonProjectile) (Object) this);
    }

    @Inject(method = "onImpact", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$rejectRepeatedEntityDetonation(
            HitResult hitResult,
            AbstractCannonProjectile.ImpactResult impactResult,
            ProjectileContext context,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.cbcRealisticBallistics$detonationCommitted) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "onImpact", at = @At("RETURN"))
    private void cbcRealisticBallistics$commitEntityDetonation(
            HitResult hitResult,
            AbstractCannonProjectile.ImpactResult impactResult,
            ProjectileContext context,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            this.cbcRealisticBallistics$detonationCommitted = true;
        }
    }

    @Inject(method = "onClip", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$rejectRepeatedClipDetonation(
            ProjectileContext context,
            net.minecraft.world.phys.Vec3 start,
            net.minecraft.world.phys.Vec3 end,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.cbcRealisticBallistics$detonationCommitted) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "onClip", at = @At("RETURN"))
    private void cbcRealisticBallistics$commitClipDetonation(
            ProjectileContext context,
            net.minecraft.world.phys.Vec3 start,
            net.minecraft.world.phys.Vec3 end,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ()) {
            this.cbcRealisticBallistics$detonationCommitted = true;
        }
    }
}
