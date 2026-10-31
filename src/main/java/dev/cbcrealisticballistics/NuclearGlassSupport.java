package dev.cbcrealisticballistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

final class NuclearGlassSupport {
    private static final Map<Block, Boolean> GLASS_BLOCKS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Accessors> ACCESSORS = new ConcurrentHashMap<>();

    private NuclearGlassSupport() {
    }

    static boolean isGlass(BlockState state) {
        if (state == null || state.isAir()) return false;
        return GLASS_BLOCKS.computeIfAbsent(state.getBlock(), block -> {
            String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
            return path.contains("glass") || path.contains("stained_glass");
        });
    }

    static boolean isGlassFilled(BlockEntity blockEntity) {
        if (blockEntity == null) return false;
        String name = blockEntity.getClass().getName().toLowerCase();
        if (!name.contains("copycat") && !name.contains("framed")) return false;
        Accessors accessors = ACCESSORS.computeIfAbsent(blockEntity.getClass(), NuclearGlassSupport::findAccessors);
        for (Method method : accessors.methods) {
            try {
                if (containsGlass(method.invoke(blockEntity), 0)) return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Optional addons change their block-entity APIs between versions.
            }
        }
        return false;
    }

    private static Accessors findAccessors(Class<?> type) {
        java.util.ArrayList<Method> methods = new java.util.ArrayList<>();
        for (String name : new String[]{"getMaterial", "getMaterialItemStorage", "getCamo", "getCamoTwo"}) {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                methods.add(method);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
            }
        }
        return new Accessors(methods.toArray(Method[]::new));
    }

    private static boolean containsGlass(Object value, int depth) throws ReflectiveOperationException {
        if (value == null || depth > 3) return false;
        if (value instanceof BlockState state) return isGlass(state);
        if (value instanceof Optional<?> optional) return optional.isPresent() && containsGlass(optional.get(), depth + 1);
        if (value instanceof Collection<?> collection) {
            for (Object entry : collection) if (containsGlass(entry, depth + 1)) return true;
            return false;
        }
        if (value instanceof Map<?, ?> map) {
            for (Object entry : map.values()) if (containsGlass(entry, depth + 1)) return true;
            return false;
        }
        for (String name : new String[]{"getAllMaterials", "getContent", "getAsBlockState"}) {
            try {
                Method method = value.getClass().getMethod(name);
                method.setAccessible(true);
                if (containsGlass(method.invoke(value), depth + 1)) return true;
            } catch (NoSuchMethodException ignored) {
            }
        }
        return false;
    }

    private record Accessors(Method[] methods) {
    }
}
