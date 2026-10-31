package dev.cbcrealisticballistics.mixin;

import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps the addon's immediate thermal pulse but suppresses its second terrain
 * scanner. NuclearBlastManager already owns the drop-free crater and scorch.
 */
@Pseudo
@Mixin(targets = "com.example.cbcenhancedshells.NukeScorchManager", remap = false)
public abstract class NukeScorchManagerMixin {
    @Inject(
            method = "start(Lnet/minecraft/server/level/ServerLevel;DDDF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/example/cbcenhancedshells/NukeScorchManager;thermalPulseEntities(Lnet/minecraft/server/level/ServerLevel;DDDF)V",
                    shift = At.Shift.AFTER,
                    remap = false
            ),
            cancellable = true,
            remap = false
    )
    private static void cbcRealisticBallistics$skipDuplicateTerrainScan(
            ServerLevel level,
            double x,
            double y,
            double z,
            float radius,
            CallbackInfo callback
    ) {
        callback.cancel();
    }
}
