package dev.cbcrealisticballistics.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.lang.reflect.Field;

/**
 * Gives Military Supplement's small/medium/large/giant cannon families a
 * deterministic accuracy improvement from a longer physical barrel.
 *
 * <p>The target is optional, so CBC Realistic Ballistics still starts when
 * Military Supplement is not installed. The modification is applied to the
 * final spread argument after the addon has combined projectile, command,
 * equipment and penalty modifiers; none of those mechanics are bypassed.</p>
 */
@Pseudo
@Mixin(
        targets = "com.cainiao1053.cbcmoreshells.cannon_control.contraption.MountedDualCannonContraption",
        remap = false
)
public abstract class MountedDualCannonContraptionMixin {
    @Unique
    private static volatile Field cbcRealisticBallistics$frontExtensionLengthField;

    @Unique
    private static volatile boolean cbcRealisticBallistics$frontExtensionLengthLookupDone;

    @ModifyArg(
            method = "fireShot",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/cainiao1053/cbcmoreshells/munitions/dual_cannon/AbstractDualCannonProjectile;shoot(DDDFF)V"
            ),
            index = 4,
            require = 0
    )
    private float cbcRealisticBallistics$scaleSpreadByBarrelLength(float originalSpread) {
        int barrelLengthMetres = Math.max(0, cbcRealisticBallistics$getBarrelLength());
        float multiplier;
        if (barrelLengthMetres >= 6) {
            multiplier = 0.0F;
        } else if (barrelLengthMetres <= 3) {
            multiplier = 1.0F;
        } else {
            multiplier = 1.0F / (1 << (barrelLengthMetres - 3));
        }
        return originalSpread * multiplier;
    }

    /**
     * The barrel-length counter is declared by CBC's superclass rather than by
     * Military Supplement's concrete contraption. A Mixin {@code @Shadow}
     * cannot target that inherited field, so resolve it once through the class
     * hierarchy and cache the result. Falling back to three metres preserves
     * the addon's original spread if a future addon version renames the field.
     */
    @Unique
    private int cbcRealisticBallistics$getBarrelLength() {
        Field field = cbcRealisticBallistics$frontExtensionLengthField;
        if (!cbcRealisticBallistics$frontExtensionLengthLookupDone) {
            synchronized (MountedDualCannonContraptionMixin.class) {
                if (!cbcRealisticBallistics$frontExtensionLengthLookupDone) {
                    Class<?> type = this.getClass();
                    while (type != null) {
                        try {
                            field = type.getDeclaredField("frontExtensionLength");
                            field.setAccessible(true);
                            cbcRealisticBallistics$frontExtensionLengthField = field;
                            break;
                        } catch (NoSuchFieldException ignored) {
                            type = type.getSuperclass();
                        }
                    }
                    cbcRealisticBallistics$frontExtensionLengthLookupDone = true;
                }
                field = cbcRealisticBallistics$frontExtensionLengthField;
            }
        }

        if (field != null) {
            try {
                return field.getInt(this);
            } catch (IllegalAccessException ignored) {
                // Preserve the original spread if reflection is restricted.
            }
        }
        return 3;
    }
}
