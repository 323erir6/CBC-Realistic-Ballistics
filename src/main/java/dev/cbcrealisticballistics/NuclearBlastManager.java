package dev.cbcrealisticballistics;

import com.example.cbcenhancedshells.compat.ClaimProtectionHelper;
import com.example.cbcenhancedshells.CBCESCommonConfig;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Replaces hundreds of ring TNT explosions with one deterministic, budgeted
 * terrain operation. Every block removal uses UPDATE_SUPPRESS_DROPS.
 */
public final class NuclearBlastManager {
    private static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private static final int MAX_CHANGES_PER_TICK = 3000;
    private static final int MAX_INSPECTIONS_PER_TICK = 24000;
    private static final int WINDOW_CHUNK_PROBES_PER_TICK = 512;
    private static final int WINDOW_CHUNK_ENQUEUES_PER_TICK = 16;
    private static final int MAX_TERRAIN_SEARCH_DEPTH = 128;
    private static final long MAX_WORK_NANOS_PER_TICK = 7_000_000L;
    private static final int MAX_DAMAGE_DISCOVERY_PER_TICK = 384;
    private static final int MAX_DAMAGE_EVALUATIONS_PER_TICK = 768;
    private static final long MAX_DAMAGE_WORK_NANOS_PER_TICK = 1_500_000L;
    private static final double BLOCK_HALF_DIAGONAL = Math.sqrt(0.5);
    private static final List<Job> JOBS = new ArrayList<>();
    private static final List<DamageWave> DAMAGE_WAVES = new ArrayList<>();
    private static int damageWaveCursor;

    private NuclearBlastManager() {
    }

    public static void start(ServerLevel level, double x, double y, double z, int profileOrdinal) {
        NuclearBlastProfile profile = NuclearBlastProfile.byOrdinal(profileOrdinal);
        DamageWave damageWave = new DamageWave(
                level, x, y, z, profile.glassRadius(), level.getGameTime());
        DAMAGE_WAVES.add(damageWave);
        NuclearBlastHistory.BlastRecord historyRecord = NuclearBlastHistory.record(
                level, x, y, z, profileOrdinal);
        JOBS.add(new Job(level, x, y, z, profile, historyRecord, damageWave));
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        processDamageWaves();
        if (JOBS.isEmpty()) return;
        Budget budget = new Budget(MAX_CHANGES_PER_TICK, MAX_INSPECTIONS_PER_TICK);
        Iterator<Job> iterator = JOBS.iterator();
        while (iterator.hasNext() && budget.canStartWork()) {
            Job job = iterator.next();
            if (!job.level.getServer().isRunning() || job.tick(budget)) {
                job.releaseTerrainTicket();
                iterator.remove();
            }
        }
    }

    public static void clearRuntimeState() {
        for (Job job : JOBS) job.releaseTerrainTicket();
        JOBS.clear();
        DAMAGE_WAVES.clear();
        damageWaveCursor = 0;
    }

    private static void processDamageWaves() {
        if (DAMAGE_WAVES.isEmpty()) return;
        DamageBudget budget = new DamageBudget();
        int remainingWaves = DAMAGE_WAVES.size();
        while (remainingWaves-- > 0 && budget.canContinue()) {
            if (damageWaveCursor >= DAMAGE_WAVES.size()) damageWaveCursor = 0;
            DamageWave wave = DAMAGE_WAVES.get(damageWaveCursor);
            if (!wave.level.getServer().isRunning() || wave.tick(budget)) {
                DAMAGE_WAVES.remove(damageWaveCursor);
                if (DAMAGE_WAVES.isEmpty()) {
                    damageWaveCursor = 0;
                    return;
                }
            } else {
                damageWaveCursor++;
            }
        }
    }

    private static boolean removeWithoutDrops(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.isAir() || state.is(Blocks.BEDROCK)) return false;
        return level.setBlock(pos, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
    }

