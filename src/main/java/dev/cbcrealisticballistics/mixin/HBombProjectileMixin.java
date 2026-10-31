package dev.cbcrealisticballistics.mixin;

import com.example.cbcenhancedshells.NukeFxProfile;
import dev.cbcrealisticballistics.NuclearBlastManager;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = {
        "com.example.cbcenhancedshells.shells.HBombMiniShellProjectile",
        "com.example.cbcenhancedshells.shells.HBombShellProjectile"
}, remap = false)
public abstract class HBombProjectileMixin {
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
}
