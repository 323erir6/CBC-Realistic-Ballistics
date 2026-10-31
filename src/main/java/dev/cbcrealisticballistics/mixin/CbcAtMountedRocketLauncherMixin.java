package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.CbcAtSableRocketLaunchFix;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.cannon_control.contraption.PitchOrientedContraptionEntity;

@Pseudo
@Mixin(targets = {
        "com.dsvv.cbcat.cannon.rocketpod.contraption.MountedRocketPodContraption",
        "com.dsvv.cbcat.cannon.medium_rocketpod.contraption.MountedMediumRocketRailContraption"
}, priority = 900)
public abstract class CbcAtMountedRocketLauncherMixin {
    @Inject(method = "fireShot", at = @At("HEAD"))
    private void cbcRealisticBallistics$beginSableRocketLaunch(
            ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) {
        CbcAtSableRocketLaunchFix.begin((Entity) entity);
    }

    @Inject(method = "fireShot", at = @At("RETURN"))
    private void cbcRealisticBallistics$endSableRocketLaunch(
            ServerLevel level, PitchOrientedContraptionEntity entity, CallbackInfo ci) {
        CbcAtSableRocketLaunchFix.end();
    }

    /**
     * Capture CBC AT's untouched launcher-space vector at the only reliable
     * point: immediately before the projectile enters ServerLevel. Capturing
     * from a ServerLevel HEAD injector is order-dependent because Sable and
     * other compatibility mods inject into the same method.
     */
    @Redirect(
            method = "fireShot",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerLevel;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"
            ),
            require = 0
    )
    private boolean cbcRealisticBallistics$captureRocketBeforeSable(
            ServerLevel level, Entity projectile) {
        CbcAtSableRocketLaunchFix.captureAtLauncher(projectile);
        return level.addFreshEntity(projectile);
    }
}
