package dev.cbcrealisticballistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Makes CBC AT rocket spawning deterministic when the launcher is inside a
 * Sable sub-level.
 *
 * <p>CBC AT constructs the muzzle position and shot vector in the launcher's
 * local plot. Sable then rotates both into world space. The separate inherited
 * velocity compatibility layer also runs at {@code ServerLevel.addFreshEntity}
 * HEAD. Mixin ordering at that shared injection point is not stable between
 * the installed compatibility mods. We therefore capture the untouched local
 * vector directly at CBC AT's call site, before {@code addFreshEntity}, and
 * replace the final vector once entity insertion has completed.</p>
 */
public final class CbcAtSableRocketLaunchFix {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double MAX_INHERITED_SPEED = 16.0;
    private static final ThreadLocal<LaunchContext> CURRENT = new ThreadLocal<>();
    private static final Map<AbstractCannonProjectile, CapturedLaunch> CAPTURED =
            Collections.synchronizedMap(new WeakHashMap<>());

    private CbcAtSableRocketLaunchFix() {
    }

    public static void begin(Entity cannonEntity) {
        CURRENT.remove();
        if (cannonEntity == null) return;
        Object helper = sableHelper();
        if (helper == null) return;
        Object subLevel = invokeSingleArgument(helper,
                "getTrackingOrVehicleSubLevel", cannonEntity);
        if (subLevel == null) {
            subLevel = invokeSingleArgument(helper, "getContaining", cannonEntity);
        }
        if (subLevel != null) {
            CURRENT.set(new LaunchContext(cannonEntity.level(), helper, subLevel));
        }
    }

    public static void end() {
        CURRENT.remove();
    }

    /** Called directly from CBC AT's fireShot before ServerLevel sees the entity. */
    public static void captureAtLauncher(Entity entity) {
        LaunchContext context = CURRENT.get();
        if (context == null || !(entity instanceof AbstractCannonProjectile projectile)
                || !isCbcAtRocket(projectile)) {
            return;
        }
        Vec3 movement = projectile.getDeltaMovement();
        Vec3 position = projectile.position();
        if (!finite(movement) || movement.lengthSqr() < 1.0e-12 || !finite(position)) return;
        CAPTURED.put(projectile, new CapturedLaunch(context, movement, position));
    }

    /** Called by EntityJoinLevelEvent after all addFreshEntity HEAD mixins. */
    public static void finishSpawn(AbstractCannonProjectile projectile) {
        CapturedLaunch captured = CAPTURED.remove(projectile);
        if (captured == null) return;

        Vec3 worldShot = transformNormal(captured.context().subLevel(),
                captured.localMovement());
        if (!finite(worldShot) || worldShot.lengthSqr() < 1.0e-12) return;

        Vec3 inherited = worldVelocity(captured.context(), captured.localPosition());
        if (finite(inherited)) {
            inherited = inherited.scale(1.0 / TICKS_PER_SECOND);
            double speedSqr = inherited.lengthSqr();
            if (speedSqr > MAX_INHERITED_SPEED * MAX_INHERITED_SPEED) {
                inherited = inherited.normalize().scale(MAX_INHERITED_SPEED);
            }
            worldShot = worldShot.add(inherited);
        }
        if (!finite(worldShot) || worldShot.lengthSqr() < 1.0e-12) return;

        projectile.setDeltaMovement(worldShot);
        double horizontal = worldShot.horizontalDistance();
        projectile.setYRot((float) Math.toDegrees(Math.atan2(worldShot.x, worldShot.z)));
        projectile.setXRot((float) Math.toDegrees(Math.atan2(worldShot.y, horizontal)));
        projectile.yRotO = projectile.getYRot();
        projectile.xRotO = projectile.getXRot();
        projectile.setOldPosAndRot();
    }

    private static boolean isCbcAtRocket(AbstractCannonProjectile projectile) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        if (id == null || !id.getPath().endsWith("_rocket")) return false;
        return id.getNamespace().equals("cbc_at");
    }

    private static Object sableHelper() {
        try {
            Class<?> sable = Class.forName("dev.ryanhcode.sable.Sable", false,
                    CbcAtSableRocketLaunchFix.class.getClassLoader());
            Field helper = sable.getField("HELPER");
            return helper.get(null);
        } catch (ReflectiveOperationException | LinkageError ignored) {
            return null;
        }
    }

    private static Object invokeSingleArgument(Object owner, String name, Object argument) {
        try {
            for (Method method : owner.getClass().getMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isInstance(argument)) {
                    return method.invoke(owner, argument);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static Vec3 transformNormal(Object subLevel, Vec3 local) {
        try {
            Method logicalPose = subLevel.getClass().getMethod("logicalPose");
            Object pose = logicalPose.invoke(subLevel);
            for (Method method : pose.getClass().getMethods()) {
                if (method.getName().equals("transformNormal")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(Vec3.class)) {
                    Object result = method.invoke(pose, local);
                    return result instanceof Vec3 vec ? vec : null;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return null;
    }

    private static Vec3 worldVelocity(LaunchContext context, Vec3 localPosition) {
        try {
            for (Method method : context.helper().getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (!method.getName().equals("getVelocity") || parameters.length != 3
                        || !parameters[0].isInstance(context.level())
                        || !parameters[1].isInstance(context.subLevel())
                        || !parameters[2].isAssignableFrom(Vec3.class)) {
                    continue;
                }
                Object result = method.invoke(context.helper(), context.level(),
                        context.subLevel(), localPosition);
                return result instanceof Vec3 vec ? vec : Vec3.ZERO;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        return Vec3.ZERO;
    }

    private static boolean finite(Vec3 vector) {
        return vector != null && Double.isFinite(vector.x)
                && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private record LaunchContext(Level level, Object helper, Object subLevel) {
    }

    private record CapturedLaunch(LaunchContext context, Vec3 localMovement,
                                  Vec3 localPosition) {
    }
}
