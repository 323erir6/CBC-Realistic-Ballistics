package dev.cbcrealisticballistics;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.saveddata.SavedData;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Persists virtual projectiles which cannot use vanilla chunk entity storage.
 *
 * <p>High-altitude transit deliberately keeps a live projectile outside an
 * entity-ticking chunk without loading that chunk. Vanilla tries to unload an
 * entity as soon as its section is no longer resident, so those projectiles
 * cannot be written through the ordinary per-chunk entity file while they are
 * in transit. This level SavedData is the authoritative restart-safe copy.</p>
 */
public final class ProjectileTransitSavedData extends SavedData {
    private static final String DATA_NAME = "cbc_realistic_ballistics_transit_projectiles";
    private static final String PROJECTILES_TAG = "Projectiles";
    private static final ThreadLocal<Boolean> RESTORING =
            ThreadLocal.withInitial(() -> false);

    private final ServerLevel level;
    private final List<CompoundTag> pendingRestore;
    private boolean restoreAttempted;
    private long nextRestoreAttemptGameTime;

    private ProjectileTransitSavedData(ServerLevel level) {
        this(level, new ArrayList<>());
    }

    private ProjectileTransitSavedData(ServerLevel level, List<CompoundTag> pendingRestore) {
        this.level = level;
        this.pendingRestore = pendingRestore;
    }

    @Override
    public CompoundTag save(CompoundTag output, HolderLookup.Provider registries) {
        ListTag projectiles = new ListTag();
        Set<UUID> savedIds = new HashSet<>();
        for (CompoundTag pending : this.pendingRestore) {
            projectiles.add(pending.copy());
            if (pending.hasUUID("UUID")) savedIds.add(pending.getUUID("UUID"));
        }
        for (AbstractCannonProjectile projectile
                : HighAltitudeProjectileTransit.snapshotUnloadedProjectiles(this.level)) {
            if (!savedIds.add(projectile.getUUID())) continue;
            CompoundTag serialized = serialize(projectile);
            if (serialized != null) projectiles.add(serialized);
        }
        output.put(PROJECTILES_TAG, projectiles);
        return output;
    }

    public void restorePending() {
        if (this.restoreAttempted
                || this.level.getGameTime() < this.nextRestoreAttemptGameTime) return;
        this.restoreAttempted = true;
        if (this.pendingRestore.isEmpty()) return;

        List<CompoundTag> retry = new ArrayList<>();
        RESTORING.set(true);
        try {
            for (CompoundTag serialized : this.pendingRestore) {
                UUID uuid = serialized.hasUUID("UUID") ? serialized.getUUID("UUID") : null;
                if (uuid != null && this.level.getEntity(uuid) != null) continue;
                Entity restored;
                try {
                    restored = EntityType.loadEntityRecursive(
                            serialized.copy(), this.level, Function.identity());
                } catch (RuntimeException exception) {
                    CBCRealisticBallistics.LOGGER.warn(
                            "Could not restore a saved transit projectile; keeping it for the next restart",
                            exception);
                    retry.add(serialized.copy());
                    continue;
                }
                if (!(restored instanceof AbstractCannonProjectile projectile)) {
                    CBCRealisticBallistics.LOGGER.warn(
                            "Ignored invalid transit record with entity id {}",
                            serialized.getString("id"));
                    continue;
                }
                if (!this.level.addFreshEntity(projectile)) {
                    retry.add(serialized.copy());
                }
            }
        } finally {
            RESTORING.remove();
        }
        this.pendingRestore.clear();
        this.pendingRestore.addAll(retry);
        if (!retry.isEmpty()) {
            this.restoreAttempted = false;
            this.nextRestoreAttemptGameTime = this.level.getGameTime() + 100L;
        }
        this.setDirty();
    }

    public static void ensureRestored(ServerLevel level) {
        get(level).restorePending();
    }

    public static void markTransitStateDirty(ServerLevel level) {
        get(level).setDirty();
    }

    public static boolean isRestoringEntity() {
        return RESTORING.get();
    }

    private static ProjectileTransitSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(
                        () -> new ProjectileTransitSavedData(level),
                        (tag, registries) -> load(level, tag),
                        null),
                DATA_NAME);
    }

    private static ProjectileTransitSavedData load(ServerLevel level, CompoundTag tag) {
        List<CompoundTag> projectiles = new ArrayList<>();
        ListTag saved = tag.getList(PROJECTILES_TAG, Tag.TAG_COMPOUND);
        for (int index = 0; index < saved.size(); index++) {
            projectiles.add(saved.getCompound(index).copy());
        }
        return new ProjectileTransitSavedData(level, projectiles);
    }

    private static CompoundTag serialize(AbstractCannonProjectile projectile) {
        if (projectile.isRemoved() || !HighAltitudeProjectileTransit.isActive(projectile)
                || HighAltitudeProjectileTransit.isCurrentChunkLoaded(projectile)) {
            return null;
        }
        CompoundTag tag = new CompoundTag();
        try {
            projectile.saveWithoutId(tag);
            var id = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
            if (id == null) return null;
            tag.putString("id", id.toString());
            return tag;
        } catch (RuntimeException exception) {
            CBCRealisticBallistics.LOGGER.warn(
                    "Could not serialize transit projectile {}", projectile.getUUID(), exception);
            return null;
        }
    }
}
