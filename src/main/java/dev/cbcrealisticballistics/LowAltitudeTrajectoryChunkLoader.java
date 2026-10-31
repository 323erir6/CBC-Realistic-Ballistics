package dev.cbcrealisticballistics;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bounded asynchronous terrain streaming for cannon projectiles.
 *
 * <p>Trajectory prediction is incremental and has one strict shared budget.
 * Launching a salvo therefore never runs thousands of simulation steps in a
 * single server tick. Only the bounded terminal area is warmed: streaming a
 * moving window along a fast, flat shot makes Minecraft generate, unload and
 * synchronously save a new chunk every few ticks. Loaded terrain is still
 * collided with by the projectile mixin, while entities and Sable constructs
 * remain available independently of main-world chunk residency.</p>
 */
public final class LowAltitudeTrajectoryChunkLoader {
    private static final String CORRECTION_FLIGHT_ROOT =
            "cbc_programmable_fuzes_tail_flight";
    private static final double COLLISION_CEILING_Y = 350.0;
    private static final int MAX_PREDICTION_TICKS = 7200;
    private static final int MAX_ACTIVE_CHUNKS_PER_PROJECTILE = 10;
    private static final int MAX_NEW_TICKETS_PER_LEVEL_TICK = 1;
    private static final int MAX_PREDICTION_STEPS_PER_LEVEL_TICK = 1024;
    private static final int MAX_PREDICTION_STEPS_PER_PROJECTILE = 8;
    /** Begin retaining the terminal area ten seconds before predicted impact. */
    private static final int TERMINAL_WARMUP_LEAD_TICKS = 200;
    /** Keep the impact chunk and its direct neighbors ready for CBC explosions. */
    private static final int TERMINAL_NEIGHBOR_RADIUS = 1;
    /**
     * Re-anchor the terminal prediction from the shell's real state while it
     * is descending. This corrects accumulated approximation error without
     * streaming the full route.
     */
    private static final double ADAPTIVE_TERMINAL_REPLAN_Y = 512.0;
    private static final int MAX_ADAPTIVE_TERMINAL_STEPS = 1024;
    /** A small rolling route window; new terrain loads are globally throttled. */
    private static final int ROUTE_LOOKAHEAD_TICKS = 4;
    private static final int MAX_ROUTE_LOOKAHEAD_CHUNKS = 10;
    private static final int MAX_NEW_ROUTE_TICKETS_PER_LEVEL_TICK = 2;
    private static final int MAX_IMMEDIATE_ROUTE_CHUNKS = 256;

    private static final Map<ServerLevel, IdentityHashMap<AbstractCannonProjectile, FlightPlan>> PLANS =
            new IdentityHashMap<>();
    private static final Map<ServerLevel, Long2IntOpenHashMap> REFERENCES =
            new IdentityHashMap<>();
    private static final Map<ServerLevel, RouteTicketBudget> ROUTE_BUDGETS =
            new IdentityHashMap<>();

    private LowAltitudeTrajectoryChunkLoader() {
    }

    /** O(1): no trajectory simulation is allowed in the projectile tick. */
    public static void track(AbstractCannonProjectile projectile, ServerLevel level) {
        if (!HighAltitudeProjectileTransit.shouldActivate(projectile)
                || projectile.isRemoved()) return;
        PLANS.computeIfAbsent(level, ignored -> new IdentityHashMap<>())
                .computeIfAbsent(projectile, ignored -> new FlightPlan(projectile));
    }

    /**
     * Requests only the chunks immediately in front of a low-flying shell.
     * The caller must hold the projectile in place while this returns false;
     * otherwise an unloaded terrain chunk would be indistinguishable from air.
     */
    public static boolean prepareImmediateFlightCorridor(
            AbstractCannonProjectile projectile,
            ServerLevel level,
            Vec3 movement
    ) {
        if (!HighAltitudeProjectileTransit.isActive(projectile)) return true;
        IdentityHashMap<AbstractCannonProjectile, FlightPlan> levelPlans =
                PLANS.computeIfAbsent(level, ignored -> new IdentityHashMap<>());
        FlightPlan plan = levelPlans.computeIfAbsent(
                projectile, ignored -> new FlightPlan(projectile));
        return plan.prepareImmediateFlightCorridor(projectile, level, movement);
    }

