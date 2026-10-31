package dev.cbcrealisticballistics.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;

/** Merges loaded vanilla blocks into Sable's no-main-level virtual ray. */
@Mixin(value = BlockGetter.class, priority = 900)
public interface LevelMixin {
    @Inject(method = "clip", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$skipProvenEmptyRay(
            ClipContext context, CallbackInfoReturnable<BlockHitResult> cir
    ) {
        if ((Object) this instanceof ServerLevel level
                && HighAltitudeProjectileTransit.isProvenEmptyRay(level, context)) {
            var to = context.getTo();
            var direction = context.getFrom().subtract(to);
            cir.setReturnValue(BlockHitResult.miss(to,
                    net.minecraft.core.Direction.getNearest(direction.x, direction.y, direction.z),
                    net.minecraft.core.BlockPos.containing(to)));
        }
    }

    @Inject(method = "clip", at = @At("RETURN"), cancellable = true)
    private void cbcRealisticBallistics$mergeLoadedBlocksIntoVirtualRay(
            ClipContext context,
            CallbackInfoReturnable<BlockHitResult> cir
    ) {
        BlockGetter self = (BlockGetter) (Object) this;
        if (self instanceof ServerLevel serverLevel
                && HighAltitudeProjectileTransit.isNoMainLevelCollisionActive()) {
            cir.setReturnValue(HighAltitudeProjectileTransit.mergeLoadedMainLevelHit(
                    serverLevel,
                    context,
                    cir.getReturnValue()
            ));
        }
    }
}
