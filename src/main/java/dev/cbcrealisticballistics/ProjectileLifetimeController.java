package dev.cbcrealisticballistics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

/**
 * Extends ordinary projectile lifetime counters without cancelling Entity.remove().
 * Deliberately avoiding an Entity removal mixin lets programmable fuzes keep full
 * ownership of their detonation/persistence logic.
 */
public final class ProjectileLifetimeController {
    private static final int PRACTICALLY_INFINITE_LIFETIME = Integer.MAX_VALUE / 4;
    private static final int REFRESH_BELOW = PRACTICALLY_INFINITE_LIFETIME / 2;

    private enum CounterKind {
        REMAINING,
        MAXIMUM,
        IN_GROUND
    }

    private record LifetimeField(Field field, CounterKind kind) {
    }

    private static final ClassValue<List<LifetimeField>> LIFETIME_FIELDS = new ClassValue<>() {
        @Override
        protected List<LifetimeField> computeValue(Class<?> type) {
            List<LifetimeField> result = new ArrayList<>();
            for (Class<?> current = type;
                 current != null && AbstractCannonProjectile.class.isAssignableFrom(current);
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
                    CounterKind kind = counterKind(field.getName());
                    if (kind == null) continue;
                    try {
                        field.setAccessible(true);
                        result.add(new LifetimeField(field, kind));
                    } catch (RuntimeException ignored) {
                        // The public autocannon setter and other accessible addon
                        // counters continue to work if one field is inaccessible.
                    }
                }
            }
            return List.copyOf(result);
        }
    };

    private ProjectileLifetimeController() {
    }

    /** True only for projectile families whose trajectories this mod owns. */
    public static boolean manages(AbstractCannonProjectile projectile) {
        if (!BallisticsConfig.ENABLED.get()
                || !BallisticsConfig.DISABLE_PROJECTILE_DESPAWN.get()
                || ProjectileExclusions.isCbcAtFixRocket(projectile)) {
            return false;
        }
        if (projectile instanceof AbstractBigCannonProjectile) {
            return BallisticsConfig.AFFECT_BIG_CANNONS.get();
        }
        if (projectile instanceof AbstractAutocannonProjectile) {
            return BallisticsConfig.AFFECT_AUTOCANNONS.get();
        }
        // Addon families commonly inherit the shared CBC base directly. They
        // are affected whenever either configured CBC family is enabled.
        return BallisticsConfig.AFFECT_BIG_CANNONS.get()
                || BallisticsConfig.AFFECT_AUTOCANNONS.get();
    }

    public static void keepAlive(AbstractCannonProjectile projectile) {
        if (!manages(projectile)) return;

        if (projectile instanceof AbstractAutocannonProjectile autocannonProjectile) {
            autocannonProjectile.setLifetime(PRACTICALLY_INFINITE_LIFETIME);
        }
        for (LifetimeField lifetimeField : LIFETIME_FIELDS.get(projectile.getClass())) {
            try {
                Field field = lifetimeField.field();
                int value = field.getInt(projectile);
                if (lifetimeField.kind() == CounterKind.IN_GROUND) {
                    // A projectile which stopped without being destroyed is
                    // still governed by the configured hard timeout. Impact
                    // removal and detonation use separate state and remain intact.
                    if (value != 0) field.setInt(projectile, 0);
                } else if (value < REFRESH_BELOW) {
                    field.setInt(projectile, PRACTICALLY_INFINITE_LIFETIME);
                }
            } catch (IllegalAccessException | RuntimeException ignored) {
                // One unsupported addon counter must not stop the projectile tick.
            }
        }
    }

    /**
     * Used by targeted expiry-method mixins. Native CBC/addon lifetime expiry
     * is suppressed, while impacts, penetration failure, fuzes and the hard
     * ballistics deadline continue through their own code paths.
     */
    public static boolean preventNativeExpiry(AbstractCannonProjectile projectile) {
        if (!manages(projectile) || projectile.isRemoved()) return false;
        keepAlive(projectile);
        return true;
    }

    private static CounterKind counterKind(String name) {
        return switch (name) {
            case "ageRemaining", "lifeRemaining", "lifetimeRemaining",
                    "remainingLifetime", "ticksRemaining" -> CounterKind.REMAINING;
            case "maxAge", "maximumAge", "maxLifetime" -> CounterKind.MAXIMUM;
            case "inGroundTime" -> CounterKind.IN_GROUND;
            default -> null;
        };
    }
}
