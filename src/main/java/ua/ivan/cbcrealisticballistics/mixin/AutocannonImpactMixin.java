package ua.ivan.cbcrealisticballistics.mixin;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import ua.ivan.cbcrealisticballistics.access.ImpactVelocityAccess;

@Mixin(AbstractAutocannonProjectile.class)
public abstract class AutocannonImpactMixin {
    @Inject(method = "calculateBlockPenetration", at = @At("HEAD"))
    private void cbcRealisticBallistics$beforePenetration(ProjectileContext context, BlockState state,
                                                           BlockHitResult hit,
                                                           CallbackInfoReturnable<AbstractCannonProjectile.ImpactResult> cir) {
        ((ImpactVelocityAccess) this).cbcRealisticBallistics$beginImpactVelocity();
    }

    @Inject(method = "calculateBlockPenetration", at = @At("RETURN"))
    private void cbcRealisticBallistics$afterPenetration(ProjectileContext context, BlockState state,
                                                          BlockHitResult hit,
                                                          CallbackInfoReturnable<AbstractCannonProjectile.ImpactResult> cir) {
        ((ImpactVelocityAccess) this).cbcRealisticBallistics$endImpactVelocity();
    }
}
