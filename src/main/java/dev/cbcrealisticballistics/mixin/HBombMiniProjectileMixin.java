package dev.cbcrealisticballistics.mixin;

import dev.cbcrealisticballistics.NuclearBlastManager;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces the addon's delayed TNT ring with one budgeted mini H-bomb crater. */
@Pseudo
@Mixin(targets = "com.example.cbcenhancedshells.shells.HBombMiniShellProjectile", remap = false)
public abstract class HBombMiniProjectileMixin {
    @Inject(
            method = "spawnShockwaveRings(Lnet/minecraft/core/Position;Lnet/minecraft/server/level/ServerLevel;)V",
            at = @At("HEAD"),
            cancellable = true,
            remap = false
    )
    private void cbcRealisticBallistics$replaceExplosionRings(
            Position position,
            ServerLevel level,
            CallbackInfo callback
    ) {
        NuclearBlastManager.start(level, position.x(), position.y(), position.z(), 2);
        callback.cancel();
    }
}
