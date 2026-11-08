package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.ProjectileLifetimeController;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
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
    @Shadow
    public boolean tooManyCharges;

    /**
     * Military Supplement counts the APFSDS round's integrated charge in the
     * same total as the cannon propellant. Its stock limit is 32, so an
     * otherwise valid 30-power launch becomes 34 and the projectile discards
     * itself before moving. Realistic Ballistics owns the high-speed flight
     * model, therefore this legacy failure flag is disabled only for APFSDS.
     */
    @Inject(method = "tick", at = @At("HEAD"), require = 0)
    private void cbcRealisticBallistics$allowHighVelocityApfsds(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        if (typeId != null
                && typeId.getNamespace().equals("cbcmoreshells")
                && typeId.getPath().equals("apfsds_shot")) {
            this.tooManyCharges = false;
        }
    }

    @Inject(method = "expireProjectile", at = @At("HEAD"), cancellable = true, require = 0)
    private void cbcRealisticBallistics$keepVirtualShellessRoundAlive(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (ProjectileLifetimeController.preventNativeExpiry(projectile)) {
            ci.cancel();
        }
    }
}
