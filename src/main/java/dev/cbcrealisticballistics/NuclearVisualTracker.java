package dev.cbcrealisticballistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class NuclearVisualTracker {
    static final double VISUAL_RANGE = 1024.0 * 16.0;
    private static final double VISUAL_RANGE_SQUARED = VISUAL_RANGE * VISUAL_RANGE;
    private static final double VANILLA_EFFECT_RANGE_SQUARED = 1024.0 * 1024.0;
    private static final Set<String> NUCLEAR_PROJECTILES = Set.of(
            "nuke_shell",
            "nuke_shell_mk2",
            "h_bomb_mini_shell",
            "h_bomb_shell"
    );
    private static final Map<UUID, Entity> ACTIVE = new HashMap<>();

    private NuclearVisualTracker() {
    }

    static void register(Entity entity, ServerLevel level) {
        if (isNuclearProjectile(entity)) {
            ACTIVE.put(entity.getUUID(), entity);
        }
    }

    static void unregister(Entity entity, ServerLevel level) {
        if (ACTIVE.remove(entity.getUUID()) == null) {
            return;
        }
        NuclearVisualPayload payload = payload(entity, false);
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(entity) <= VISUAL_RANGE_SQUARED) {
                PacketDistributor.sendToPlayer(player, payload);
            }
        }
    }

    static void onServerTickPost(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        Iterator<Entity> iterator = ACTIVE.values().iterator();
        while (iterator.hasNext()) {
            Entity entity = iterator.next();
            if (entity.isRemoved() || !(entity.level() instanceof ServerLevel level)) {
                iterator.remove();
                continue;
            }
            NuclearVisualPayload payload = payload(entity, true);
            for (ServerPlayer player : level.players()) {
                double distanceSquared = player.distanceToSqr(entity);
                if (distanceSquared > VANILLA_EFFECT_RANGE_SQUARED
                        && distanceSquared <= VISUAL_RANGE_SQUARED) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
    }

    static void clearRuntimeState() {
        ACTIVE.clear();
    }

    private static boolean isNuclearProjectile(Entity entity) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return id != null
                && id.getNamespace().equals("cbc_enhanced_shells")
                && NUCLEAR_PROJECTILES.contains(id.getPath());
    }

    private static NuclearVisualPayload payload(Entity entity, boolean active) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        int profile = switch (id == null ? "" : id.getPath()) {
            case "nuke_shell_mk2" -> 1;
            case "h_bomb_mini_shell" -> 2;
            case "h_bomb_shell" -> 3;
            default -> 0;
        };
        Vec3 velocity = entity.getDeltaMovement();
        return new NuclearVisualPayload(
                entity.getUUID(),
                entity.getX(), entity.getY(), entity.getZ(),
                velocity.x, velocity.y, velocity.z,
                profile,
                active
        );
    }
}
