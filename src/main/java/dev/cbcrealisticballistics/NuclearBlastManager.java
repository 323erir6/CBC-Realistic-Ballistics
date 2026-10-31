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
import java.util.Iterator;
import java.util.List;

/**
 * Replaces hundreds of ring TNT explosions with one deterministic, budgeted
 * terrain operation. Every block removal uses UPDATE_SUPPRESS_DROPS.
 */
public final class NuclearBlastManager {
    private static final int REMOVE_FLAGS = Block.UPDATE_CLIENTS
            | Block.UPDATE_KNOWN_SHAPE
            | Block.UPDATE_SUPPRESS_DROPS;
    private static final int MAX_CHANGES_PER_TICK = 900;
    private static final int MAX_INSPECTIONS_PER_TICK = 8000;
    private static final int WINDOW_CHUNK_PROBES_PER_TICK = 64;
    private static final long MAX_WORK_NANOS_PER_TICK = 3_000_000L;
    private static final float CRATER_ENTITY_DAMAGE = 15_000.0f;
    private static final List<Job> JOBS = new ArrayList<>();

    private NuclearBlastManager() {
    }

    public static void start(ServerLevel level, double x, double y, double z, int profileOrdinal) {
        NuclearBlastProfile profile = NuclearBlastProfile.byOrdinal(profileOrdinal);
        damageLivingEntities(level, x, y, z, profile.craterRadius());
        NuclearBlastHistory.BlastRecord historyRecord = NuclearBlastHistory.record(
                level, x, y, z, profileOrdinal);
        JOBS.add(new Job(level, x, y, z, profile, historyRecord));
    }

    private static void damageLivingEntities(
            ServerLevel level, double x, double y, double z, double radius
    ) {
        double radiusSquared = radius * radius;
        AABB bounds = new AABB(
                x - radius, y - radius, z - radius,
                x + radius, y + radius, z + radius
        );
        for (LivingEntity entity : level.getEntitiesOfClass(
                LivingEntity.class,
                bounds,
                candidate -> candidate.isAlive()
                        && candidate.distanceToSqr(x, y, z) <= radiusSquared
        )) {
            entity.hurt(level.damageSources().genericKill(), CRATER_ENTITY_DAMAGE);
        }
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
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
        final int centerX;
        final int centerZ;
        final int groundY;
        final int scorchRadius;
        final int terrainRadius;
        final boolean craterEnabled;
        final List<Long> terrainChunks;
        final List<Long> windowChunks;
        final List<SableTarget> sableTargets;
        int terrainChunkIndex;
        int terrainColumnIndex;
        int windowChunkIndex;
        int sableTargetIndex;
        long heldTerrainChunk = Long.MIN_VALUE;
        boolean loadedTerrainChunkThisTick;
        boolean terrainDone;

        Job(
                ServerLevel level,
                double x,
                double y,
                double z,
                NuclearBlastProfile profile,
                NuclearBlastHistory.BlastRecord historyRecord
        ) {
            this.level = level;
            this.x = x;
            this.y = y;
            this.z = z;
            this.profile = profile;
            this.historyRecord = historyRecord;
            this.centerX = (int) Math.floor(x);
            this.centerZ = (int) Math.floor(z);
            int surface = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centerX, centerZ) - 1;
            this.groundY = Math.min((int) Math.floor(y), surface);
            this.craterEnabled = y - surface <= Math.max(24.0, profile.craterRadius() * 0.6);
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
            // The H-bomb glass radius spans tens of thousands of chunks.
            // Their order is irrelevant, so avoid an O(n log n) boxed sort at detonation.
            this.windowChunks = circleChunks(x, z, profile.glassRadius(), false);
            this.sableTargets = SableBridge.targets(level, x, y, z, profile.glassRadius());
        }

        boolean tick(Budget budget) {
            loadedTerrainChunkThisTick = false;
            processTerrain(budget);
            processWindows(budget);
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
                    if (loadedTerrainChunkThisTick) return;
                    releaseTerrainTicket();
                    ChunkPos chunkPos = new ChunkPos(packed);
                    level.getChunkSource().addRegionTicket(
                            CBCRealisticBallistics.NUCLEAR_TERRAIN_TICKET,
                            chunkPos, 0, packed, false
                    );
                    level.getChunk(chunkPos.x, chunkPos.z);
                    heldTerrainChunk = packed;
                    loadedTerrainChunkThisTick = true;
                }

                ChunkPos chunkPos = new ChunkPos(packed);
                while (terrainColumnIndex < 256 && budget.canStartWork()) {
                    if (!budget.canProcessColumn(profile.craterDepth() + profile.rimHeight() + 2)) return;
                    int local = terrainColumnIndex++;
                    int bx = chunkPos.getMinBlockX() + (local & 15);
                    int bz = chunkPos.getMinBlockZ() + (local >>> 4);
                    processTerrainColumn(bx, bz, budget);
                }
                if (terrainColumnIndex < 256) return;
                terrainColumnIndex = 0;
                terrainChunkIndex++;
                releaseTerrainTicket();
                // Generating more than one new chunk in one tick recreates the
                // autosave/TPS spike this queue is intended to remove.
                return;
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

            if (craterEnabled && distance <= profile.craterRadius()) {
                // The floor is absolute. Basing it on topY copied buildings and
                // trees into the bottom of the crater as raised silhouettes.
                int floorY = Math.max(
                        level.getMinBuildHeight(),
                        profile.craterFloorY(groundY, distance)
                );
                BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, topY, bz);
                for (int by = topY; by > floorY && budget.canInspect(); --by) {
                    pos.setY(by);
                    budget.inspect();
                    BlockState state = level.getBlockState(pos);
                    if (removeWithoutDrops(level, pos, state)) budget.change();
                }
                return;
            }

            if (!ClaimProtectionHelper.canModifyBlock(level, top)) return;

            if (craterEnabled) {
                int rim = profile.rimLayers(distance);
                if (rim > 0) {
                    BlockState fill = rimMaterial(level.getBlockState(top));
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos(bx, topY + 1, bz);
                    for (int layer = 0; layer < rim && budget.canInspect(); ++layer) {
                        pos.setY(topY + 1 + layer);
                        budget.inspect();
                        if (!level.getBlockState(pos).isAir()) break;
                        if (level.setBlock(pos, fill, REMOVE_FLAGS)) budget.change();
                    }
                    return;
                }
            }
            if (distance <= scorchRadius) scorchSurface(top, budget);
        }

        private void scorchSurface(BlockPos top, Budget budget) {
            BlockState state = level.getBlockState(top);
            budget.inspect();
            if (state.is(Blocks.GRASS_BLOCK)
                    || state.is(Blocks.MYCELIUM)
                    || state.is(Blocks.PODZOL)
                    || state.is(Blocks.MOSS_BLOCK)) {
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
            while (windowChunkIndex < windowChunks.size()
                    && probes++ < WINDOW_CHUNK_PROBES_PER_TICK
                    && budget.canStartWork()) {
                ChunkPos pos = new ChunkPos(windowChunks.get(windowChunkIndex++));
                LevelChunk chunk = level.getChunkSource().getChunkNow(pos.x, pos.z);
                if (chunk == null) continue;
                NuclearAftermathManager.enqueue(level, chunk, historyRecord);
                return;
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
