package dev.cbcrealisticballistics.mixin;

import com.example.cbcenhancedshells.NukeFxProfile;
import dev.cbcrealisticballistics.NuclearBlastManager;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.example.cbcenhancedshells.shells.NukeShellProjectile", remap = false)
public abstract class NukeShellProjectileMixin {
    @Shadow
    protected abstract NukeFxProfile getFxProfile();

    @Inject(method = "spawnShockwaveRings", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$replaceExplosionRings(
            Position position,
            ServerLevel level,
            CallbackInfo callback
    ) {
        NuclearBlastManager.start(
                level, position.x(), position.y(), position.z(), getFxProfile().ordinal());
        callback.cancel();
    }

    @ModifyConstant(
            method = "sendCloudFx",
            constant = @Constant(doubleValue = 1536.0)
    )
    private double cbcRealisticBallistics$extendNuclearFxRange(double original) {
        return 1024.0 * 16.0;
    }
}