    public static void onLevelTickPost(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        long diagnosticStart = PerformanceDiagnostics.startTiming();
        int ticketBudget = MAX_NEW_TICKETS_PER_LEVEL_TICK;
        int predictionBudget = MAX_PREDICTION_STEPS_PER_LEVEL_TICK;
        int trackedInLevel = 0;
        try {
            IdentityHashMap<AbstractCannonProjectile, FlightPlan> levelPlans = PLANS.get(level);
            if (levelPlans == null || levelPlans.isEmpty()) return;
            int predictingInLevel = 0;
            for (Map.Entry<AbstractCannonProjectile, FlightPlan> entry : levelPlans.entrySet()) {
                if (!entry.getKey().isRemoved()) {
                    trackedInLevel++;
                    if (!entry.getValue().predictionComplete
                            || !entry.getValue().adaptiveTerminalAttempted) predictingInLevel++;
                }
            }
            int predictionAllowance = predictingInLevel == 0 ? 0 : Math.max(1, Math.min(
                    MAX_PREDICTION_STEPS_PER_PROJECTILE,
                    MAX_PREDICTION_STEPS_PER_LEVEL_TICK / predictingInLevel
            ));

            Iterator<Map.Entry<AbstractCannonProjectile, FlightPlan>> iterator =
                    levelPlans.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<AbstractCannonProjectile, FlightPlan> entry = iterator.next();
                AbstractCannonProjectile projectile = entry.getKey();
                FlightPlan plan = entry.getValue();
                if (projectile.isRemoved()) {
                    plan.releaseAll(level);
                    iterator.remove();
                    continue;
                }

                plan.queueProgrammedTarget(projectile, level);
                if (predictionBudget > 0) {
                    int allowance = Math.min(predictionAllowance, predictionBudget);
                    // Terminal correction shares the same budget as launch-time
                    // prediction. No hidden 1024-step loop per arriving shell.
                    int used = plan.queueAdaptiveTerminalWarmup(projectile, level, allowance);
                    used += plan.advanceImpactPrediction(projectile, level, allowance - used);
                    predictionBudget -= used;
                }
                plan.queueTerminalWarmupIfReady(level);
                if (ticketBudget > 0) {
                    ticketBudget -= plan.acquireOneTerminalChunk(
                            projectile, level, ticketBudget);
                }
            }
            if (levelPlans.isEmpty()) PLANS.remove(level);
        } finally {
            PerformanceDiagnostics.recordPlanner(
                    PerformanceDiagnostics.elapsedSince(diagnosticStart),
                    trackedInLevel,
                    MAX_PREDICTION_STEPS_PER_LEVEL_TICK - predictionBudget,
                    MAX_NEW_TICKETS_PER_LEVEL_TICK - ticketBudget);
        }
    }

    public static void release(AbstractCannonProjectile projectile) {
        if (projectile.level() instanceof ServerLevel level) {
            IdentityHashMap<AbstractCannonProjectile, FlightPlan> levelPlans = PLANS.get(level);
            FlightPlan plan = levelPlans == null ? null : levelPlans.remove(projectile);
            if (plan != null) plan.releaseAll(level);
            if (levelPlans != null && levelPlans.isEmpty()) PLANS.remove(level);
        }
    }

    public static void clearRuntimeState() {
        for (Map.Entry<ServerLevel, IdentityHashMap<AbstractCannonProjectile, FlightPlan>> levelEntry
                : PLANS.entrySet()) {
            ServerLevel level = levelEntry.getKey();
            for (FlightPlan plan : levelEntry.getValue().values()) {
                plan.releaseAll(level);
            }
        }
        PLANS.clear();
        REFERENCES.clear();
        ROUTE_BUDGETS.clear();
    }

    private static void acquire(ServerLevel level, long packedChunk) {
        Long2IntOpenHashMap references = REFERENCES.computeIfAbsent(
                level, ignored -> new Long2IntOpenHashMap());
        int previous = references.get(packedChunk);
        references.put(packedChunk, previous + 1);
        if (previous == 0) {
            CBCRealisticBallistics.setProjectileChunkTicket(level, packedChunk, true);
        }
    }

    private static void release(ServerLevel level, long packedChunk) {
        Long2IntOpenHashMap references = REFERENCES.get(level);
        if (references == null) return;
        int previous = references.get(packedChunk);
        if (previous <= 1) {
            references.remove(packedChunk);
            CBCRealisticBallistics.setProjectileChunkTicket(level, packedChunk, false);
            if (references.isEmpty()) REFERENCES.remove(level);
        } else {
            references.put(packedChunk, previous - 1);
        }
    }

    private static boolean isAlreadyAcquired(ServerLevel level, long packedChunk) {
        Long2IntOpenHashMap references = REFERENCES.get(level);
        return references != null && references.get(packedChunk) > 0;
    }

    private static boolean consumeRouteTicketBudget(ServerLevel level) {
        RouteTicketBudget budget = ROUTE_BUDGETS.computeIfAbsent(
                level, ignored -> new RouteTicketBudget());
        long gameTime = level.getGameTime();
        if (budget.gameTime != gameTime) {
            budget.gameTime = gameTime;
            budget.remaining = MAX_NEW_ROUTE_TICKETS_PER_LEVEL_TICK;
        }
        if (budget.remaining <= 0) return false;
        budget.remaining--;
        return true;
    }

    private static final class RouteTicketBudget {
        private long gameTime = Long.MIN_VALUE;
        private int remaining;
    }

    private static final class FlightPlan {
        private final LongArrayFIFOQueue pendingTerminal = new LongArrayFIFOQueue();
        private final LongOpenHashSet queuedTerminal = new LongOpenHashSet();
        private final LongOpenHashSet heldTerminal = new LongOpenHashSet();
        private final LongOpenHashSet heldRoute = new LongOpenHashSet();
        private final LongOpenHashSet desiredRoute = new LongOpenHashSet();
        private final LongArrayList routeOrder = new LongArrayList(MAX_ROUTE_LOOKAHEAD_CHUNKS);
        private Vec3 predictedPosition;
        private Vec3 predictedVelocity;
        private double impactPlaneY;
        private int predictedTicks;
        private final LongArrayList predictedLowTail =
                new LongArrayList(MAX_ACTIVE_CHUNKS_PER_PROJECTILE);
        private boolean predictionComplete;
        private boolean warmupQueued;
        private boolean adaptiveTerminalAttempted;
        private Vec3 adaptivePosition;
        private Vec3 adaptiveVelocity;
        private int adaptiveSteps;
        private final LongArrayList adaptiveTail = new LongArrayList(MAX_ACTIVE_CHUNKS_PER_PROJECTILE);
        private long terminalCenter = Long.MIN_VALUE;
        private long programmedTargetChunk = Long.MIN_VALUE;
        private boolean programmedTargetAvailable;
        private int lastProgrammedTargetCheckTick = Integer.MIN_VALUE;
        private int terminalWarmupStartTick = Integer.MAX_VALUE;

        private boolean prepareImmediateFlightCorridor(
                AbstractCannonProjectile projectile,
                ServerLevel level,
                Vec3 movement
        ) {
            double lowest = projectile.getBoundingBox().minY + Math.min(0.0, movement.y);
            if (lowest >= level.getMaxBuildHeight()) {
                clearRouteTickets(level);
                return true;
            }

            Vec3 from = projectile.position();
            Vec3 immediateTo = from.add(movement);
            this.desiredRoute.clear();
            this.routeOrder.clear();
            boolean immediateCovered = traceChunksOrdered(
                    level, from, immediateTo, this.routeOrder, this.desiredRoute,
                    MAX_IMMEDIATE_ROUTE_CHUNKS);
            int immediateCount = this.routeOrder.size();

            if (immediateCovered) {
                Vec3 lookaheadTo = from.add(movement.scale(ROUTE_LOOKAHEAD_TICKS));
                traceChunksOrdered(level, from, lookaheadTo,
                        this.routeOrder, this.desiredRoute,
                        Math.max(immediateCount, MAX_ROUTE_LOOKAHEAD_CHUNKS));
            }

            LongIterator heldIterator = this.heldRoute.iterator();
            while (heldIterator.hasNext()) {
                long packed = heldIterator.nextLong();
                if (this.desiredRoute.contains(packed)) continue;
                heldIterator.remove();
                LowAltitudeTrajectoryChunkLoader.release(level, packed);
            }

            for (int index = 0; index < this.routeOrder.size(); index++) {
                long packed = this.routeOrder.getLong(index);
                int chunkX = ChunkPos.getX(packed);
                int chunkZ = ChunkPos.getZ(packed);
                if (level.getChunkSource().getChunkNow(chunkX, chunkZ) != null
                        || this.heldRoute.contains(packed)) continue;
                boolean shared = LowAltitudeTrajectoryChunkLoader.isAlreadyAcquired(level, packed);
                if (!shared && !LowAltitudeTrajectoryChunkLoader.consumeRouteTicketBudget(level)) {
                    continue;
                }
                LowAltitudeTrajectoryChunkLoader.acquire(level, packed);
                this.heldRoute.add(packed);
            }

            if (!immediateCovered) return false;
            for (int index = 0; index < immediateCount; index++) {
                long packed = this.routeOrder.getLong(index);
                if (level.getChunkSource().getChunkNow(
                        ChunkPos.getX(packed), ChunkPos.getZ(packed)) == null) return false;
            }
            return true;
        }

        private FlightPlan(AbstractCannonProjectile projectile) {
            this.predictedPosition = projectile.position();
            this.predictedVelocity = projectile.getDeltaMovement();
            this.impactPlaneY = this.predictedPosition.y
                    - Math.max(4.0, projectile.getBbHeight());
            if (!finite(this.predictedPosition) || !finite(this.predictedVelocity)) {
                this.predictionComplete = true;
            }
        }

        private int advanceImpactPrediction(
                AbstractCannonProjectile projectile,
                ServerLevel level,
                int allowance
        ) {
            int consumed = 0;
            while (consumed < allowance && !this.predictionComplete) {
                Vec3 position = this.predictedPosition;
                Vec3 velocity = this.predictedVelocity;
                Vec3 acceleration = RealisticFlightModel.predictForces(
                        projectile, position, velocity);
                Vec3 nextVelocity = velocity.add(acceleration);
                // Match AbstractCannonProjectile.tick exactly: CBC advances by
                // v + 0.5a and only then stores v + a for the next tick.
                Vec3 nextPosition = position.add(velocity).add(acceleration.scale(0.5));
                this.predictedTicks++;
                consumed++;

                if (!finite(nextPosition) || !finite(nextVelocity)
                        || !Level.isInSpawnableBounds(BlockPos.containing(nextPosition))) {
                    this.predictionComplete = true;
                    break;
                }
                if (Math.min(position.y, nextPosition.y) <= COLLISION_CEILING_Y) {
                    traceChunksToTail(level, position, nextPosition, this.predictedLowTail);
                }
                if (this.predictedTicks > 3 && nextVelocity.y < 0.0
                        && position.y > this.impactPlaneY
                        && nextPosition.y <= this.impactPlaneY) {
                    this.predictionComplete = true;
                } else if (this.predictedTicks >= MAX_PREDICTION_TICKS
                        || (this.predictedTicks > 20
                        && nextPosition.y < level.getMinBuildHeight() - 64.0
                        && nextVelocity.y < 0.0)) {
                    this.predictionComplete = true;
                }

                this.predictedPosition = nextPosition;
                this.predictedVelocity = nextVelocity;
            }
            return consumed;
        }

        /**
         * A correction module carries the authoritative world-space target in
         * the projectile itself. Keeping that one exact chunk resident makes
         * direct APFSDS reach and collide with an unloaded target without
         * restoring the expensive moving route window.
         */
        private void queueProgrammedTarget(
                AbstractCannonProjectile projectile,
                ServerLevel level
        ) {
            // Module data is immutable for ordinary rounds and retargeting a
            // guided round does not need a 20 Hz NBT tree walk. Poll rapidly
            // during spawn, then at 4 Hz with a module or 1 Hz without one.
            int interval = projectile.tickCount <= 10
                    ? 1 : (this.programmedTargetAvailable ? 5 : 20);
            if (this.lastProgrammedTargetCheckTick != Integer.MIN_VALUE
                    && projectile.tickCount - this.lastProgrammedTargetCheckTick < interval) {
                return;
            }
            this.lastProgrammedTargetCheckTick = projectile.tickCount;
            CompoundTag persistent = projectile.getPersistentData();
            if (!persistent.contains(CORRECTION_FLIGHT_ROOT, Tag.TAG_COMPOUND)) return;
            CompoundTag correction = persistent.getCompound(CORRECTION_FLIGHT_ROOT);
            if (!correction.getBoolean("HasTarget")) return;
            double targetX = correction.getDouble("TargetX");
            double targetY = correction.getDouble("TargetY");
            double targetZ = correction.getDouble("TargetZ");
            if (!Double.isFinite(targetX) || !Double.isFinite(targetY)
                    || !Double.isFinite(targetZ)) return;

            int chunkX = blockToChunk(targetX);
            int chunkZ = blockToChunk(targetZ);
            if (!validChunk(level, chunkX, chunkZ)) return;
            long packed = ChunkPos.asLong(chunkX, chunkZ);
            this.programmedTargetAvailable = true;
            // Guidance supplies an authoritative elevation. Do not keep the
            // old launch-relative synthetic plane, which could make long
            // uphill/downhill solutions warm a terminal area at the wrong Y.
            this.impactPlaneY = targetY;
            if (packed == this.programmedTargetChunk
                    && (this.queuedTerminal.contains(packed)
                    || this.heldTerminal.contains(packed))) return;

            clearTerminalTickets(level);
            this.programmedTargetChunk = packed;
            this.terminalCenter = packed;
            this.terminalWarmupStartTick = 0;
            this.predictionComplete = true;
            this.warmupQueued = true;
            this.adaptiveTerminalAttempted = true;
            this.predictedLowTail.clear();
            this.adaptiveTail.clear();
            enqueueTerminal(level, chunkX, chunkZ);
        }

        private void queueTerminalWarmupIfReady(ServerLevel level) {
            if (!this.predictionComplete || this.warmupQueued) return;
            this.warmupQueued = true;
            // A short, flat solution only reaches the synthetic plane four
            // blocks below the muzzle; it is not a real impact prediction.
            // Warming its 3x3 area made APFSDS generate and later save chunks
            // which the projectile merely flew past. Direct-fire rounds use
            // loaded-only terrain collision and never create terrain tickets.
            if (this.predictedTicks <= TERMINAL_WARMUP_LEAD_TICKS) {
                this.adaptiveTerminalAttempted = true;
                this.predictedLowTail.clear();
                return;
            }
            this.terminalWarmupStartTick = Math.max(
                    0, this.predictedTicks - TERMINAL_WARMUP_LEAD_TICKS);
            enqueueTerminalArea(level, this.predictedLowTail);
            this.predictedLowTail.clear();
        }

        /**
         * The launch-time prediction intentionally avoids terrain and biome
         * loads, so very long shots can accumulate a small terminal error.
         * Once per flight, while descending below 512 m, cheaply recompute only
         * the remaining path from the projectile's authoritative live state.
         * No chunk is queried during this math-only pass.
         */
        private int queueAdaptiveTerminalWarmup(
                AbstractCannonProjectile projectile, ServerLevel level, int allowance
        ) {
            // Finish and classify the cheap launch-time solution first. This
            // prevents the adaptive pass from queuing the same synthetic
            // direct-fire endpoint before it is recognized as non-terminal.
            if (this.programmedTargetAvailable || !this.predictionComplete
                    || this.adaptiveTerminalAttempted
                    || projectile.isRemoved() || allowance <= 0) return 0;
            if (this.adaptivePosition == null) {
                Vec3 position = projectile.position();
                Vec3 velocity = projectile.getDeltaMovement();
                if (!finite(position) || !finite(velocity)
                        || position.y > ADAPTIVE_TERMINAL_REPLAN_Y || velocity.y >= 0.0) return 0;
                this.adaptivePosition = position;
                this.adaptiveVelocity = velocity;
            }
            int consumed = 0;
            boolean finished = this.adaptivePosition.y <= this.impactPlaneY;
            while (consumed < allowance && !finished) {
                Vec3 acceleration = RealisticFlightModel.predictForces(
                        projectile, this.adaptivePosition, this.adaptiveVelocity);
                Vec3 nextVelocity = this.adaptiveVelocity.add(acceleration);
                Vec3 nextPosition = this.adaptivePosition.add(this.adaptiveVelocity)
                        .add(acceleration.scale(0.5));
                consumed++;
                this.adaptiveSteps++;
                if (!finite(nextPosition) || !finite(nextVelocity)
                        || !Level.isInSpawnableBounds(BlockPos.containing(nextPosition))) {
                    finished = true;
                    break;
                }
                if (Math.min(this.adaptivePosition.y, nextPosition.y) <= COLLISION_CEILING_Y) {
                    traceChunksToTail(level, this.adaptivePosition, nextPosition, this.adaptiveTail);
                }
                finished = nextPosition.y <= this.impactPlaneY
                        || this.adaptiveSteps >= MAX_ADAPTIVE_TERMINAL_STEPS;
                this.adaptivePosition = nextPosition;
                this.adaptiveVelocity = nextVelocity;
            }
            if (!finished) return consumed;
            this.adaptiveTerminalAttempted = true;
            if (this.adaptiveTail.isEmpty()) {
                addTailChunk(level, blockToChunk(this.adaptivePosition.x),
                        blockToChunk(this.adaptivePosition.z), this.adaptiveTail);
            }
            if (!this.adaptiveTail.isEmpty()) {
                long correctedCenter = this.adaptiveTail.getLong(this.adaptiveTail.size() - 1);
                if (this.terminalCenter != correctedCenter) {
                    clearTerminalTickets(level);
                    this.warmupQueued = true;
                    enqueueTerminalArea(level, this.adaptiveTail);
                }
                this.terminalWarmupStartTick = Math.min(
                        this.terminalWarmupStartTick, projectile.tickCount);
            }
            this.adaptiveTail.clear();
            return consumed;
        }

        private void enqueueTerminalArea(ServerLevel level, LongArrayList lowTail) {
            if (lowTail.isEmpty()) return;
            ChunkPos impact = new ChunkPos(lowTail.getLong(lowTail.size() - 1));
            this.terminalCenter = impact.toLong();

            // Collision correctness comes first: load the actual impact chunk
            // before any neighbor instead of reaching it fifth in a 3x3 loop.
            enqueueTerminal(level, impact.x, impact.z);
            for (int radius = 1; radius <= TERMINAL_NEIGHBOR_RADIUS; radius++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dx = -radius; dx <= radius; dx++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                        enqueueTerminal(level, impact.x + dx, impact.z + dz);
                    }
                }
            }

            // One remaining slot normally keeps the final approach ready.
            for (int index = lowTail.size() - 2;
                 index >= 0
                         && this.queuedTerminal.size()
                         < MAX_ACTIVE_CHUNKS_PER_PROJECTILE;
                 index--) {
                ChunkPos chunk = new ChunkPos(lowTail.getLong(index));
                enqueueTerminal(level, chunk.x, chunk.z);
            }
        }

        private void clearTerminalTickets(ServerLevel level) {
            for (long packed : this.heldTerminal) {
                LowAltitudeTrajectoryChunkLoader.release(level, packed);
            }
            this.heldTerminal.clear();
            this.pendingTerminal.clear();
            this.queuedTerminal.clear();
            this.terminalCenter = Long.MIN_VALUE;
        }

        private void enqueueTerminal(ServerLevel level, int chunkX, int chunkZ) {
            if (this.queuedTerminal.size() >= MAX_ACTIVE_CHUNKS_PER_PROJECTILE
                    || !validChunk(level, chunkX, chunkZ)) return;
            long packed = ChunkPos.asLong(chunkX, chunkZ);
            if (this.queuedTerminal.add(packed)) this.pendingTerminal.enqueue(packed);
        }

        /**
         * Starts at most one terminal ticket per server tick and keeps it until
         * impact/removal. This spreads generation work while guaranteeing that
         * CBC's explosion and fluid queries do not materialize a 3x3 area in
         * the impact tick itself.
         */
        private int acquireOneTerminalChunk(
                AbstractCannonProjectile projectile,
                ServerLevel level,
                int budget
        ) {
            if (budget <= 0 || projectile.tickCount < this.terminalWarmupStartTick
                    || this.pendingTerminal.isEmpty()) return 0;
            long packed = this.pendingTerminal.dequeueLong();
            if (!this.heldTerminal.add(packed)) return 0;
            ChunkPos chunk = new ChunkPos(packed);
            boolean alreadyResident = level.getChunkSource().getChunkNow(
                    chunk.x, chunk.z) != null;
            boolean sharedTicket = LowAltitudeTrajectoryChunkLoader.isAlreadyAcquired(
                    level, packed);
            LowAltitudeTrajectoryChunkLoader.acquire(level, packed);
            return alreadyResident || sharedTicket ? 0 : 1;
        }

        private void releaseAll(ServerLevel level) {
            clearTerminalTickets(level);
            clearRouteTickets(level);
        }

        private void clearRouteTickets(ServerLevel level) {
            for (long packed : this.heldRoute) {
                LowAltitudeTrajectoryChunkLoader.release(level, packed);
            }
            this.heldRoute.clear();
            this.desiredRoute.clear();
            this.routeOrder.clear();
        }
    }

    /** Keeps only the final ten low-altitude chunks of the predicted path. */
    private static void traceChunksToTail(
            ServerLevel level,
            Vec3 from,
            Vec3 to,
            LongArrayList tail
    ) {
        int chunkX = blockToChunk(from.x);
        int chunkZ = blockToChunk(from.z);
        int endChunkX = blockToChunk(to.x);
        int endChunkZ = blockToChunk(to.z);
        addTailChunk(level, chunkX, chunkZ, tail);

        double dx = to.x - from.x;
        double dz = to.z - from.z;
        int stepX = Double.compare(dx, 0.0);
        int stepZ = Double.compare(dz, 0.0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        double boundaryX = stepX > 0 ? (chunkX + 1) * 16.0 : chunkX * 16.0;
        double boundaryZ = stepZ > 0 ? (chunkZ + 1) * 16.0 : chunkZ * 16.0;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (boundaryX - from.x) / dx;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (boundaryZ - from.z) / dz;
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
            if (!addTailChunk(level, chunkX, chunkZ, tail)) break;
        }
    }

    /** Ordered DDA used by the rolling collision-correctness window. */
    private static boolean traceChunksOrdered(
            ServerLevel level,
            Vec3 from,
            Vec3 to,
            LongArrayList order,
            LongOpenHashSet unique,
            int maximum
    ) {
        int chunkX = blockToChunk(from.x);
        int chunkZ = blockToChunk(from.z);
        int endChunkX = blockToChunk(to.x);
        int endChunkZ = blockToChunk(to.z);
        if (!addOrderedChunk(level, chunkX, chunkZ, order, unique, maximum)) return false;

        double dx = to.x - from.x;
        double dz = to.z - from.z;
        int stepX = Double.compare(dx, 0.0);
        int stepZ = Double.compare(dz, 0.0);
        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dx);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 16.0 / Math.abs(dz);
        double boundaryX = stepX > 0 ? (chunkX + 1) * 16.0 : chunkX * 16.0;
        double boundaryZ = stepZ > 0 ? (chunkZ + 1) * 16.0 : chunkZ * 16.0;
        double tMaxX = stepX == 0 ? Double.POSITIVE_INFINITY : (boundaryX - from.x) / dx;
        double tMaxZ = stepZ == 0 ? Double.POSITIVE_INFINITY : (boundaryZ - from.z) / dz;
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
            if (!addOrderedChunk(level, chunkX, chunkZ, order, unique, maximum)) return false;
        }
        return true;
    }

    private static boolean addOrderedChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            LongArrayList order,
            LongOpenHashSet unique,
            int maximum
    ) {
        if (!validChunk(level, chunkX, chunkZ)) return false;
        long packed = ChunkPos.asLong(chunkX, chunkZ);
        if (unique.contains(packed)) return true;
        if (order.size() >= maximum) return false;
        unique.add(packed);
        order.add(packed);
        return true;
    }

    private static boolean addTailChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            LongArrayList tail
    ) {
        if (!validChunk(level, chunkX, chunkZ)) return false;
        long packed = ChunkPos.asLong(chunkX, chunkZ);
        int previous = tail.indexOf(packed);
        if (previous >= 0) tail.removeLong(previous);
        tail.add(packed);
        if (tail.size() > MAX_ACTIVE_CHUNKS_PER_PROJECTILE) tail.removeLong(0);
        return true;
    }

    private static boolean validChunk(ServerLevel level, int chunkX, int chunkZ) {
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        BlockPos center = new BlockPos(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ());
        return Level.isInSpawnableBounds(center)
                && level.getWorldBorder().isWithinBounds(chunk);
    }

    private static int blockToChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x)
                && Double.isFinite(vector.y)
                && Double.isFinite(vector.z);
    }
}
