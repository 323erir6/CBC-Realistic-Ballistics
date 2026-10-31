package dev.cbcrealisticballistics.mixin;

import net.minecraft.core.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import rbasamoyai.createbigcannons.munitions.big_cannon.he_shell.HEShellProjectile;

/**
 * Makes an HE projectile's explosion strictly single-shot.
 *
 * <p>CBC may reach {@code detonate(Position)} twice during one entity tick: an
 * impact/clip fuze can detonate while the superclass tick subsequently sees the
 * same fuze as triggered and detonates again before {@code removeNextTick} is
 * processed. Guarding the callbacks which lead to detonation is insufficient,
 * because the second call comes directly from {@code tick()}.</p>
 */
@Mixin(HEShellProjectile.class)
public abstract class HEShellProjectileMixin {
    @Unique
    private boolean cbcRealisticBallistics$explosionExecuted;

    @Inject(method = "detonate", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$executeExplosionOnce(
            Position position,
            CallbackInfo ci
    ) {
        if (this.cbcRealisticBallistics$explosionExecuted) {
            ci.cancel();
            return;
        }

        // Commit before CBC starts the explosion. This also protects against a
        // nested/re-entrant callback caused by block or Sable interaction.
        this.cbcRealisticBallistics$explosionExecuted = true;
    }
}
