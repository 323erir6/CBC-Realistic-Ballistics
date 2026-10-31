package dev.cbcrealisticballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.event.level.ChunkDataEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Applies persistent glass and pressure-wave aftermath only when chunks load naturally. */
public final class NuclearAftermathManager {
    private static final String CHUNK_MARKER = "cbc_realistic_ballistics_nuclear_aftermath";
    private static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private static final int MAX_INSPECTIONS_PER_TICK = 6000;
    private static final int MAX_CHANGES_PER_TICK = 400;
    private static final long MAX_WORK_NANOS_PER_TICK = 2_000_000L;
    private static final int MAX_COLUMN_DEPTH = 40;
    private static final Map<ChunkAccess, Long> PROCESSED_THROUGH =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final ArrayDeque<ChunkJob> JOBS = new ArrayDeque<>();

    private NuclearAftermathManager() {
    }

    public static void onChunkDataLoad(ChunkDataEvent.Load event) {
        PROCESSED_THROUGH.put(event.getChunk(), event.getData().getLong(CHUNK_MARKER));
    }

    public static void onChunkDataSave(ChunkDataEvent.Save event) {
        Long processed = PROCESSED_THROUGH.get(event.getChunk());
        if (processed != null && processed > 0L) event.getData().putLong(CHUNK_MARKER, processed);
    }

    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getChunk() instanceof LevelChunk chunk)) return;
        long processed = PROCESSED_THROUGH.getOrDefault(chunk, 0L);
        NuclearBlastHistory.Query query = NuclearBlastHistory.get(level).query(chunk.getPos(), processed);
        if (!query.records().isEmpty()) enqueue(level, chunk, query.records(), query.processedThrough());
    }

    public static void enqueue(
            ServerLevel level,
            LevelChunk chunk,
            NuclearBlastHistory.BlastRecord record
    ) {
        long processed = PROCESSED_THROUGH.getOrDefault(chunk, 0L);
        if (record.sequence() <= processed || !record.intersects(chunk.getPos())) return;
        NuclearBlastHistory.Query query = NuclearBlastHistory.get(level).query(chunk.getPos(), processed);
        if (!query.records().isEmpty()) {
            enqueue(level, chunk, query.records(), query.processedThrough());
        }
    }

    private static void enqueue(
            ServerLevel level,
            LevelChunk chunk,
            List<NuclearBlastHistory.BlastRecord> records,
            long processedThrough
    ) {
        JOBS.addLast(new ChunkJob(level, chunk, records, processedThrough));
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        Budget budget = new Budget(MAX_INSPECTIONS_PER_TICK, MAX_CHANGES_PER_TICK);
        while (!JOBS.isEmpty() && budget.available()) {
            ChunkJob job = JOBS.peekFirst();
            LevelChunk current = job.level.getChunkSource().getChunkNow(job.chunkPos.x, job.chunkPos.z);
            if (current != job.chunk) {
                JOBS.removeFirst();
                continue;
            }
            if (!job.process(budget)) break;
            PROCESSED_THROUGH.merge(job.chunk, job.processedThrough, Math::max);
            job.chunk.setUnsaved(true);
            JOBS.removeFirst();
        }
    }

    public static void clearRuntimeState() {
        JOBS.clear();
        PROCESSED_THROUGH.clear();
    }

    private static final class ChunkJob {
        final ServerLevel level;
        final LevelChunk chunk;
        final ChunkPos chunkPos;
        final List<NuclearBlastHistory.BlastRecord> records;
        final long processedThrough;
        final List<BlockEntity> blockEntities;
        int recordIndex;
        int sectionIndex;
        int sectionBlockIndex;
        int blockEntityIndex;
        int columnIndex;
        final double[] attenuationByColumn = new double[256];
        Phase phase = Phase.GLASS;

        ChunkJob(
                ServerLevel level,
                LevelChunk chunk,
                List<NuclearBlastHistory.BlastRecord> records,
                long processedThrough
        ) {
            this.level = level;
            this.chunk = chunk;
            this.chunkPos = chunk.getPos();
            this.records = List.copyOf(records);
            this.processedThrough = processedThrough;
            this.blockEntities = new ArrayList<>(chunk.getBlockEntities().values());
            Arrays.fill(attenuationByColumn, Double.NaN);
        }

        boolean process(Budget budget) {
            while (recordIndex < records.size() && budget.available()) {
                NuclearBlastHistory.BlastRecord blast = records.get(recordIndex);
                if (phase == Phase.GLASS && !processGlass(blast, budget)) return false;
                if (phase == Phase.BLOCK_ENTITIES && !processBlockEntities(blast, budget)) return false;
                if (phase == Phase.STRUCTURES && !processStructures(blast, budget)) return false;
                if (phase == Phase.DONE) nextRecord();
            }
            return recordIndex >= records.size();
        }

        private boolean processGlass(NuclearBlastHistory.BlastRecord blast, Budget budget) {
            LevelChunkSection[] sections = chunk.getSections();
            while (sectionIndex < sections.length && budget.available()) {
                LevelChunkSection section = sections[sectionIndex];
                if (section.hasOnlyAir() || !section.maybeHas(NuclearGlassSupport::isGlass)) {
                    sectionIndex++;
                    sectionBlockIndex = 0;
                    continue;
                }
                int baseY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(sectionIndex));
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                while (sectionBlockIndex < 4096 && budget.available()) {
                    int packed = sectionBlockIndex++;
                    int lx = packed & 15;
                    int lz = packed >>> 4 & 15;
                    int ly = packed >>> 8;
                    BlockState state = section.getBlockState(lx, ly, lz);
                    budget.inspect();
                    if (!NuclearGlassSupport.isGlass(state)) continue;
                    pos.set(chunkPos.getMinBlockX() + lx, baseY + ly, chunkPos.getMinBlockZ() + lz);
                    if (waveEnergy(blast, pos, lx, lz) >= 0.10
                            && removeWithoutDrops(pos, state)) budget.change();
                }
                if (sectionBlockIndex < 4096) return false;
                sectionIndex++;
                sectionBlockIndex = 0;
            }
            if (sectionIndex < sections.length) return false;
            phase = Phase.BLOCK_ENTITIES;
            return true;
        }

        private boolean processBlockEntities(
                NuclearBlastHistory.BlastRecord blast, Budget budget
        ) {
            while (blockEntityIndex < blockEntities.size() && budget.available()) {
                BlockEntity blockEntity = blockEntities.get(blockEntityIndex++);
                BlockPos pos = blockEntity.getBlockPos();
                budget.inspect();
                if (NuclearGlassSupport.isGlassFilled(blockEntity)
                        && waveEnergy(blast, pos, pos.getX() & 15, pos.getZ() & 15) >= 0.10
                        && removeWithoutDrops(pos, level.getBlockState(pos))) {
                    budget.change();
                }
            }
            if (blockEntityIndex < blockEntities.size()) return false;
            phase = Phase.STRUCTURES;
            return true;
        }

        private boolean processStructures(
                NuclearBlastHistory.BlastRecord blast, Budget budget
        ) {
            while (columnIndex < 256 && budget.canProcessColumn()) {
                int packed = columnIndex++;
                int lx = packed & 15;
                int lz = packed >>> 4;
                processColumn(blast, lx, lz, budget);
            }
            if (columnIndex < 256) return false;
            phase = Phase.DONE;
            return true;
        }

        private void processColumn(
                NuclearBlastHistory.BlastRecord blast, int lx, int lz, Budget budget
        ) {
            int topY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz) - 1;
            if (topY <= level.getMinBuildHeight()) return;
            int minNeighbour = topY;
            for (int[] offset : NEIGHBOURS) {
                int nx = lx + offset[0];
                int nz = lz + offset[1];
                if (nx < 0 || nx > 15 || nz < 0 || nz > 15) continue;
                minNeighbour = Math.min(minNeighbour,
                        chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, nx, nz) - 1);
            }
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(
                    chunkPos.getMinBlockX() + lx, topY, chunkPos.getMinBlockZ() + lz);
            BlockState topState = level.getBlockState(pos);
            int protrusion = Math.max(0, topY - minNeighbour);
            if (!isLikelyTerrain(topState)) protrusion = Math.max(protrusion, 6);
            if (protrusion < 2) return;
            int depth = Math.min(MAX_COLUMN_DEPTH, protrusion + 3);
            int towardX = Integer.compare((int) Math.floor(blast.x()), pos.getX());
            int towardZ = Integer.compare((int) Math.floor(blast.z()), pos.getZ());
            if (Math.abs(blast.x() - pos.getX()) >= Math.abs(blast.z() - pos.getZ())) towardZ = 0;
            else towardX = 0;

            // Finish a column once started so a wall-clock yield cannot leave a
            // deterministic pressure-wave column half processed.
            for (int offset = 0; offset < depth && budget.withinLimits(); ++offset) {
                pos.setY(topY - offset);
                BlockState state = level.getBlockState(pos);
                budget.inspect();
                if (state.isAir() || NuclearGlassSupport.isGlass(state)
                        || state.getDestroySpeed(level, pos) < 0.0f) continue;
                BlockPos front = pos.offset(towardX, 0, towardZ);
                if (!level.getBlockState(front).isAir() && offset > 0) continue;
                double distanceSquared = distanceSquared(blast, pos);
                if (distanceSquared <= (double) blast.craterRadius() * blast.craterRadius()) continue;
                double energy = waveEnergy(blast, pos, lx, lz);
                if (energy <= 0.12) continue;
                float resistance = Math.max(0.0f, state.getBlock().getExplosionResistance());
                float hardness = Math.max(0.0f, state.getDestroySpeed(level, pos));
                double material = 1.0 / (1.0 + resistance / 8.0 + hardness / 10.0);
                double chance = Math.min(0.92, Math.max(0.0, (energy - 0.12) * 0.55 * material));
                if (deterministicUnit(blast.sequence(), pos) < chance
                        && removeWithoutDrops(pos, state)) budget.change();
            }
        }

        private double waveEnergy(
                NuclearBlastHistory.BlastRecord blast, BlockPos pos, int lx, int lz
        ) {
            double distanceSquared = distanceSquared(blast, pos);
            double inverseSquare = inverseSquareEnergy(blast, distanceSquared);
            int column = (lz & 15) << 4 | lx & 15;
            double attenuation = attenuationByColumn[column];
            if (Double.isNaN(attenuation)) {
                int surfaceY = chunk.getHeight(
                        Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx & 15, lz & 15) - 1;
                attenuation = turnAttenuation(blast, surfaceY, lx & 15, lz & 15);
                attenuationByColumn[column] = attenuation;
            }
            return inverseSquare * attenuation;
        }

        private double turnAttenuation(
                NuclearBlastHistory.BlastRecord blast, int targetY, int lx, int lz
        ) {
            double dx = blast.x() - (chunkPos.getMinBlockX() + lx + 0.5);
            double dz = blast.z() - (chunkPos.getMinBlockZ() + lz + 0.5);
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length < 1.0) return 1.0;
            double stepX = dx / length;
            double stepZ = dz / length;
            double previousAngle = 0.0;
            double turnCost = 0.0;
            int bends = 0;
            for (int step = 1; step <= 10; ++step) {
                int sx = (int) Math.round(lx + stepX * step);
                int sz = (int) Math.round(lz + stepZ * step);
                if (sx < 0 || sx > 15 || sz < 0 || sz > 15) break;
                int obstacleY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, sx, sz) - 1;
                double requiredAngle = Math.atan2(Math.max(0, obstacleY - targetY + 1), step);
                double change = Math.abs(requiredAngle - previousAngle);
                if (change > 0.08) bends++;
                turnCost += change;
                previousAngle = requiredAngle;
            }
            // Besides inverse-square decay, every bend consumes wave energy.
            return bendAttenuation(turnCost, bends);
        }

        private double distanceSquared(NuclearBlastHistory.BlastRecord blast, BlockPos pos) {
            double dx = pos.getX() + 0.5 - blast.x();
            double dy = pos.getY() + 0.5 - blast.y();
            double dz = pos.getZ() + 0.5 - blast.z();
            return dx * dx + dy * dy + dz * dz;
        }

        private boolean removeWithoutDrops(BlockPos pos, BlockState state) {
            if (state.isAir() || state.getDestroySpeed(level, pos) < 0.0f) return false;
            return level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
        }

        private void nextRecord() {
            recordIndex++;
            sectionIndex = 0;
            sectionBlockIndex = 0;
            blockEntityIndex = 0;
            columnIndex = 0;
            Arrays.fill(attenuationByColumn, Double.NaN);
            phase = Phase.GLASS;
        }
    }

    private static boolean isLikelyTerrain(BlockState state) {
        String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).getPath();
        return path.equals("dirt") || path.equals("grass_block") || path.equals("stone")
                || path.equals("deepslate") || path.equals("netherrack")
                || path.equals("end_stone") || path.equals("gravel") || path.equals("clay")
                || path.equals("snow_block") || path.equals("ice")
                || path.endsWith("_sand") || path.equals("sand")
                || path.endsWith("_ore") || path.endsWith("terracotta");
    }

    static double inverseSquareEnergy(
            NuclearBlastHistory.BlastRecord blast, double distanceSquared
    ) {
        double structuralRadius = blast.glassRadius() * 0.45;
        double minimum = (double) blast.craterRadius() * blast.craterRadius();
        return structuralRadius * structuralRadius / Math.max(minimum, distanceSquared);
    }

    static double bendAttenuation(double turnCost, int bends) {
        return Math.exp(-1.15 * Math.max(0.0, turnCost))
                * Math.pow(0.78, Math.max(0, bends));
    }

    private static double deterministicUnit(long sequence, BlockPos pos) {
        long value = sequence * 0x9E3779B97F4A7C15L ^ pos.asLong();
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static final int[][] NEIGHBOURS = {
            {-2, 0}, {2, 0}, {0, -2}, {0, 2},
            {-2, -2}, {-2, 2}, {2, -2}, {2, 2}
    };

    private enum Phase {
        GLASS,
        BLOCK_ENTITIES,
        STRUCTURES,
        DONE
    }

    private static final class Budget {
        final int maxInspections;
        final int maxChanges;
        final long deadlineNanos;
        int inspections;
        int changes;

        Budget(int maxInspections, int maxChanges) {
            this.maxInspections = maxInspections;
            this.maxChanges = maxChanges;
            this.deadlineNanos = System.nanoTime() + MAX_WORK_NANOS_PER_TICK;
        }

        boolean available() {
            return withinLimits() && System.nanoTime() < deadlineNanos;
        }

        boolean withinLimits() {
            return inspections < maxInspections && changes < maxChanges;
        }

        boolean canProcessColumn() {
            return available()
                    && maxInspections - inspections >= MAX_COLUMN_DEPTH
                    && maxChanges - changes >= MAX_COLUMN_DEPTH;
        }

        void inspect() {
            inspections++;
        }

        void change() {
            changes++;
        }
    }
}
