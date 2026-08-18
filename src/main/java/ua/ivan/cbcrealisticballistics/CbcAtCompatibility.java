package ua.ivan.cbcrealisticballistics;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Makes CBC Realistic Ballistics the only trajectory/launch-speed owner when
 * CBC Advanced Technology is installed. CBC AT intentionally exposes the
 * {@code disablePhysicRework} server option, so enabling it leaves all of CBC
 * AT's content intact while restoring CBC's original muzzle velocity for this
 * mod's flight model.
 *
 * <p>Reflection keeps CBC AT an optional dependency: this class can be loaded
 * normally in installations where CBC AT is absent.</p>
 */
final class CbcAtCompatibility {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CBC_AT_MOD_ID = "cbc_at";
    private static boolean applied;

    private CbcAtCompatibility() {
    }

    static void onServerAboutToStart(ServerAboutToStartEvent ignored) {
        takeTrajectoryControl();
    }

    static void onServerStarted(ServerStartedEvent ignored) {
        // Retry here in case another mod initialized its config unusually late.
        takeTrajectoryControl();
    }

    static synchronized void reset() {
        // An integrated client can open another world without restarting Java.
        // Let the next server instance enforce the setting again after configs reload.
        applied = false;
    }

    private static synchronized void takeTrajectoryControl() {
        if (applied || !ModList.get().isLoaded(CBC_AT_MOD_ID)) {
            return;
        }

        try {
            ClassLoader loader = Thread.currentThread().getContextClassLoader();
            Class<?> configsClass = Class.forName("com.dsvv.cbcat.config.CBCATConfigs", true, loader);

            Object serverConfig = readField(configsClass, null, "SERVER");
            Object bigCannonsConfig = readField(serverConfig.getClass(), serverConfig, "bigCannons");
            Object disablePhysicsRework = readField(
                    bigCannonsConfig.getClass(), bigCannonsConfig, "disablePhysicRework"
            );

            Method setter = findBooleanSetter(disablePhysicsRework.getClass());
            setter.invoke(disablePhysicsRework, Boolean.TRUE);
            applied = true;

            LOGGER.info(
                    "CBC Advanced Technology detected: disabled its projectile physics rewrite; "
                            + "CBC Realistic Ballistics now owns launch speed and trajectory"
            );
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.warn(
                    "CBC Advanced Technology was detected, but its physics rewrite could not be disabled. "
                            + "Set cbc_at-server.toml -> disablePhysicRework=true manually.",
                    exception
            );
        }
    }

    private static Object readField(Class<?> owner, Object instance, String name)
            throws ReflectiveOperationException {
        Class<?> current = owner;
        while (current != null) {
            try {
                Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(owner.getName() + "." + name);
    }

    private static Method findBooleanSetter(Class<?> type) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals("set") || method.getParameterCount() != 1) {
                    continue;
                }

                Class<?> parameter = method.getParameterTypes()[0];
                if (parameter == boolean.class
                        || parameter == Boolean.class
                        || parameter == Object.class
                        || parameter.isAssignableFrom(Boolean.class)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(type.getName() + ".set(boolean)");
    }
}
