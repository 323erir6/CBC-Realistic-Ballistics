package dev.cbcrealisticballistics.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;

@Mixin(AbstractBigCannonProjectile.class)
public abstract class AbstractBigCannonProjectileMixin {
    @Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasChunk(II)Z")
    )
    private boolean cbcRealisticBallistics$allowVirtualBigCannonTick(
            Level level, int chunkX, int chunkZ
    ) {
        AbstractBigCannonProjectile self = (AbstractBigCannonProjectile) (Object) this;
        return level.hasChunk(chunkX, chunkZ) || HighAltitudeProjectileTransit.isActive(self);
    }
}
