package dev.cbcrealisticballistics.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;
import dev.cbcrealisticballistics.ProjectileLifetimeController;

@Mixin(AbstractAutocannonProjectile.class)
public abstract class AbstractAutocannonProjectileMixin {
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasChunk(II)Z")
    )
    private boolean cbcRealisticBallistics$allowVirtualAutocannonTick(
            Level level, int chunkX, int chunkZ
    ) {
        AbstractAutocannonProjectile self = (AbstractAutocannonProjectile) (Object) this;
        return level.hasChunk(chunkX, chunkZ) || HighAltitudeProjectileTransit.isActive(self);
    }

    @Inject(method = "expireProjectile", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$useBallisticsLifetime(CallbackInfo ci) {
        AbstractAutocannonProjectile self =
                (AbstractAutocannonProjectile) (Object) this;
        if (ProjectileLifetimeController.preventNativeExpiry(self)) {
            ci.cancel();
        }
    }
}
