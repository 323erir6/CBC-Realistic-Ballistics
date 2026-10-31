package dev.cbcrealisticballistics.mixin;

import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;

@Mixin(ClipContext.class)
public abstract class ClipContextMixin {
    @Inject(
            method = "<init>(Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/level/ClipContext$Block;Lnet/minecraft/world/level/ClipContext$Fluid;Lnet/minecraft/world/phys/shapes/CollisionContext;)V",
            at = @At("RETURN")
    )
    private void cbcRealisticBallistics$ignoreOnlyMainLevelDuringVirtualFlight(
            Vec3 from,
            Vec3 to,
            ClipContext.Block block,
            ClipContext.Fluid fluid,
            CollisionContext collisionContext,
            CallbackInfo ci
    ) {
        if (HighAltitudeProjectileTransit.isNoMainLevelCollisionActive()) {
            HighAltitudeProjectileTransit.configureClipContext(this);
        }
    }
}
