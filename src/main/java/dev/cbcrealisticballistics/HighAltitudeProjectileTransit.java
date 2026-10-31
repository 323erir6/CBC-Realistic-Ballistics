package dev.cbcrealisticballistics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import dev.cbcrealisticballistics.access.HighAltitudeTransitAccess;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import dev.cbcrealisticballistics.access.CollisionProbeAccess;
import net.neoforged.fml.ModList;

/**
 * Keeps a projectile's simulation alive after it enters an unloaded chunk.
 * High sky remains ticket-free. Unknown terrain never causes a synchronous
 * chunk read: low flight uses the bounded asynchronous loader, while CBC
 * still receives loaded-world, entity and Sable collision results.
 */
public final class HighAltitudeProjectileTransit {
    private static final double HARD_DELETE_Y = -70.0;
    private static final Map<ServerLevel, Set<AbstractCannonProjectile>> PROJECTILES =
            new IdentityHashMap<>();
    /**
     * Projectiles registered re-entrantly while a level is being traversed.
     *
     * <p>Version 1.9.14 avoided iterator invalidation by copying every tracked
     * projectile to a fresh array every level tick. Long-lived salvos made
     * that a steady allocation source and could turn a world-save GC into a
     * long stop-the-world pause. Registrations are server-thread operations,
     * so a small identity-set deferred until the traversal ends provides the
     * same safety without a per-tick snapshot.</p>
     */
    private static final Map<ServerLevel, Set<AbstractCannonProjectile>> PENDING_REGISTRATIONS =
            new IdentityHashMap<>();
    private static final Map<ServerLevel, Set<AbstractCannonProjectile>> PENDING_REMOVALS =
            new IdentityHashMap<>();
    private static final Set<ServerLevel> ITERATING_LEVELS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Set<ServerLevel> LEGACY_PURGE_COMPLETED =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static final ThreadLocal<Integer> NO_MAIN_LEVEL_COLLISION_DEPTH =
            ThreadLocal.withInitial(() -> 0);
    private static final ThreadLocal<AbstractCannonProjectile> COLLISION_PROJECTILE =
            new ThreadLocal<>();
    private static final ThreadLocal<AbstractCannonProjectile> LIGHTWEIGHT_BASE_TICK =
            new ThreadLocal<>();
    private static final ThreadLocal<EmptyRayStack> EMPTY_RAY =
            ThreadLocal.withInitial(EmptyRayStack::new);
    /**
     * Direct handle to CBC's implementation of tick(). A few addons override
     * tick and return immediately when Level.hasChunk() is false, before their
     * call to super.tick(). Invoking the method normally would dispatch back
     * into that same addon override forever, so the transit fallback needs an
     * invokespecial-equivalent handle to the CBC base implementation.
     */
    private static final MethodHandle BASE_CANNON_PROJECTILE_TICK =
            findBaseCannonProjectileTick();

    private HighAltitudeProjectileTransit() {
    }

    public static boolean enabled() {
        return BallisticsConfig.HIGH_ALTITUDE_TRANSIT_ENABLED.get();
    }

    public static double activationAltitude() {
        return BallisticsConfig.HIGH_ALTITUDE_TRANSIT_MIN_Y.get();
    }

    public static boolean shouldActivate(AbstractCannonProjectile projectile) {
        // Seamless transit is armed on the projectile's very first tick.
        // Waiting for an altitude or chunk-boundary test leaves a one-tick
        // race in which CBC/vanilla can suspend a very fast shell before the
        // transit manager owns it. Keeping the mode armed everywhere is
        // cheap: ordinary loaded chunks still use their normal entity tick
        // and exact collision path, while only an actually missed tick is
        // supplied by the manager.
        return enabled()
                && !ProjectileExclusions.isCbcAtFixRocket(projectile)
                && !RealisticFlightModel.isTwentyMillimetreProjectile(projectile);
    }

    public static boolean isActive(AbstractCannonProjectile projectile) {
        if (!(projectile instanceof HighAltitudeTransitAccess access)) return false;
        // Eligibility is resolved once at spawn/first tick and stored on the
        // entity. Hot collision paths then need only one config read and one
        // boolean instead of repeated registry/class-name classification.
        return enabled() && access.cbcRealisticBallistics$isHighAltitudeTransitActive();
    }

