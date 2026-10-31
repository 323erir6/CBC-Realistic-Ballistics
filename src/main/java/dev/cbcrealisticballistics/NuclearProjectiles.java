package dev.cbcrealisticballistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;

import java.util.Set;

public final class NuclearProjectiles {
    private static final Set<String> IDS = Set.of(
            "nuke_shell",
            "nuke_shell_mk2",
            "h_bomb_mini_shell",
            "h_bomb_shell"
    );

    private NuclearProjectiles() {
    }

    public static boolean isNuclear(Entity entity) {
        if (entity == null) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return "cbc_enhanced_shells".equals(id.getNamespace()) && IDS.contains(id.getPath());
    }
}
