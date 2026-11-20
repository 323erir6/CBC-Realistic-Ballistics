package dev.cbcrealisticballistics.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

@Pseudo
@Mixin(targets = {
        "riftyboi.cbcmodernwarfare.munitions.medium_cannon.AbstractMediumcannonProjectile",
        "com.cainiao1053.cbcmoreshells.munitions.dual_cannon.AbstractDualCannonProjectile",
        "com.dsvv.cbcat.cannon.heavy_autocannon.munitions.AbstractHeavyAutocannonProjectile"
})
public abstract class AddonProjectileTransitMixin {
    @WrapOperation(method = "tick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;hasChunk(II)Z"), require = 0)
    private boolean cbcRealisticBallistics$keepNativeTickDuringTransit(
            Level level, int chunkX, int chunkZ, Operation<Boolean> original
    ) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        // These guards precede the call to CBC's tick. Do not touch the separate
        // checks used by fuze callbacks and block queries.
        return original.call(level, chunkX, chunkZ)
                || (level instanceof ServerLevel && !projectile.isInGround()
                && HighAltitudeProjectileTransit.isActive(projectile));
    }
}