    public static void register(AbstractCannonProjectile projectile, ServerLevel level) {
        if (!shouldActivate(projectile)) return;
        Map<ServerLevel, Set<AbstractCannonProjectile>> destination =
                ITERATING_LEVELS.contains(level) ? PENDING_REGISTRATIONS : PROJECTILES;
        destination.computeIfAbsent(
                level,
                ignored -> Collections.newSetFromMap(new IdentityHashMap<>())
        ).add(projectile);
    }

    /** Remove dead/unloaded projectiles immediately instead of retaining them until level post-tick. */
    public static void unregister(AbstractCannonProjectile projectile, ServerLevel level) {
        removeFromLevelSet(PENDING_REGISTRATIONS, level, projectile);
        if (ITERATING_LEVELS.contains(level)) {
            PENDING_REMOVALS.computeIfAbsent(
                    level,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>())
            ).add(projectile);
        } else {
            removeFromLevelSet(PROJECTILES, level, projectile);
        }
    }

    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        ProjectileTransitSavedData.ensureRestored(level);
        long diagnosticStart = PerformanceDiagnostics.startTiming();
        int trackedCount = 0;
        int suppliedTicks = 0;
        try {
            long gameTime = level.getGameTime();
            // Old versions could leave transit shells below the void. One sweep
            // repairs an existing save; repeating a whole-world entity traversal
            // every second was unnecessary permanent overhead.
            if (LEGACY_PURGE_COMPLETED.add(level)) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof AbstractCannonProjectile projectile) {
                        discardIfOutsideSimulationBounds(projectile);
                    }
                }
            }
            Set<AbstractCannonProjectile> tracked = PROJECTILES.get(level);
            if (tracked == null || tracked.isEmpty()) return;
            trackedCount = tracked.size();

            // Ticking a projectile may synchronously spawn or register another
            // projectile. Queue those registrations until this traversal is
            // complete, keeping iteration safe without allocating a complete
            // projectile snapshot every server tick.
            ITERATING_LEVELS.add(level);
            Iterator<AbstractCannonProjectile> iterator = tracked.iterator();
            while (iterator.hasNext()) {
                AbstractCannonProjectile projectile = iterator.next();
                if (projectile.level() != level
                        || discardIfOutsideSimulationBounds(projectile)
                        || projectile.isRemoved()) {
                    iterator.remove();
                    continue;
                }
                if (!(projectile instanceof HighAltitudeTransitAccess access)) continue;
                if (!isActive(projectile)) continue;

                access.cbcRealisticBallistics$activateHighAltitudeTransit();
                if (access.cbcRealisticBallistics$getLastServerTick() == gameTime) continue;

                // ServerLevel's ordinary entity loop skips entities outside an
                // entity-ticking chunk. Run precisely the missed tick here.
                // Do not pre-probe here: CBC/addons may change velocity before
                // clipAndDamage. Only that method knows the current swept ray.
                suppliedTicks++;
                // Every manager-supplied tick is outside an entity-ticking
                // chunk, so vanilla's environment polling is both unsafe and
                // redundant. CBC performs the authoritative swept collision
                // below on the authoritative current velocity.
                LIGHTWEIGHT_BASE_TICK.set(projectile);
                try {
                    level.tickNonPassenger(projectile);
                    if (!projectile.isRemoved()
                            && access.cbcRealisticBallistics$getLastServerTick() != gameTime) {
                        // The addon tick returned before calling CBC's base
                        // implementation (CBC Modern Warfare's medium cannon
                        // does this in an unloaded chunk). Supply only the
                        // missing CBC base tick; never tick twice when an addon
                        // already reached super.tick().
                        invokeBaseCannonProjectileTick(projectile);
                    }
                } finally {
                    LIGHTWEIGHT_BASE_TICK.remove();
                }
            }
            if (hasUnloadedProjectile(level, tracked)) {
                ProjectileTransitSavedData.markTransitStateDirty(level);
            }
        } finally {
            finishLevelIteration(level);
            PerformanceDiagnostics.recordTransit(
                    PerformanceDiagnostics.elapsedSince(diagnosticStart), trackedCount, suppliedTicks);
        }
    }

    /** Stable save-time snapshot; never exposes the mutable runtime identity set. */
    public static List<AbstractCannonProjectile> snapshotUnloadedProjectiles(ServerLevel level) {
        Set<AbstractCannonProjectile> tracked = PROJECTILES.get(level);
        if (tracked == null || tracked.isEmpty()) return List.of();
        List<AbstractCannonProjectile> result = new ArrayList<>();
        for (AbstractCannonProjectile projectile : tracked) {
            if (!projectile.isRemoved() && projectile.level() == level
                    && isActive(projectile) && !isCurrentChunkLoaded(projectile)) {
                result.add(projectile);
            }
        }
        return result;
    }

    private static boolean hasUnloadedProjectile(
            ServerLevel level,
            Set<AbstractCannonProjectile> tracked
    ) {
        for (AbstractCannonProjectile projectile : tracked) {
            if (!projectile.isRemoved() && projectile.level() == level
                    && isActive(projectile) && !isCurrentChunkLoaded(projectile)) {
                return true;
            }
        }
        return false;
    }

    private static void finishLevelIteration(ServerLevel level) {
        if (!ITERATING_LEVELS.remove(level)) return;
        Set<AbstractCannonProjectile> removals = PENDING_REMOVALS.remove(level);
        if (removals != null && !removals.isEmpty()) {
            Set<AbstractCannonProjectile> tracked = PROJECTILES.get(level);
            if (tracked != null) tracked.removeAll(removals);
        }
        Set<AbstractCannonProjectile> pending = PENDING_REGISTRATIONS.remove(level);
        if (pending != null && !pending.isEmpty()) {
            Set<AbstractCannonProjectile> tracked = PROJECTILES.computeIfAbsent(
                    level,
                    ignored -> Collections.newSetFromMap(new IdentityHashMap<>())
            );
            for (AbstractCannonProjectile projectile : pending) {
                if (projectile.level() == level && !projectile.isRemoved()) {
                    tracked.add(projectile);
                }
            }
        }
        Set<AbstractCannonProjectile> tracked = PROJECTILES.get(level);
        if (tracked != null && tracked.isEmpty()) PROJECTILES.remove(level);
    }

    private static void removeFromLevelSet(
            Map<ServerLevel, Set<AbstractCannonProjectile>> source,
            ServerLevel level,
            AbstractCannonProjectile projectile
    ) {
        Set<AbstractCannonProjectile> projectiles = source.get(level);
        if (projectiles == null) return;
        projectiles.remove(projectile);
        if (projectiles.isEmpty()) source.remove(level);
    }

    private static MethodHandle findBaseCannonProjectileTick() {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                    AbstractCannonProjectile.class,
                    MethodHandles.lookup()
            );
            return lookup.findSpecial(
                    AbstractCannonProjectile.class,
                    "tick",
                    MethodType.methodType(void.class),
                    AbstractCannonProjectile.class
            );
        } catch (IllegalAccessException | NoSuchMethodException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void invokeBaseCannonProjectileTick(
            AbstractCannonProjectile projectile
    ) {
        try {
            BASE_CANNON_PROJECTILE_TICK.invokeExact(projectile);
        } catch (RuntimeException | Error exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException(
                    "Could not supply the CBC base projectile tick", throwable);
        }
    }

    /** Mirrors vanilla's below-world removal even when baseTick is bypassed. */
    public static boolean discardIfOutsideSimulationBounds(AbstractCannonProjectile projectile) {
        Vec3 position = projectile.position();
        boolean invalid = !Double.isFinite(position.x)
                || !Double.isFinite(position.y)
                || !Double.isFinite(position.z)
                || position.y <= HARD_DELETE_Y
                || !Level.isInSpawnableBounds(net.minecraft.core.BlockPos.containing(position));
        if (invalid && !projectile.isRemoved()) {
            projectile.discard();
        }
        return invalid || projectile.isRemoved();
    }

    public static boolean isChunkLoaded(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    public static boolean isCurrentChunkLoaded(AbstractCannonProjectile projectile) {
        if (!(projectile.level() instanceof ServerLevel level)) return true;
        ChunkPos chunk = projectile.chunkPosition();
        return isChunkLoaded(level, chunk.x, chunk.z);
    }

    public static boolean isFlightCorridorLoaded(AbstractCannonProjectile projectile) {
        if (!(projectile.level() instanceof ServerLevel level)) return true;
        Vec3 start = projectile.position();
        Vec3 end = start.add(projectile.getDeltaMovement());
        return traceLoaded(level, start.x, start.z, end.x, end.z);
    }

    public static boolean canRaycastSableWithoutLoadingMainChunks() {
        return SableClipBridge.SET_IGNORE_MAIN_LEVEL != null;
    }

    /** True only while supplying a missed entity tick on the server thread. */
    public static boolean isLightweightBaseTick(AbstractCannonProjectile projectile) {
        return LIGHTWEIGHT_BASE_TICK.get() == projectile;
    }


    /** CBC traces v + a, not v: acceleration must be included at chunk edges. */
    public static Vec3 collisionMovement(AbstractCannonProjectile projectile) {
        return projectile instanceof CollisionProbeAccess probe
                ? probe.cbcRealisticBallistics$getCollisionMovement()
                : projectile.getDeltaMovement();
    }

    /** Only ordinary world blocks may be skipped; entities and Sable stay active. */
    public static boolean isAboveBlockCollisionCeiling(AbstractCannonProjectile projectile) {
        double lowest = projectile.getBoundingBox().minY
                + Math.min(0.0, collisionMovement(projectile).y) - 1.0;
        return lowest >= Math.max(activationAltitude(), projectile.level().getMaxBuildHeight());
    }

    /**
     * Narrow swept segments make broad-phase cost linear in path length rather
     * than the volume of its enclosing box. Never cache an empty moving-entity
     * or Sable cell: another entity can enter it during this very server tick.
     * Unknown low terrain is a candidate, NOT evidence of empty air.
     */
    public static boolean hasPotentialLoadedCollision(AbstractCannonProjectile projectile) {
        if (!(projectile.level() instanceof ServerLevel level)) return true;
        Vec3 motion = collisionMovement(projectile);
        return hasPotentialLoadedCollision(projectile, level, motion);
    }

    public static boolean hasPotentialLoadedCollision(
            AbstractCannonProjectile projectile,
            ServerLevel level,
            Vec3 motion
    ) {
        int count = CollisionSegments.count(motion.x, motion.y, motion.z);
        if (count < 0) return true; // Invalid state: retain the original path.
        AABB base = projectile.getBoundingBox().inflate(1.0);
        LoadedOnlyBlockGetter getter = null;
        for (int i = 0; i < count; i++) {
            AABB box = CollisionSegments.box(base, motion, i, count);
            int terrain = mainLevelOccupancy(level, box);
            // Unknown terrain is not safe to prune. Resident terrain can be
            // checked cheaply without invoking Sable's transformed voxel rays.
            if (terrain == 2) return true;
            if (terrain == 1) {
                double fromScale = (double) i / count;
                double toScale = (double) (i + 1) / count;
                Vec3 origin = projectile.position();
                Vec3 from = new Vec3(
                        origin.x + motion.x * fromScale,
                        origin.y + motion.y * fromScale,
                        origin.z + motion.z * fromScale);
                Vec3 to = new Vec3(
                        origin.x + motion.x * toScale,
                        origin.y + motion.y * toScale,
                        origin.z + motion.z * toScale);
                if (getter == null) getter = new LoadedOnlyBlockGetter(level);
                if (getter.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, projectile)).getType() != HitResult.Type.MISS
                        || getter.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.ANY, projectile)).getType() != HitResult.Type.MISS) return true;
            }
            if (hasSable(level, box)) return true;
        }
        return false;
    }

    public static boolean hasPotentialRemoteCollision(AbstractCannonProjectile projectile) {
        return hasPotentialLoadedCollision(projectile);
    }

    /** 0 = known air, 1 = resident terrain, 2 = unknown terrain. */
    private static int mainLevelOccupancy(ServerLevel level, AABB box) {
        int minY = Math.max(level.getMinSection(), ((int) Math.floor(box.minY)) >> 4);
        int maxY = Math.min(level.getMaxSection() - 1,
                ((int) Math.floor(box.maxY)) >> 4);
        if (minY > maxY) return 0;
        int result = 0;
        for (int x = blockToChunk(box.minX); x <= blockToChunk(box.maxX); x++) {
            for (int z = blockToChunk(box.minZ); z <= blockToChunk(box.maxZ); z++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
                if (chunk == null) return 2;
                for (int y = minY; y <= maxY; y++) {
                    // hasOnlyAir is an O(1) live counter, not a voxel scan.
                    if (!chunk.getSection(level.getSectionIndex(y << 4)).hasOnlyAir()) result = 1;
                }
            }
        }
        return result;
    }

    private static boolean hasSable(ServerLevel level, AABB box) {
        if (!SableBroadphase.available() && !ModList.get().isLoaded("sable")) return false;
        return SableBroadphase.hasAny(level, box, 0.0);
    }

    /**
     * Adds the main-level result from already loaded chunks to Sable's
     * transformed-sublevel ray result at every altitude. The BlockGetter
     * returns air for an unloaded chunk and therefore can never synchronously
     * load the flight corridor.
     */
    public static BlockHitResult mergeLoadedMainLevelHit(
            ServerLevel level,
            ClipContext context,
            BlockHitResult existing
    ) {
        if (!isNoMainLevelCollisionActive()) return existing;
        BlockHitResult loaded = new LoadedOnlyBlockGetter(level).clip(context);
        if (loaded.getType() == HitResult.Type.MISS) return existing;
        if (existing == null || existing.getType() == HitResult.Type.MISS) return loaded;
        double loadedDistance = context.getFrom().distanceToSqr(loaded.getLocation());
        double existingDistance = context.getFrom().distanceToSqr(existing.getLocation());
        return loadedDistance < existingDistance ? loaded : existing;
    }

    public static void beginNoMainLevelCollision(AbstractCannonProjectile projectile) {
        NO_MAIN_LEVEL_COLLISION_DEPTH.set(NO_MAIN_LEVEL_COLLISION_DEPTH.get() + 1);
        COLLISION_PROJECTILE.set(projectile);
    }

    public static void endNoMainLevelCollision() {
        int depth = NO_MAIN_LEVEL_COLLISION_DEPTH.get() - 1;
        if (depth <= 0) {
            NO_MAIN_LEVEL_COLLISION_DEPTH.remove();
            COLLISION_PROJECTILE.remove();
        } else {
            NO_MAIN_LEVEL_COLLISION_DEPTH.set(depth);
        }
    }

    public static boolean isNoMainLevelCollisionActive() {
        return NO_MAIN_LEVEL_COLLISION_DEPTH.get() > 0;
    }

    public static void beginProvenEmptyRay(ServerLevel level, Vec3 from, Vec3 to) {
        EMPTY_RAY.get().push(level, from, to);
    }

    public static void endProvenEmptyRay() {
        EMPTY_RAY.get().pop();
    }

    /** Also covers clip calls redirected by cbc_aero_velocity_fix. Only the
     * exact proven ray is skipped; unrelated fuze/addon queries stay intact. */
    public static boolean isProvenEmptyRay(ServerLevel level, ClipContext context) {
        EmptyRay ray = EMPTY_RAY.get().current();
        return ray != null && ray.level == level
                && ray.from.equals(context.getFrom()) && ray.to.equals(context.getTo());
    }

    public static void configureClipContext(Object clipContext) {
        MethodHandle method = SableClipBridge.SET_IGNORE_MAIN_LEVEL;
        if (method == null) return;
        try {
            method.invokeExact(clipContext, true);
            AbstractCannonProjectile projectile = COLLISION_PROJECTILE.get();
            // A fast long penetrator can begin its first swept ray while its
            // origin is still inside the firing Sable construction. Treating
            // that as a real impact applies the APFSDS impulse to its own
            // launcher, wakes the whole Rapier island and can remove the shot.
            // Ignore only that containing sublevel during muzzle clearance;
            // all later Sable intersections remain fully authoritative.
            if (projectile != null && projectile.tickCount <= 3) {
                SableClipBridge.ignoreContainingLaunchSubLevel(
                        clipContext, projectile.level(), projectile.position());
            }
        } catch (Throwable ignored) {
            // Capability detection prevents entering this path if Sable is
            // absent. A single incompatible context must not crash the shot.
        }
    }

    private static boolean traceLoaded(ServerLevel level,
                                       double startX, double startZ,
                                       double endX, double endZ) {
        int chunkX = blockToChunk(startX);
        int chunkZ = blockToChunk(startZ);
        int endChunkX = blockToChunk(endX);
        int endChunkZ = blockToChunk(endZ);
        if (!isChunkLoaded(level, chunkX, chunkZ)) return false;

        double dx = endX - startX;
        double dz = endZ - startZ;
        int stepX = Double.compare(dx, 0.0);
        int stepZ = Double.compare(dz, 0.0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        double nextBoundaryX = stepX > 0 ? (chunkX + 1) * 16.0 : chunkX * 16.0;
        double nextBoundaryZ = stepZ > 0 ? (chunkZ + 1) * 16.0 : chunkZ * 16.0;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryX - startX) / dx;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (nextBoundaryZ - startZ) / dz;

        while (chunkX != endChunkX || chunkZ != endChunkZ) {
            if (tMaxX < tMaxZ) {
                chunkX += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxZ < tMaxX) {
                chunkZ += stepZ;
                tMaxZ += tDeltaZ;
            } else {
                chunkX += stepX;
                chunkZ += stepZ;
                tMaxX += tDeltaX;
                tMaxZ += tDeltaZ;
            }
            if (!isChunkLoaded(level, chunkX, chunkZ)) return false;
        }
        return true;
    }

    private static int blockToChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    /** Reuses the common empty-ray frame instead of allocating one every shot tick. */
    private static final class EmptyRayStack {
        private EmptyRay[] frames = {new EmptyRay()};
        private int depth;

        private void push(ServerLevel level, Vec3 from, Vec3 to) {
            if (this.depth == this.frames.length) {
                EmptyRay[] grown = new EmptyRay[this.frames.length << 1];
                System.arraycopy(this.frames, 0, grown, 0, this.frames.length);
                for (int i = this.frames.length; i < grown.length; i++) grown[i] = new EmptyRay();
                this.frames = grown;
            }
            EmptyRay frame = this.frames[this.depth++];
            frame.level = level;
            frame.from = from;
            frame.to = to;
        }

        private void pop() {
            if (this.depth <= 0) return;
            EmptyRay frame = this.frames[--this.depth];
            frame.level = null;
            frame.from = null;
            frame.to = null;
        }

        private EmptyRay current() {
            return this.depth == 0 ? null : this.frames[this.depth - 1];
        }
    }

    private static final class EmptyRay {
        private ServerLevel level;
        private Vec3 from;
        private Vec3 to;
    }

    private static final class LoadedOnlyBlockGetter implements BlockGetter {
        private final ServerLevel level;
        private int lastX = Integer.MIN_VALUE;
        private int lastZ = Integer.MIN_VALUE;
        private LevelChunk lastChunk;

        private LoadedOnlyBlockGetter(ServerLevel level) {
            this.level = level;
        }

        private LevelChunk chunk(BlockPos position) {
            if (this.level.isOutsideBuildHeight(position)) return null;
            int x = position.getX() >> 4;
            int z = position.getZ() >> 4;
            if (x != this.lastX || z != this.lastZ) {
                this.lastX = x;
                this.lastZ = z;
                this.lastChunk = this.level.getChunkSource().getChunkNow(x, z);
            }
            return this.lastChunk;
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos position) {
            LevelChunk chunk = chunk(position);
            return chunk == null ? null : chunk.getBlockEntity(position);
        }

        @Override
        public BlockState getBlockState(BlockPos position) {
            LevelChunk chunk = chunk(position);
            return chunk == null ? Blocks.AIR.defaultBlockState() : chunk.getBlockState(position);
        }

        @Override
        public FluidState getFluidState(BlockPos position) {
            LevelChunk chunk = chunk(position);
            return chunk == null
                    ? Blocks.AIR.defaultBlockState().getFluidState()
                    : chunk.getFluidState(position);
        }

        @Override
        public int getHeight() {
            return this.level.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return this.level.getMinBuildHeight();
        }
    }

    public static void clearRuntimeState() {
        PROJECTILES.clear();
        PENDING_REGISTRATIONS.clear();
        PENDING_REMOVALS.clear();
        ITERATING_LEVELS.clear();
        LEGACY_PURGE_COMPLETED.clear();
        NO_MAIN_LEVEL_COLLISION_DEPTH.remove();
        COLLISION_PROJECTILE.remove();
        LIGHTWEIGHT_BASE_TICK.remove();
        EMPTY_RAY.remove();
    }

    /** Optional Sable API bridge kept reflective so Sable remains optional. */
    private static final class SableBroadphase {
        private static final Object HELPER;
        private static final MethodHandle BOX_CONSTRUCTOR;
        private static final MethodHandle BOX_SET;
        private static final MethodHandle GET_ALL_INTERSECTING;
        private static final ThreadLocal<Object> REUSABLE_BOX;

        static {
            Object helper = null;
            MethodHandle boxConstructor = null;
            MethodHandle boxSet = null;
            MethodHandle getAllIntersecting = null;
            try {
                ClassLoader loader = HighAltitudeProjectileTransit.class.getClassLoader();
                Class<?> sableClass = Class.forName("dev.ryanhcode.sable.Sable", false, loader);
                Class<?> boxClass = Class.forName(
                        "dev.ryanhcode.sable.companion.math.BoundingBox3d", false, loader);
                helper = sableClass.getField("HELPER").get(null);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                boxConstructor = lookup.unreflectConstructor(boxClass.getConstructor(
                                double.class, double.class, double.class,
                                double.class, double.class, double.class))
                        .asType(MethodType.methodType(Object.class,
                                double.class, double.class, double.class,
                                double.class, double.class, double.class));
                Method boxSetMethod;
                try {
                    boxSetMethod = boxClass.getMethod("setUnchecked",
                            double.class, double.class, double.class,
                            double.class, double.class, double.class);
                } catch (NoSuchMethodException ignored) {
                    try {
                        boxSetMethod = boxClass.getMethod("set",
                                double.class, double.class, double.class,
                                double.class, double.class, double.class);
                    } catch (NoSuchMethodException ignoredAgain) {
                        boxSetMethod = null;
                    }
                }
                if (boxSetMethod != null) {
                    boxSet = lookup.unreflect(boxSetMethod).asType(MethodType.methodType(
                            Object.class, Object.class,
                            double.class, double.class, double.class,
                            double.class, double.class, double.class));
                }
                for (Method candidate : helper.getClass().getMethods()) {
                    if (candidate.getName().equals("getAllIntersecting")
                            && candidate.getParameterCount() == 2) {
                        getAllIntersecting = lookup.unreflect(candidate).asType(
                                MethodType.methodType(Object.class,
                                        Object.class, Object.class, Object.class));
                        break;
                    }
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
                helper = null;
                boxConstructor = null;
                boxSet = null;
                getAllIntersecting = null;
            }
            HELPER = helper;
            BOX_CONSTRUCTOR = boxConstructor;
            BOX_SET = boxSet;
            GET_ALL_INTERSECTING = getAllIntersecting;
            REUSABLE_BOX = ThreadLocal.withInitial(SableBroadphase::newReusableBox);
        }

        private static Object newReusableBox() {
            if (BOX_CONSTRUCTOR == null || BOX_SET == null) return null;
            try {
                return (Object) BOX_CONSTRUCTOR.invokeExact(
                        0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static boolean hasAny(ServerLevel level, AABB bounds, double margin) {
            if (HELPER == null || BOX_CONSTRUCTOR == null || GET_ALL_INTERSECTING == null) {
                return true;
            }
            try {
                double minX = bounds.minX - margin;
                double minY = bounds.minY - margin;
                double minZ = bounds.minZ - margin;
                double maxX = bounds.maxX + margin;
                double maxY = bounds.maxY + margin;
                double maxZ = bounds.maxZ + margin;
                Object box = REUSABLE_BOX.get();
                if (box != null) {
                    box = (Object) BOX_SET.invokeExact(
                            box, minX, minY, minZ, maxX, maxY, maxZ);
                } else {
                    box = (Object) BOX_CONSTRUCTOR.invokeExact(
                            minX, minY, minZ, maxX, maxY, maxZ);
                }
                Object result = (Object) GET_ALL_INTERSECTING.invokeExact(
                        HELPER, (Object) level, box);
                return !(result instanceof Iterable<?> iterable)
                        || iterable.iterator().hasNext();
            } catch (Throwable ignored) {
                return true;
            }
        }

        private static boolean available() {
            return HELPER != null && BOX_CONSTRUCTOR != null && GET_ALL_INTERSECTING != null;
        }
    }

    private static final class SableClipBridge {
        private static final MethodHandle SET_IGNORE_MAIN_LEVEL = findMethod();
        private static final Method SET_IGNORED_SUB_LEVEL = findIgnoredSubLevelMethod();
        private static final Object HELPER = findHelper();
        private static final Method GET_CONTAINING = findContainingMethod();

        private static MethodHandle findMethod() {
            try {
                Class<?> extension = Class.forName(
                        "dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension",
                        false,
                        HighAltitudeProjectileTransit.class.getClassLoader()
                );
                return MethodHandles.publicLookup().unreflect(
                                extension.getMethod("sable$setIgnoreMainLevel", boolean.class))
                        .asType(MethodType.methodType(
                                void.class, Object.class, boolean.class));
            } catch (ClassNotFoundException | NoSuchMethodException
                     | IllegalAccessException | LinkageError ignored) {
                return null;
            }
        }

        private static Object findHelper() {
            try {
                Class<?> sable = Class.forName(
                        "dev.ryanhcode.sable.Sable", false,
                        HighAltitudeProjectileTransit.class.getClassLoader());
                return sable.getField("HELPER").get(null);
            } catch (ReflectiveOperationException | LinkageError ignored) {
                return null;
            }
        }

        private static Method findIgnoredSubLevelMethod() {
            try {
                Class<?> extension = Class.forName(
                        "dev.ryanhcode.sable.mixinterface.clip_overwrite.ClipContextExtension",
                        false,
                        HighAltitudeProjectileTransit.class.getClassLoader());
                Class<?> subLevel = Class.forName(
                        "dev.ryanhcode.sable.sublevel.SubLevel", false,
                        HighAltitudeProjectileTransit.class.getClassLoader());
                return extension.getMethod("sable$setIgnoredSubLevel", subLevel);
            } catch (ClassNotFoundException | NoSuchMethodException | LinkageError ignored) {
                return null;
            }
        }

        private static Method findContainingMethod() {
            Object helper = HELPER;
            if (helper == null) return null;
            for (Method candidate : helper.getClass().getMethods()) {
                if (!candidate.getName().equals("getContaining")
                        || candidate.getParameterCount() != 2) continue;
                Class<?>[] parameters = candidate.getParameterTypes();
                if (Level.class.isAssignableFrom(parameters[0])
                        && net.minecraft.core.Position.class.isAssignableFrom(parameters[1])) {
                    return candidate;
                }
            }
            return null;
        }

        private static void ignoreContainingLaunchSubLevel(
                Object clipContext, Level level, Vec3 position
        ) throws ReflectiveOperationException {
            if (HELPER == null || GET_CONTAINING == null || SET_IGNORED_SUB_LEVEL == null) return;
            Object containing = GET_CONTAINING.invoke(HELPER, level, position);
            if (containing != null) SET_IGNORED_SUB_LEVEL.invoke(clipContext, containing);
        }
    }
}
