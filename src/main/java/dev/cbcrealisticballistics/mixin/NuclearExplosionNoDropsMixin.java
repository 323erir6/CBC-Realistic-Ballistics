package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.NuclearProjectiles;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiConsumer;

@Mixin(Explosion.class)
public abstract class NuclearExplosionNoDropsMixin {
    @Redirect(
            method = "finalizeExplosion",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/state/BlockState;onExplosionHit(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/Explosion;Ljava/util/function/BiConsumer;)V"
            )
    )
    private void cbcRealisticBallistics$suppressNuclearDrops(
            BlockState state,
            Level level,
            BlockPos pos,
            Explosion explosion,
            BiConsumer<ItemStack, BlockPos> dropConsumer
    ) {
        if (!NuclearProjectiles.isNuclear(explosion.getDirectSourceEntity())) {
            state.onExplosionHit(level, pos, explosion, dropConsumer);
        }
        // Entity damage and blast impulse were already calculated by explode().
        // Terrain and Sable blocks are changed by NuclearBlastManager so the
        // result is continuous, drop-free and spread over multiple ticks.
    }
}