    private static final class Job {
        final ServerLevel level;
        final double x;
        final double y;
        final double z;
        final NuclearBlastProfile profile;
        final NuclearBlastHistory.BlastRecord historyRecord;
        final DamageWave damageWave;
        final int centerX;
        final int centerZ;
        final int scorchRadius;
        final int terrainRadius;
        final boolean craterEnabled;
        final boolean undergroundCavityEnabled;
        final List<Long> terrainChunks;
        final List<Long> windowChunks;
        final List<SableTarget> sableTargets;
        int terrainChunkIndex;
        int terrainColumnIndex;
        int windowChunkIndex;
        int sableTargetIndex;
        long heldTerrainChunk = Long.MIN_VALUE;
        boolean forcedTerrainChunkThisTick;
        boolean terrainDone;

        Job(
                ServerLevel level,
                double x,
                double y,
                double z,
                NuclearBlastProfile profile,
                NuclearBlastHistory.BlastRecord historyRecord,
                DamageWave damageWave
        ) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.profile = profile;
            this.historyRecord = historyRecord;
            this.damageWave = damageWave;
            this.centerX = (int) Math.floor(x);
            this.centerZ = (int) Math.floor(z);
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, centerX, centerZ) - 1;
            int naturalSurface = findTerrainSurfaceY(centerX, centerZ, top, null);
            boolean hasNaturalGround = naturalSurface != Integer.MIN_VALUE;
            double burialDepth = hasNaturalGround ? naturalSurface - y : 0.0;
            this.undergroundCavityEnabled = hasNaturalGround && burialDepth >= 2.0;
            this.craterEnabled = hasNaturalGround
                    && y - naturalSurface <= Math.max(24.0, profile.craterRadius() * 0.6)
                    && burialDepth <= profile.surfaceBreakoutDepth();
            float cloudRadius = switch (profileOrdinal(profile)) {
                case 1 -> 130.0f;
                case 2 -> 170.0f;
                case 3 -> 300.0f;
                default -> 60.0f;
            };
            this.scorchRadius = CBCESCommonConfig.scorchEnabled()
                    ? Math.min(Math.round(cloudRadius * CBCESCommonConfig.scorchRadiusMultiplier()),
                    CBCESCommonConfig.scorchMaxRadius())
                    : 0;
            this.terrainRadius = Math.max(profile.outerTerrainRadius(), scorchRadius);
            this.terrainChunks = circleChunks(x, z, terrainRadius, true);
            double burstHeight = hasNaturalGround ? y - naturalSurface : 0.0;
            this.windowChunks = prioritizedAftermathChunks(x, z, profile, burstHeight);
            this.sableTargets = SableBridge.targets(level, x, y, z, profile.glassRadius());
        }

        boolean tick(Budget budget) {
            forcedTerrainChunkThisTick = false;
            // Queue loaded aftermath chunks before terrain consumes the block
            // budget, so flash and structural damage appear with the blast.
            processWindows(budget);
            processTerrain(budget);
            processSable(budget);
            return terrainDone
                    && windowChunkIndex >= windowChunks.size()
                    && sableTargetIndex >= sableTargets.size();
        }

        private void processTerrain(Budget budget) {
            if (terrainDone || !budget.canStartWork()) return;
            while (terrainChunkIndex < terrainChunks.size() && budget.canStartWork()) {
                long packed = terrainChunks.get(terrainChunkIndex);
                if (heldTerrainChunk != packed) {
                    releaseTerrainTicket();
                    ChunkPos chunkPos = new ChunkPos(packed);
                    if (level.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) == null) {
                        if (forcedTerrainChunkThisTick) return;
                        level.getChunkSource().addRegionTicket(
                                CBCRealisticBallistics.NUCLEAR_TERRAIN_TICKET,
                                chunkPos, 0, packed, false
                        );
                        level.getChunk(chunkPos.x, chunkPos.z);
                        heldTerrainChunk = packed;
                        forcedTerrainChunkThisTick = true;
                    }
                }

                ChunkPos chunkPos = new ChunkPos(packed);
                // Also catches entities from chunks that became active after
                // the travelling front had already passed their position.
                damageWave.catchUpChunk(chunkPos);
                while (terrainColumnIndex < 256 && budget.canStartWork()) {
                    if (!budget.canProcessColumn(MAX_TERRAIN_SEARCH_DEPTH
                            + profile.craterDepth() + profile.rimHeight()
                            + profile.undergroundCavityVerticalRadius() * 2 + 4)) return;
                    int local = terrainColumnIndex++;
                    int bx = chunkPos.getMinBlockX() + (local & 15);
                    int bz = chunkPos.getMinBlockZ() + (local >>> 4);
                    processTerrainColumn(bx, bz, budget);
                }
                if (terrainColumnIndex < 256) return;
                terrainColumnIndex = 0;
                terrainChunkIndex++;
                releaseTerrainTicket();
                // Already-loaded chunks may continue within the time budget.
                // Newly generated chunks remain limited to one per tick.
                if (forcedTerrainChunkThisTick) return;
            }
            if (terrainChunkIndex >= terrainChunks.size()) {
                terrainDone = true;
                releaseTerrainTicket();
            }
        }

        private void processTerrainColumn(int bx, int bz, Budget budget) {
            double dx = bx + 0.5 - x;
            double dz = bz + 0.5 - z;
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance > terrainRadius) return;
            // WORLD_SURFACE starts above every non-air block, including leaves,
            // flowers, ladders and fluids which the motion-blocking map ignores.
            int topY = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz) - 1;
            if (topY <= level.getMinBuildHeight()) return;
            BlockPos top = new BlockPos(bx, topY, bz);

            double craterDistance = Math.max(0.0, distance - BLOCK_HALF_DIAGONAL);
            int cavityHalfHeight = undergroundCavityEnabled
                    ? profile.undergroundCavityHalfHeight(distance)
                    : 0;
            if (cavityHalfHeight > 0) {
                excavateUndergroundCavity(bx, bz, cavityHalfHeight, budget);
            }
            boolean terrainShape = craterEnabled
                    && distance <= profile.outerTerrainRadius() + BLOCK_HALF_DIAGONAL;
            int localGroundY = terrainShape
                    ? findTerrainSurfaceY(bx, bz, topY, budget)
                    : Integer.MIN_VALUE;
            if (craterEnabled && craterDistance <= profile.craterRadius()) {
                // The crater depth is measured from the natural local surface,
                // never from a building and never from the centre column. It
                // can only excavate existing terrain, not draw a bowl in air.
                if (localGroundY == Integer.MIN_VALUE) return;
                int floorY = Math.max(
                        level.getMinBuildHeight(),
                        profile.craterFloorY(localGroundY, craterDistance)
                );
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, topY, bz);
                // A started column is always completed. At most one world-height
                // column can exceed the tick budget, avoiding permanent remnants.
                for (int by = topY; by > floorY; --by) {
                    pos.setY(by);
                    budget.inspect();
                    BlockState state = level.getBlockState(pos);
                    if (removeWithoutDrops(level, pos, state)) budget.change();
                }
                pos.setY(floorY);
                budget.inspect();
                BlockState floor = level.getBlockState(pos);
                boolean scorchedTransition = craterDistance
                        >= Math.max(0.0, profile.craterRadius() - profile.rimWidth());
                boolean floorOpenedIntoCavity = cavityHalfHeight > 0
                        && Math.abs((floorY + 0.5) - y) <= cavityHalfHeight + 0.5;
                if (!floorOpenedIntoCavity
                        && !floor.is(Blocks.BEDROCK)
                        && (scorchedTransition || isScorchableSurface(floor))
                        && level.setBlock(pos, Blocks.COARSE_DIRT.defaultBlockState(), REMOVE_FLAGS)) {
                    budget.change();
                }
                return;
            }

            if (craterEnabled
                    && distance <= profile.outerTerrainRadius() + BLOCK_HALF_DIAGONAL) {
                if (localGroundY == Integer.MIN_VALUE) return;
                int rim = profile.rimLayers(distance);
                int desiredSurfaceY = Math.min(
                        level.getMaxBuildHeight() - 1, localGroundY + rim);
                int foundationY = Math.max(
                        level.getMinBuildHeight(),
                        localGroundY - Math.max(4, profile.rimHeight())
                );
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(
                        bx, localGroundY, bz);
                BlockState originalBase = level.getBlockState(pos);
                BlockState fill = rimMaterial(originalBase);
                // Remove every structure above the final rim profile first.
                for (int by = topY; by > desiredSurfaceY; --by) {
                    pos.setY(by);
                    budget.inspect();
                    BlockState state = level.getBlockState(pos);
                    if (removeWithoutDrops(level, pos, state)) budget.change();
                }
                // Replace the complete foundation-to-surface volume. This
                // prevents buried buildings, paths and caves from leaving a
                // hollow rim or breaking through it after later block updates.
                for (int by = foundationY; by <= desiredSurfaceY; ++by) {
                    pos.setY(by);
                    budget.inspect();
                    BlockState state = level.getBlockState(pos);
                    if (state.is(Blocks.BEDROCK) || state.equals(fill)) continue;
                    if (level.setBlock(pos, fill, REMOVE_FLAGS)) budget.change();
                }
                return;
            }

            if (!ClaimProtectionHelper.canModifyBlock(level, top)) return;
            if (distance <= scorchRadius) scorchSurface(top, budget);
        }

        private void excavateUndergroundCavity(
                int bx, int bz, int halfHeight, Budget budget
        ) {
            int minimumY = Math.max(
                    level.getMinBuildHeight(), (int) Math.floor(y - halfHeight));
            int maximumY = Math.min(
                    level.getMaxBuildHeight() - 1, (int) Math.ceil(y + halfHeight));
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, minimumY, bz);
            for (int by = minimumY; by <= maximumY; ++by) {
                pos.setY(by);
                budget.inspect();
                BlockState state = level.getBlockState(pos);
                if (removeWithoutDrops(level, pos, state)) budget.change();
            }
        }

        private int findTerrainSurfaceY(int bx, int bz, int topY, Budget budget) {
            int minimumY = Math.max(
                    level.getMinBuildHeight(), topY - MAX_TERRAIN_SEARCH_DEPTH);
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, topY, bz);
            for (int by = topY; by >= minimumY; --by) {
                pos.setY(by);
                if (budget != null) budget.inspect();
                BlockState state = level.getBlockState(pos);
                if (NuclearAftermathManager.isLikelyTerrain(state)) return by;
            }
            return Integer.MIN_VALUE;
        }

        private void scorchSurface(BlockPos top, Budget budget) {
            BlockState state = level.getBlockState(top);
            budget.inspect();
            if (state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.MYCELIUM)
                    || state.is(Blocks.PODZOL)
                    || state.is(Blocks.MOSS_BLOCK)
                    || state.is(Blocks.DIRT_PATH)
                    || state.is(Blocks.FARMLAND)) {
                if (level.setBlock(top, Blocks.COARSE_DIRT.defaultBlockState(), REMOVE_FLAGS)) budget.change();
                return;
            }
            String path = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).getPath();
            if (path.contains("grass") || path.contains("flower") || path.contains("fern")
                    || path.contains("leaves") || path.contains("bush") || path.contains("moss_carpet")) {
                if (removeWithoutDrops(level, top, state)) budget.change();
            }
        }

        private void processWindows(Budget budget) {
            int probes = 0;
            int enqueued = 0;
            while (windowChunkIndex < windowChunks.size()
                    && probes++ < WINDOW_CHUNK_PROBES_PER_TICK
                    && enqueued < WINDOW_CHUNK_ENQUEUES_PER_TICK
                    && budget.canStartWork()) {
                ChunkPos pos = new ChunkPos(windowChunks.get(windowChunkIndex++));
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) continue;
                NuclearAftermathManager.enqueue(level, chunk, historyRecord);
                enqueued++;
            }
        }

        private void processSable(Budget budget) {
            while (sableTargetIndex < sableTargets.size() && budget.canStartWork()) {
                SableTarget target = sableTargets.get(sableTargetIndex);
                if (target.process(this, budget)) return;
                sableTargetIndex++;
            }
        }

        private double distanceSquared(BlockPos pos) {
            double dx = pos.getX() + 0.5 - x;
            double dy = pos.getY() + 0.5 - y;
            double dz = pos.getZ() + 0.5 - z;
            return dx * dx + dy * dy + dz * dz;
        }

        void releaseTerrainTicket() {
            if (heldTerrainChunk == Long.MIN_VALUE) return;
            level.getChunkSource().removeRegionTicket(
                    CBCRealisticBallistics.NUCLEAR_TERRAIN_TICKET,
                    new ChunkPos(heldTerrainChunk), 0, heldTerrainChunk, false
            );
            heldTerrainChunk = Long.MIN_VALUE;
        }
    }

    private static BlockState rimMaterial(BlockState surface) {
        if (surface.is(Blocks.STONE) || surface.is(Blocks.DEEPSLATE)) return surface;
        return Blocks.COARSE_DIRT.defaultBlockState();
    }

    private static boolean isScorchableSurface(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.FARMLAND)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MOSS_BLOCK)
                || state.is(Blocks.ROOTED_DIRT);
    }

    private static int profileOrdinal(NuclearBlastProfile profile) {
        if (profile.craterRadius() == 80) return 1;
        if (profile.craterRadius() == 90) return 2;
        if (profile.craterRadius() == 240) return 3;
        return 0;
    }

    private static List<Long> circleChunks(double x, double z, int radius, boolean nearestFirst) {
        List<Long> chunks = NuclearScorchChunkSupport.intersectingChunks(x, z, radius);
        if (nearestFirst) {
            chunks.sort(Comparator.comparingDouble(packed -> {
                double dx = ChunkPos.getX(packed) * 16.0 + 8.0 - x;
                double dz = ChunkPos.getZ(packed) * 16.0 + 8.0 - z;
                return dx * dx + dz * dz;
            }));
        }
        return chunks;
    }

    private static List<Long> prioritizedAftermathChunks(
            double x, double z, NuclearBlastProfile profile, double burstHeight
    ) {
        // Surface scorching and nearby structures must be visible first. Only
        // this smaller thermal area is sorted; the enormous outer glass area
        // remains unsorted to avoid a detonation-time sorting spike.
        List<Long> priority = circleChunks(
                x, z, profile.thermalFlashMaximumRadius(burstHeight), true);
        Set<Long> included = new HashSet<>(priority);
        List<Long> all = circleChunks(x, z, profile.glassRadius(), false);
        ArrayList<Long> ordered = new ArrayList<>(all.size());
        ordered.addAll(priority);
        for (long packed : all) {
            if (included.add(packed)) ordered.add(packed);
        }
        return ordered;
    }

    private static final class Budget {
        int changes;
        int inspections;
        final int maxChanges;
        final int maxInspections;
        final long deadlineNanos;

        Budget(int maxChanges, int maxInspections) {
            this.maxChanges = maxChanges;
            this.maxInspections = maxInspections;
            this.deadlineNanos = System.nanoTime() + MAX_WORK_NANOS_PER_TICK;
        }

        boolean canInspect() {
            return changes < maxChanges && inspections < maxInspections;
        }

        boolean canStartWork() {
            return canInspect() && System.nanoTime() < deadlineNanos;
        }

        boolean canProcessColumn(int worstCaseChanges) {
            return canStartWork()
                    && maxChanges - changes >= worstCaseChanges
                    && maxInspections - inspections >= worstCaseChanges;
        }

        void inspect() {
            inspections++;
        }

        void change() {
            changes++;
        }
    }

    private static final class SableTarget {
        final ServerSubLevel subLevel;
        final BoundingBox3ic bounds;
        int index;

        SableTarget(ServerSubLevel subLevel) {
            this.subLevel = subLevel;
            this.bounds = subLevel.getPlot().getBoundingBox();
        }

        boolean process(Job job, Budget budget) {
            if (subLevel.isRemoved() || bounds.volume() <= 0) return false;
            int width = bounds.width();
            int length = bounds.length();
            int plane = width * length;
            double damageSquared = (double) job.profile.constructDamageRadius()
                    * job.profile.constructDamageRadius();
            double glassSquared = (double) job.profile.glassRadius() * job.profile.glassRadius();
            BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
            while (index < bounds.volume() && budget.canStartWork()) {
                int current = index++;
                int ly = current / plane;
                int remainder = current - ly * plane;
                int lz = remainder / width;
                int lx = remainder - lz * width;
                localPos.set(bounds.minX() + lx, bounds.minY() + ly, bounds.minZ() + lz);
                budget.inspect();
                BlockState state = job.level.getBlockState(localPos);
                if (state.isAir()) continue;
                Vec3 world = subLevel.logicalPose().transformPosition(localPos.getCenter());
                double dx = world.x - job.x;
                double dy = world.y - job.y;
                double dz = world.z - job.z;
                double distanceSquared = dx * dx + dy * dy + dz * dz;
                boolean destroy = distanceSquared <= damageSquared;
                if (!destroy && distanceSquared <= glassSquared) {
                    BlockEntity blockEntity = job.level.getBlockEntity(localPos);
                    destroy = NuclearGlassSupport.isGlass(state)
                            || NuclearGlassSupport.isGlassFilled(blockEntity);
                }
                if (destroy && removeWithoutDrops(job.level, localPos, state)) budget.change();
            }
            return index < bounds.volume();
        }
    }

    private static final class DamageWave {
        final ServerLevel level;
        final double x;
        final double y;
        final double z;
        final double maximumRadius;
        final long startedGameTime;
        final Set<UUID> hitEntities = new HashSet<>();
        final Set<UUID> discoveredEntities = new HashSet<>();
        final List<UUID> candidateEntities = new ArrayList<>();
        Iterator<Entity> discoveryIterator;
        int evaluationCursor;
        int finalSweepCursor = -1;
        boolean discoveryComplete;

        DamageWave(
                ServerLevel level,
                double x,
                double y,
                double z,
                double maximumRadius,
                long startedGameTime
        ) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.maximumRadius = maximumRadius;
            this.startedGameTime = startedGameTime;
        }

        boolean tick(DamageBudget budget) {
            long elapsed = level.getGameTime() - startedGameTime;
            double radius = NuclearDamageWaveMath.radius(elapsed, maximumRadius);
            if (radius < 0.0) return false;
            discoverCandidates(budget);
            double radiusSquared = radius * radius;
            if (radius >= maximumRadius && discoveryComplete) {
                if (finalSweepCursor < 0) finalSweepCursor = 0;
                while (finalSweepCursor < candidateEntities.size()
                        && budget.canEvaluate()) {
                    damageCandidate(candidateEntities.get(finalSweepCursor++), radiusSquared);
                    budget.evaluated();
                }
                return finalSweepCursor >= candidateEntities.size();
            }
            evaluateCurrentCandidates(radiusSquared, budget);
            return false;
        }

        void catchUpChunk(ChunkPos chunkPos) {
            long elapsed = level.getGameTime() - startedGameTime;
            double radius = NuclearDamageWaveMath.radius(elapsed, maximumRadius);
            if (radius <= 0.0) return;
            AABB chunkBounds = new AABB(
                    chunkPos.getMinBlockX(), level.getMinBuildHeight(), chunkPos.getMinBlockZ(),
                    chunkPos.getMinBlockX() + 16.0, level.getMaxBuildHeight(),
                    chunkPos.getMinBlockZ() + 16.0
            );
            damageEntities(chunkBounds, -1.0, radius * radius);
        }

        private void damageEntities(
                AABB bounds, double innerRadiusSquared, double outerRadiusSquared
        ) {
            for (LivingEntity entity : level.getEntitiesOfClass(
                    LivingEntity.class,
                    bounds,
                    candidate -> isDamageable(candidate)
                            && !hitEntities.contains(candidate.getUUID())
            )) {
                double dx = entity.getX() - x;
                double dz = entity.getZ() - z;
                double horizontalDistanceSquared = dx * dx + dz * dz;
                if (horizontalDistanceSquared < innerRadiusSquared
                        || horizontalDistanceSquared > outerRadiusSquared) continue;
                float damage = NuclearDamageWaveMath.damage(
                        Math.sqrt(horizontalDistanceSquared), maximumRadius);
                if (damage <= 0.0f) continue;
                entity.hurt(level.damageSources().genericKill(), damage);
                hitEntities.add(entity.getUUID());
            }
        }

        private void discoverCandidates(DamageBudget budget) {
            if (discoveryComplete) return;
            if (discoveryIterator == null) discoveryIterator = level.getAllEntities().iterator();
            try {
                while (budget.canDiscover() && discoveryIterator.hasNext()) {
                    Entity candidate = discoveryIterator.next();
                    budget.discovered();
                    if (candidate instanceof LivingEntity entity
                            && isDamageable(entity)
                            && discoveredEntities.add(entity.getUUID())) {
                        candidateEntities.add(entity.getUUID());
                    }
                }
                if (!discoveryIterator.hasNext()) {
                    discoveryIterator = null;
                    discoveryComplete = true;
                }
            } catch (ConcurrentModificationException changedDuringIteration) {
                // Entity collections may rotate while the scan is spread over
                // several ticks. Restart safely; UUID de-duplication preserves
                // prior progress and, crucially, no tick loses its time cap.
                discoveryIterator = null;
            }
        }

        private void evaluateCurrentCandidates(double radiusSquared, DamageBudget budget) {
            int available = candidateEntities.size();
            int checked = 0;
            while (checked++ < available && budget.canEvaluate()) {
                if (evaluationCursor >= candidateEntities.size()) evaluationCursor = 0;
                damageCandidate(candidateEntities.get(evaluationCursor++), radiusSquared);
                budget.evaluated();
            }
        }

        private void damageCandidate(UUID entityId, double radiusSquared) {
            if (hitEntities.contains(entityId)) return;
            Entity candidate = level.getEntity(entityId);
            if (!(candidate instanceof LivingEntity entity) || !isDamageable(entity)) return;
            double dx = entity.getX() - x;
            double dz = entity.getZ() - z;
            double horizontalDistanceSquared = dx * dx + dz * dz;
            if (horizontalDistanceSquared > radiusSquared) return;
            float damage = NuclearDamageWaveMath.damage(
                    Math.sqrt(horizontalDistanceSquared), maximumRadius);
            if (damage <= 0.0f) return;
            entity.hurt(level.damageSources().genericKill(), damage);
            hitEntities.add(entityId);
        }

        private static boolean isDamageable(LivingEntity entity) {
            if (!entity.isAlive()) return false;
            return !(entity instanceof ServerPlayer player)
                    || (!player.isCreative() && !player.isSpectator());
        }
    }

    /** Shared hard cap for all active waves in one server tick. */
    private static final class DamageBudget {
        final long deadline = System.nanoTime() + MAX_DAMAGE_WORK_NANOS_PER_TICK;
        int discoveries;
        int evaluations;

        boolean canContinue() {
            return System.nanoTime() < deadline
                    && (discoveries < MAX_DAMAGE_DISCOVERY_PER_TICK
                    || evaluations < MAX_DAMAGE_EVALUATIONS_PER_TICK);
        }

        boolean canDiscover() {
            return discoveries < MAX_DAMAGE_DISCOVERY_PER_TICK
                    && System.nanoTime() < deadline;
        }

        boolean canEvaluate() {
            return evaluations < MAX_DAMAGE_EVALUATIONS_PER_TICK
                    && System.nanoTime() < deadline;
        }

        void discovered() {
            discoveries++;
        }

        void evaluated() {
            evaluations++;
        }
    }

    private static final class SableBridge {
        static List<SableTarget> targets(
                ServerLevel level, double x, double y, double z, int radius
        ) {
            if (!ModList.get().isLoaded("sable")) return List.of();
            try {
                BoundingBox3d bounds = new BoundingBox3d(
                        x - radius, y - radius, z - radius,
                        x + radius, y + radius, z + radius
                );
                List<SableTarget> targets = new ArrayList<>();
                for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, bounds)) {
                    if (subLevel instanceof ServerSubLevel serverSubLevel && !serverSubLevel.isRemoved()) {
                        targets.add(new SableTarget(serverSubLevel));
                    }
                }
                return targets;
            } catch (LinkageError | RuntimeException error) {
                CBCRealisticBallistics.LOGGER.warn(
                        "Could not enumerate Sable constructions for nuclear damage", error);
                return List.of();
            }
        }
    }
}
