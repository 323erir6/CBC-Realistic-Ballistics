package dev.cbcrealisticballistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Projectile families deliberately left entirely to their owning mod. */
public final class ProjectileExclusions {
    private static final java.util.concurrent.ConcurrentHashMap<net.minecraft.world.entity.EntityType<?>, Boolean>
            CBC_AT_FIX_ROCKETS = new java.util.concurrent.ConcurrentHashMap<>();

    private ProjectileExclusions() {
    }

    public static boolean isCbcAtFixRocket(AbstractCannonProjectile projectile) {
        return CBC_AT_FIX_ROCKETS.computeIfAbsent(projectile.getType(), type -> {
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            return id != null
                    && id.getNamespace().equals("cbcatfix")
                    && id.getPath().startsWith("big_")
                    && id.getPath().endsWith("_rocket");
        });
    }
}
