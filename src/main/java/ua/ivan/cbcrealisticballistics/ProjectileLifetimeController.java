package ua.ivan.cbcrealisticballistics;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;

/**
 * Extends ordinary projectile lifetime counters without cancelling Entity.remove().
 * Deliberately avoiding an Entity removal mixin lets programmable fuzes keep full
 * ownership of their detonation/persistence logic.
 */
public final class ProjectileLifetimeController {
    private static final int PRACTICALLY_INFINITE_LIFETIME = Integer.MAX_VALUE / 4;

    private static final ClassValue<List<Field>> LIFETIME_FIELDS = new ClassValue<>() {
        @Override
        protected List<Field> computeValue(Class<?> type) {
            List<Field> result = new ArrayList<>();
            for (Class<?> current = type;
                 current != null && AbstractCannonProjectile.class.isAssignableFrom(current);
                 current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) continue;
                    String name = field.getName();
                    if (!name.equals("ageRemaining") && !name.equals("maxAge")) continue;
                    try {
                        field.setAccessible(true);
                        result.add(field);
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

    public static void keepAlive(AbstractCannonProjectile projectile) {
        if (!BallisticsConfig.DISABLE_PROJECTILE_DESPAWN.get()) return;

        if (projectile instanceof AbstractAutocannonProjectile autocannonProjectile) {
            autocannonProjectile.setLifetime(PRACTICALLY_INFINITE_LIFETIME);
        }
        for (Field field : LIFETIME_FIELDS.get(projectile.getClass())) {
            try {
                field.setInt(projectile, PRACTICALLY_INFINITE_LIFETIME);
            } catch (IllegalAccessException | RuntimeException ignored) {
                // One unsupported addon counter must not stop the projectile tick.
            }
        }
    }
}
