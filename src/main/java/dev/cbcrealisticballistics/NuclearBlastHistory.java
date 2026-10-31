package dev.cbcrealisticballistics;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent per-dimension history used to apply aftermath to future chunk loads. */
public final class NuclearBlastHistory extends SavedData {
    private static final String DATA_NAME = "cbc_realistic_ballistics_nuclear_history";
    private static final int REGION_SHIFT = 5;
    private static final Factory<NuclearBlastHistory> FACTORY = new Factory<>(
            NuclearBlastHistory::new,
            NuclearBlastHistory::load
    );

    private final List<BlastRecord> records = new ArrayList<>();
    private final Long2ObjectOpenHashMap<List<BlastRecord>> regionIndex = new Long2ObjectOpenHashMap<>();
    private long nextSequence = 1L;

    public static NuclearBlastHistory get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static BlastRecord record(
            ServerLevel level, double x, double y, double z, int profileOrdinal
    ) {
        NuclearBlastHistory history = get(level);
        NuclearBlastProfile profile = NuclearBlastProfile.byOrdinal(profileOrdinal);
        BlastRecord record = new BlastRecord(
                history.nextSequence++,
                typeName(profileOrdinal),
                profileOrdinal,
                x, y, z,
                level.getGameTime(),
                profile.craterRadius(),
                profile.glassRadius()
        );
        history.records.add(record);
        history.index(record);
        history.setDirty();
        return record;
    }

    public Query query(ChunkPos chunk, long processedThrough) {
        List<BlastRecord> bucket = regionIndex.get(regionKey(chunk.x, chunk.z));
        if (bucket == null || bucket.isEmpty()) return Query.EMPTY;
        List<BlastRecord> relevant = new ArrayList<>();
        long through = processedThrough;
        for (BlastRecord record : bucket) {
            through = Math.max(through, record.sequence());
            if (record.sequence() <= processedThrough || !record.intersects(chunk)) continue;
            relevant.add(record);
        }
        return relevant.isEmpty()
                ? Query.EMPTY
                : new Query(Collections.unmodifiableList(relevant), through);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("NextSequence", nextSequence);
        ListTag list = new ListTag();
        for (BlastRecord record : records) {
            CompoundTag blast = new CompoundTag();
            blast.putLong("Sequence", record.sequence());
            blast.putString("Type", record.type());
            blast.putInt("Profile", record.profileOrdinal());
            blast.putDouble("X", record.x());
            blast.putDouble("Y", record.y());
            blast.putDouble("Z", record.z());
            blast.putLong("GameTime", record.gameTime());
            blast.putInt("CraterRadius", record.craterRadius());
            blast.putInt("GlassRadius", record.glassRadius());
            list.add(blast);
        }
        tag.put("Blasts", list);
        return tag;
    }

    private static NuclearBlastHistory load(CompoundTag tag, HolderLookup.Provider registries) {
        NuclearBlastHistory history = new NuclearBlastHistory();
        history.nextSequence = Math.max(1L, tag.getLong("NextSequence"));
        ListTag list = tag.getList("Blasts", Tag.TAG_COMPOUND);
        long maxSequence = 0L;
        for (int index = 0; index < list.size(); ++index) {
            CompoundTag blast = list.getCompound(index);
            int profileOrdinal = blast.getInt("Profile");
            NuclearBlastProfile profile = NuclearBlastProfile.byOrdinal(profileOrdinal);
            BlastRecord record = new BlastRecord(
                    blast.getLong("Sequence"),
                    blast.contains("Type") ? blast.getString("Type") : typeName(profileOrdinal),
                    profileOrdinal,
                    blast.getDouble("X"), blast.getDouble("Y"), blast.getDouble("Z"),
                    blast.getLong("GameTime"),
                    blast.contains("CraterRadius") ? blast.getInt("CraterRadius") : profile.craterRadius(),
                    blast.contains("GlassRadius") ? blast.getInt("GlassRadius") : profile.glassRadius()
            );
            history.records.add(record);
            history.index(record);
            maxSequence = Math.max(maxSequence, record.sequence());
        }
        history.nextSequence = Math.max(history.nextSequence, maxSequence + 1L);
        return history;
    }

    private void index(BlastRecord record) {
        int minChunkX = ((int) Math.floor(record.x() - record.glassRadius())) >> 4;
        int maxChunkX = ((int) Math.floor(record.x() + record.glassRadius())) >> 4;
        int minChunkZ = ((int) Math.floor(record.z() - record.glassRadius())) >> 4;
        int maxChunkZ = ((int) Math.floor(record.z() + record.glassRadius())) >> 4;
        for (int regionX = minChunkX >> REGION_SHIFT; regionX <= maxChunkX >> REGION_SHIFT; ++regionX) {
            for (int regionZ = minChunkZ >> REGION_SHIFT; regionZ <= maxChunkZ >> REGION_SHIFT; ++regionZ) {
                regionIndex.computeIfAbsent(ChunkPos.asLong(regionX, regionZ), ignored -> new ArrayList<>())
                        .add(record);
            }
        }
    }

    private static long regionKey(int chunkX, int chunkZ) {
        return ChunkPos.asLong(chunkX >> REGION_SHIFT, chunkZ >> REGION_SHIFT);
    }

    private static String typeName(int profileOrdinal) {
        return switch (profileOrdinal) {
            case 1 -> "nuke_mk2";
            case 2 -> "h_bomb_mini";
            case 3 -> "h_bomb";
            default -> "nuke_mk1";
        };
    }

    public record BlastRecord(
            long sequence,
            String type,
            int profileOrdinal,
            double x,
            double y,
            double z,
            long gameTime,
            int craterRadius,
            int glassRadius
    ) {
        boolean intersects(ChunkPos chunk) {
            double minX = chunk.getMinBlockX();
            double maxX = minX + 16.0;
            double minZ = chunk.getMinBlockZ();
            double maxZ = minZ + 16.0;
            double closestX = Math.max(minX, Math.min(x, maxX));
            double closestZ = Math.max(minZ, Math.min(z, maxZ));
            double dx = closestX - x;
            double dz = closestZ - z;
            return dx * dx + dz * dz <= (double) glassRadius * glassRadius;
        }

        NuclearBlastProfile profile() {
            return NuclearBlastProfile.byOrdinal(profileOrdinal);
        }
    }

    public record Query(List<BlastRecord> records, long processedThrough) {
        static final Query EMPTY = new Query(List.of(), 0L);
    }
}
