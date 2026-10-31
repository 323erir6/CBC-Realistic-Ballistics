package dev.cbcrealisticballistics.mixin;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import dev.cbcrealisticballistics.BallisticsConfig;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;
import dev.cbcrealisticballistics.ProjectileLifetimeController;
import dev.cbcrealisticballistics.LowAltitudeTrajectoryChunkLoader;
import dev.cbcrealisticballistics.PerformanceDiagnostics;
import dev.cbcrealisticballistics.ProjectileExclusions;
import dev.cbcrealisticballistics.RealisticFlightModel;
import dev.cbcrealisticballistics.access.ImpactVelocityAccess;
import dev.cbcrealisticballistics.access.HighAltitudeTransitAccess;
import dev.cbcrealisticballistics.access.ProjectileChunkTicketAccess;
import dev.cbcrealisticballistics.access.CollisionProbeAccess;

@Mixin(AbstractCannonProjectile.class)
public abstract class AbstractCannonProjectileMixin
        implements ImpactVelocityAccess, ProjectileChunkTicketAccess, HighAltitudeTransitAccess,
        CollisionProbeAccess {
    /** 0.01 block/tick = 0.2 m/s: below this a lodged shell is effectively at rest. */
    @Unique
    private static final double cbcRealisticBallistics$stationarySpeedSqr = 1.0E-4;
    @Shadow
    protected abstract Vec3 getForces(Vec3 position, Vec3 velocity);
    @Unique
    private double cbcRealisticBallistics$energySpeed = -1.0;
    @Unique
    private Vec3 cbcRealisticBallistics$tickStartDirection = Vec3.ZERO;
    @Unique
    private Vec3 cbcRealisticBallistics$savedTrajectoryVelocity = Vec3.ZERO;
    @Unique
    private int cbcRealisticBallistics$impactDepth;
    @Unique
    private boolean cbcRealisticBallistics$highAltitudeTransitActive;
    @Unique
    private long cbcRealisticBallistics$lastServerTick = Long.MIN_VALUE;
    @Unique
    private int cbcRealisticBallistics$cachedForceTick = Integer.MIN_VALUE;
    @Unique
    private Vec3 cbcRealisticBallistics$cachedForcePosition = Vec3.ZERO;
    @Unique
    private Vec3 cbcRealisticBallistics$cachedForceVelocity = Vec3.ZERO;
    @Unique
    private Vec3 cbcRealisticBallistics$cachedForces = Vec3.ZERO;
    @Unique
    private boolean cbcRealisticBallistics$emptyBlockCorridor;
    @Unique
    private Vec3 cbcRealisticBallistics$rayFrom;
    @Unique
    private Vec3 cbcRealisticBallistics$rayTo;
    @Unique
    private boolean cbcRealisticBallistics$lifetimeExtended;
    @Unique
    private Vec3 cbcRealisticBallistics$lastSyncedOrientation = Vec3.ZERO;
    @Unique
    private int cbcRealisticBallistics$lastOrientationSyncTick = Integer.MIN_VALUE;
    @Unique
    private long cbcRealisticBallistics$diagnosticTickStart;
    @Unique
    private int cbcRealisticBallistics$collisionProbeTick = Integer.MIN_VALUE;
    @Unique
    private boolean cbcRealisticBallistics$collisionProbeClear;
    @Unique
    private long cbcRealisticBallistics$despawnAtGameTime = Long.MIN_VALUE;
    @Unique
    private long cbcRealisticBallistics$stationarySinceGameTime = Long.MIN_VALUE;
    @Unique
    private boolean cbcRealisticBallistics$transitRegistered;
    @Unique
    private boolean cbcRealisticBallistics$trajectoryPlanRegistered;
    @Unique
    private boolean cbcRealisticBallistics$waitingForTerrain;
    @Unique
    private Vec3 cbcRealisticBallistics$terrainHoldPosition = Vec3.ZERO;
    @Unique
    private Vec3 cbcRealisticBallistics$terrainHoldVelocity = Vec3.ZERO;

    @Override
    public void cbcRealisticBallistics$setConfiguredLaunchSpeed(double speedBlocksPerTick) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        Vec3 velocity = projectile.getDeltaMovement();
        double originalSpeed = velocity.length();
        if (!(originalSpeed > 1.0E-9) || !Double.isFinite(originalSpeed)
                || !(speedBlocksPerTick > 0.0) || !Double.isFinite(speedBlocksPerTick)) {
            return;
        }
        // The mod changes trajectory only. Preserve the addon's original CBC
        // launch speed as the impact-energy reference before applying 800 m/s.
        if (this.cbcRealisticBallistics$energySpeed < 0.0) {
            this.cbcRealisticBallistics$energySpeed = originalSpeed;
        }
        projectile.setDeltaMovement(velocity.scale(speedBlocksPerTick / originalSpeed));
    }

    /**
     * CBC samples the fluid at the projectile's current block while computing
     * drag. Level#getFluidState synchronously obtains the chunk, which stalls
     * the server whenever a virtual-transit shell crosses an absent chunk.
     * An absent chunk cannot contain an observable fluid yet, so model it as
     * air until the bounded terminal/impact loader has made it resident.
     */
    @WrapOperation(
            method = "getDragForce",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getFluidState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/material/FluidState;"
            )
    )
    private FluidState cbcRealisticBallistics$loadedOnlyFluidDrag(
            Level level,
            BlockPos position,
            Operation<FluidState> original
    ) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (level instanceof ServerLevel serverLevel
                && HighAltitudeProjectileTransit.isActive(projectile)
                && serverLevel.getChunkSource().getChunkNow(
                        position.getX() >> 4, position.getZ() >> 4) == null) {
            return Fluids.EMPTY.defaultFluidState();
        }
        return original.call(level, position);
    }

    @Inject(method = "getForces", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$reuseExactForces(Vec3 position, Vec3 velocity,
                                                       CallbackInfoReturnable<Vec3> cir) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (this.cbcRealisticBallistics$impactDepth == 0
                && projectile.tickCount == this.cbcRealisticBallistics$cachedForceTick
                && sameVector(position, this.cbcRealisticBallistics$cachedForcePosition)
                && sameVector(velocity, this.cbcRealisticBallistics$cachedForceVelocity)) {
            cir.setReturnValue(this.cbcRealisticBallistics$cachedForces);
        }
    }

    @Inject(method = "getForces", at = @At("RETURN"), cancellable = true)
    private void cbcRealisticBallistics$replaceForces(Vec3 position, Vec3 velocity,
                                                       CallbackInfoReturnable<Vec3> cir) {
        if (this.cbcRealisticBallistics$impactDepth > 0) return;
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        int tick = projectile.tickCount;
        if (tick == this.cbcRealisticBallistics$cachedForceTick
                && sameVector(position, this.cbcRealisticBallistics$cachedForcePosition)
                && sameVector(velocity, this.cbcRealisticBallistics$cachedForceVelocity)) {
            cir.setReturnValue(this.cbcRealisticBallistics$cachedForces);
            return;
        }

        long diagnosticStart = PerformanceDiagnostics.startTiming();
        Vec3 forces;
        try {
            forces = RealisticFlightModel.replaceForces(
                    projectile, position, velocity, cir.getReturnValue());
        } finally {
            PerformanceDiagnostics.recordForceCalculation(
                    PerformanceDiagnostics.elapsedSince(diagnosticStart));
        }
        this.cbcRealisticBallistics$cachedForceTick = tick;
        this.cbcRealisticBallistics$cachedForcePosition = position;
        this.cbcRealisticBallistics$cachedForceVelocity = velocity;
        this.cbcRealisticBallistics$cachedForces = forces;
        cir.setReturnValue(forces);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$captureReferenceVelocity(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (projectile.level() instanceof ServerLevel serverLevel) {
            long gameTime = serverLevel.getGameTime();
            if (this.cbcRealisticBallistics$despawnAtGameTime == Long.MIN_VALUE) {
                long lifetimeTicks = Math.max(1L, (long) Math.ceil(
                        BallisticsConfig.PROJECTILE_DESPAWN_TIMEOUT_SECONDS.get() * 20.0));
                this.cbcRealisticBallistics$despawnAtGameTime = gameTime + lifetimeTicks;
            }
            if (gameTime >= this.cbcRealisticBallistics$despawnAtGameTime) {
                this.cbcRealisticBallistics$releaseChunkTickets();
                // KILLED is deliberate: programmable-fuze persistence only
                // protects ordinary lifetime DISCARD, never this hard cap.
                projectile.remove(Entity.RemovalReason.KILLED);
                ci.cancel();
                return;
            }

            // Do this before every trajectory-family exclusion: spent APFSDS,
            // 20 mm ammunition, addon projectiles and compatibility rockets all
            // need the same cleanup. An absolute game-time deadline avoids
            // double-counting if another mod invokes projectile tick more than
            // once during one server tick.
            Vec3 motion = projectile.getDeltaMovement();
            double speedSqr = motion.lengthSqr();
            boolean stationary = Double.isFinite(speedSqr)
                    && speedSqr <= cbcRealisticBallistics$stationarySpeedSqr;
            if (stationary) {
                if (this.cbcRealisticBallistics$stationarySinceGameTime == Long.MIN_VALUE) {
                    this.cbcRealisticBallistics$stationarySinceGameTime = gameTime;
                }
                long stationaryTicks = Math.max(1L, (long) Math.ceil(
                        BallisticsConfig.STATIONARY_PROJECTILE_TIMEOUT_SECONDS.get() * 20.0));
                if (gameTime - this.cbcRealisticBallistics$stationarySinceGameTime >= stationaryTicks) {
                    this.cbcRealisticBallistics$releaseChunkTickets();
                    projectile.remove(Entity.RemovalReason.KILLED);
                    ci.cancel();
                    return;
                }
            } else {
                this.cbcRealisticBallistics$stationarySinceGameTime = Long.MIN_VALUE;
            }
        }
        // This safety floor applies to every CBC projectile, including families
        // otherwise excluded from realistic flight/transit compatibility.
        if (HighAltitudeProjectileTransit.discardIfOutsideSimulationBounds(projectile)) {
            ci.cancel();
            return;
        }
        if (ProjectileExclusions.isCbcAtFixRocket(projectile)) return;
        this.cbcRealisticBallistics$diagnosticTickStart =
                projectile.level() instanceof ServerLevel
                        ? PerformanceDiagnostics.startTiming() : 0L;
        if (this.cbcRealisticBallistics$diagnosticTickStart != 0L) {
            PerformanceDiagnostics.markProjectileActivity();
        }
        // Refresh once per second. Addons may write their configured lifetime
        // after launch or after NBT/chunk transitions, so a one-shot write was
        // not sufficient. Cached field metadata and write-only-on-change keep
        // this negligible even for large salvos.
        if (!this.cbcRealisticBallistics$lifetimeExtended
                || projectile.tickCount % 20 == 0) {
            ProjectileLifetimeController.keepAlive(projectile);
            this.cbcRealisticBallistics$lifetimeExtended = true;
        }
        if (projectile.level() instanceof ServerLevel serverLevel && !projectile.isRemoved()) {
            this.cbcRealisticBallistics$lastServerTick = serverLevel.getGameTime();
            if (!this.cbcRealisticBallistics$highAltitudeTransitActive
                    && HighAltitudeProjectileTransit.shouldActivate(projectile)) {
                this.cbcRealisticBallistics$highAltitudeTransitActive = true;
            }
            if (this.cbcRealisticBallistics$highAltitudeTransitActive) {
                if (!this.cbcRealisticBallistics$transitRegistered) {
                    HighAltitudeProjectileTransit.register(projectile, serverLevel);
                    this.cbcRealisticBallistics$transitRegistered = true;
                }
                if (!this.cbcRealisticBallistics$trajectoryPlanRegistered) {
                    LowAltitudeTrajectoryChunkLoader.track(projectile, serverLevel);
                    this.cbcRealisticBallistics$trajectoryPlanRegistered = true;
                }
            }
        }
        Vec3 velocity = projectile.getDeltaMovement();
        this.cbcRealisticBallistics$waitingForTerrain = false;
        this.cbcRealisticBallistics$terrainHoldPosition = projectile.position();
        this.cbcRealisticBallistics$terrainHoldVelocity = velocity;
        double speed = velocity.length();
        if (this.cbcRealisticBallistics$energySpeed < 0.0 && Double.isFinite(speed)) {
            this.cbcRealisticBallistics$energySpeed = speed;
        }
        this.cbcRealisticBallistics$tickStartDirection = speed > 1.0E-9
                ? velocity.scale(1.0 / speed) : Vec3.ZERO;
    }

    @Override
    public Vec3 cbcRealisticBallistics$getCollisionMovement() {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        Vec3 velocity = projectile.getDeltaMovement();
        return velocity.add(getForces(projectile.position(), velocity));
    }

    @WrapMethod(method = "clipAndDamage")
    private void cbcRealisticBallistics$preserveSableAndEntityCollision(Operation<Void> original) {
        long diagnosticStart = PerformanceDiagnostics.startTiming();
        boolean fullCbcPass = true;
        boolean noMain = false;
        boolean emptyRay = false;
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        try {
            if (projectile.level() instanceof ServerLevel
                    && !ProjectileExclusions.isCbcAtFixRocket(projectile)) {
                ServerLevel serverLevel = (ServerLevel) projectile.level();
                Vec3 collisionMovement = this.cbcRealisticBallistics$getCollisionMovement();
                if (HighAltitudeProjectileTransit.isActive(projectile)
                        && !LowAltitudeTrajectoryChunkLoader.prepareImmediateFlightCorridor(
                        projectile, serverLevel, collisionMovement)) {
                    // The CBC tick will still run its cheap bookkeeping, but
                    // the RETURN hook restores position and velocity. This
                    // prevents an unloaded chunk from being treated as air.
                    this.cbcRealisticBallistics$waitingForTerrain = true;
                    fullCbcPass = false;
                    return;
                }
                boolean potential = HighAltitudeProjectileTransit.hasPotentialLoadedCollision(
                        projectile, serverLevel, collisionMovement);
                this.cbcRealisticBallistics$emptyBlockCorridor = !potential;
                this.cbcRealisticBallistics$recordCollisionProbe(projectile.tickCount, !potential);
                fullCbcPass = potential;
                this.cbcRealisticBallistics$rayFrom = projectile.position();
                this.cbcRealisticBallistics$rayTo = projectile.position().add(
                        collisionMovement);
                if (!potential) {
                    HighAltitudeProjectileTransit.beginProvenEmptyRay((ServerLevel) projectile.level(),
                            this.cbcRealisticBallistics$rayFrom, this.cbcRealisticBallistics$rayTo);
                    emptyRay = true;
                }
                // Never let CBC's exact ray synchronously obtain an absent
                // main-world chunk. This matters most for fast direct-fire
                // rounds such as Military Supplement APFSDS: at roughly
                // 40 blocks/tick they can cross several chunks before async
                // generation completes. Sable still performs its transformed
                // collision pass and LevelMixin merges ordinary blocks from
                // every chunk which is already resident (including chunks
                // prepared by LowAltitudeTrajectoryChunkLoader).
                noMain = HighAltitudeProjectileTransit.isActive(projectile)
                        && HighAltitudeProjectileTransit.canRaycastSableWithoutLoadingMainChunks();
                if (noMain) HighAltitudeProjectileTransit.beginNoMainLevelCollision(projectile);
            }
            // Always preserve onClip/fuze callbacks, entity damage, and CBC's
            // impact/penetration bookkeeping, even in proven empty sky.
            original.call();
        } finally {
            this.cbcRealisticBallistics$emptyBlockCorridor = false;
            this.cbcRealisticBallistics$rayFrom = null;
            this.cbcRealisticBallistics$rayTo = null;
            if (noMain) HighAltitudeProjectileTransit.endNoMainLevelCollision();
            if (emptyRay) HighAltitudeProjectileTransit.endProvenEmptyRay();
            PerformanceDiagnostics.recordCollisionPass(
                    PerformanceDiagnostics.elapsedSince(diagnosticStart), fullCbcPass);
        }
    }

    @WrapOperation(
            method = "clipAndDamage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"),
            // An optional compatibility mod may own the invocation instead.
            // If so, retain its original collision behavior.
            require = 0
    )
    private BlockHitResult cbcRealisticBallistics$skipProvenEmptyBlockRay(
            Level level, ClipContext context, Operation<BlockHitResult> original
    ) {
        this.cbcRealisticBallistics$rayFrom = context.getFrom();
        this.cbcRealisticBallistics$rayTo = context.getTo();
        if (!this.cbcRealisticBallistics$emptyBlockCorridor) return original.call(level, context);
        Vec3 to = context.getTo();
        Vec3 direction = context.getFrom().subtract(to);
        return BlockHitResult.miss(to,
                Direction.getNearest(direction.x, direction.y, direction.z),
                BlockPos.containing(to));
    }

    @WrapOperation(
            method = "clipAndDamage",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;"),
            require = 0
    )
    private java.util.List<net.minecraft.world.entity.Entity> cbcRealisticBallistics$narrowEntitySweep(
            Level level, net.minecraft.world.entity.Entity source,
            net.minecraft.world.phys.AABB box,
            Operation<java.util.List<net.minecraft.world.entity.Entity>> original
    ) {
        if (this.cbcRealisticBallistics$rayFrom == null
                || this.cbcRealisticBallistics$rayTo == null
                || ProjectileExclusions.isCbcAtFixRocket((AbstractCannonProjectile) (Object) this)) {
            return original.call(level, source, box);
        }
        return dev.cbcrealisticballistics.ProjectileEntitySweep.query(
                level, source, box, this.cbcRealisticBallistics$rayFrom, this.cbcRealisticBallistics$rayTo);
    }

    @org.spongepowered.asm.mixin.injection.Redirect(
            method = "tick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;hasChunk(II)Z")
    )
    private boolean cbcRealisticBallistics$allowVirtualCommonProjectileTick(
            net.minecraft.world.level.Level level, int chunkX, int chunkZ
    ) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        return level.hasChunk(chunkX, chunkZ) || HighAltitudeProjectileTransit.isActive(projectile);
    }

    @WrapOperation(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lrbasamoyai/ritchiesprojectilelib/RitchiesProjectileLib;queueForceLoad(Lnet/minecraft/server/level/ServerLevel;II)V"
            )
    )
    private void cbcRealisticBallistics$disableCbcHighAltitudeChunkLoading(
            ServerLevel level, int chunkX, int chunkZ, Operation<Void> original
    ) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (ProjectileExclusions.isCbcAtFixRocket(projectile)) {
            original.call(level, chunkX, chunkZ);
            return;
        }
        // Suppressed deliberately for every altitude. At launch the cannon is
        // already inside an entity-ticking area, so adding a second ticket is
        // redundant. Moving that ticket by several chunks every tick at
        // artillery speed caused synchronous chunk-level transitions and was
        // the dominant TPS cost of a salvo. Once high-altitude transit is
        // active, the projectile is advanced without any chunk ticket.
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void cbcRealisticBallistics$advanceReferenceVelocity(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (ProjectileExclusions.isCbcAtFixRocket(projectile)) return;
        if (this.cbcRealisticBallistics$waitingForTerrain) {
            projectile.setPos(this.cbcRealisticBallistics$terrainHoldPosition);
            projectile.setDeltaMovement(this.cbcRealisticBallistics$terrainHoldVelocity);
            this.cbcRealisticBallistics$waitingForTerrain = false;
            return;
        }
        // Catch a fast projectile in the same tick in which it crosses Y=-70,
        // rather than allowing one additional full movement step below it.
        if (HighAltitudeProjectileTransit.discardIfOutsideSimulationBounds(projectile)) {
            this.cbcRealisticBallistics$releaseChunkTickets();
            return;
        }
        if (projectile.isRemoved()) {
            this.cbcRealisticBallistics$releaseChunkTickets();
        }

        // CBC renders the projectile block/model from its separately synced
        // orientation vector. The realistic force replacement changes the
        // flight vector after CBC performs its normal orientation update, so
        // the shell model could keep an old (often downward) direction while
        // the entity continued along the correct trajectory. Always align the
        // visual orientation and the vanilla rotation fields with the final
        // trajectory velocity produced by this tick.
        Vec3 trajectoryVelocity = projectile.getDeltaMovement();
        double trajectorySpeedSqr = trajectoryVelocity.lengthSqr();
        if (!projectile.isInGround()
                && Double.isFinite(trajectorySpeedSqr)
                && trajectorySpeedSqr > 1.0E-12
                && Double.isFinite(trajectoryVelocity.x)
                && Double.isFinite(trajectoryVelocity.y)
                && Double.isFinite(trajectoryVelocity.z)) {
            Vec3 direction = trajectoryVelocity.scale(1.0 / Math.sqrt(trajectorySpeedSqr));
            // SynchedEntityData marks an entry dirty even for tiny ballistic
            // direction changes. Limit network/model updates while retaining
            // smooth interpolation and immediate updates for visible turns.
            double dot = this.cbcRealisticBallistics$lastSyncedOrientation.lengthSqr() > 0.5
                    ? direction.dot(this.cbcRealisticBallistics$lastSyncedOrientation) : -1.0;
            if (dot < 0.9999984769132877
                    || projectile.tickCount - this.cbcRealisticBallistics$lastOrientationSyncTick >= 4) {
                projectile.setOrientation(direction);
                this.cbcRealisticBallistics$lastSyncedOrientation = direction;
                this.cbcRealisticBallistics$lastOrientationSyncTick = projectile.tickCount;
            }
            projectile.setYRot((float) Math.toDegrees(Math.atan2(direction.x, direction.z)));
            projectile.setXRot((float) Math.toDegrees(Math.atan2(
                    direction.y,
                    Math.sqrt(direction.x * direction.x + direction.z * direction.z)
            )));
        }

        if (this.cbcRealisticBallistics$energySpeed < 0.0
                || this.cbcRealisticBallistics$tickStartDirection.lengthSqr() < 1.0E-12
                || projectile.isInGround() || projectile.isRemoved()) {
            return;
        }

        Vec3 referenceVelocity = this.cbcRealisticBallistics$tickStartDirection
                .scale(this.cbcRealisticBallistics$energySpeed);
        this.cbcRealisticBallistics$impactDepth++;
        try {
            projectile.setDeltaMovement(referenceVelocity);
            Vec3 referenceForces = this.getForces(projectile.position(), referenceVelocity);
            double nextSpeed = referenceVelocity.add(referenceForces).length();
            if (Double.isFinite(nextSpeed)) this.cbcRealisticBallistics$energySpeed = nextSpeed;
        } finally {
            projectile.setDeltaMovement(trajectoryVelocity);
            this.cbcRealisticBallistics$impactDepth--;
        }
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void cbcRealisticBallistics$recordProjectileTick(CallbackInfo ci) {
        long started = this.cbcRealisticBallistics$diagnosticTickStart;
        if (started == 0L) return;
        this.cbcRealisticBallistics$diagnosticTickStart = 0L;
        PerformanceDiagnostics.recordProjectileTick(PerformanceDiagnostics.elapsedSince(started));
    }

    @Unique
    private static boolean sameVector(Vec3 first, Vec3 second) {
        return Double.doubleToLongBits(first.x) == Double.doubleToLongBits(second.x)
                && Double.doubleToLongBits(first.y) == Double.doubleToLongBits(second.y)
                && Double.doubleToLongBits(first.z) == Double.doubleToLongBits(second.z);
    }

    @Unique
    private void cbcRealisticBallistics$recordCollisionProbe(int tick, boolean clear) {
        this.cbcRealisticBallistics$collisionProbeTick = tick;
        this.cbcRealisticBallistics$collisionProbeClear = clear;
    }

    @Override
    public boolean cbcRealisticBallistics$wasCollisionProbeClear(int projectileTick) {
        return this.cbcRealisticBallistics$collisionProbeTick == projectileTick
                && this.cbcRealisticBallistics$collisionProbeClear;
    }

    @Override
    public void cbcRealisticBallistics$releaseChunkTickets() {
        LowAltitudeTrajectoryChunkLoader.release(
                (AbstractCannonProjectile) (Object) this);
    }

    @Override
    public void cbcRealisticBallistics$beginImpactVelocity() {
        if (!BallisticsConfig.PRESERVE_ORIGINAL_IMPACT_ENERGY.get()) return;
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (ProjectileExclusions.isCbcAtFixRocket(projectile)) return;
        if (this.cbcRealisticBallistics$impactDepth++ > 0) return;
        this.cbcRealisticBallistics$savedTrajectoryVelocity = projectile.getDeltaMovement();
        double actualSpeed = this.cbcRealisticBallistics$savedTrajectoryVelocity.length();
        if (this.cbcRealisticBallistics$energySpeed < 0.0) {
            this.cbcRealisticBallistics$energySpeed = actualSpeed;
        }
        if (actualSpeed > 1.0E-9 && this.cbcRealisticBallistics$energySpeed >= 0.0) {
            projectile.setDeltaMovement(this.cbcRealisticBallistics$savedTrajectoryVelocity
                    .scale(this.cbcRealisticBallistics$energySpeed / actualSpeed));
        }
    }

    @Override
    public void cbcRealisticBallistics$endImpactVelocity() {
        if (this.cbcRealisticBallistics$impactDepth <= 0) return;
        if (--this.cbcRealisticBallistics$impactDepth == 0) {
            AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
            projectile.setDeltaMovement(this.cbcRealisticBallistics$savedTrajectoryVelocity);
        }
    }

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void cbcRealisticBallistics$beforeEntityImpact(net.minecraft.world.entity.Entity entity,
                                                            rbasamoyai.createbigcannons.munitions.ProjectileContext context,
                                                            CallbackInfoReturnable<Boolean> cir) {
        this.cbcRealisticBallistics$beginImpactVelocity();
    }

    @Inject(method = "onHitEntity", at = @At("RETURN"))
    private void cbcRealisticBallistics$afterEntityImpact(net.minecraft.world.entity.Entity entity,
                                                           rbasamoyai.createbigcannons.munitions.ProjectileContext context,
                                                           CallbackInfoReturnable<Boolean> cir) {
        this.cbcRealisticBallistics$endImpactVelocity();
    }

    @Inject(method = "addAdditionalSaveData", at = @At("TAIL"))
    private void cbcRealisticBallistics$saveEnergySpeed(CompoundTag tag, CallbackInfo ci) {
        if (this.cbcRealisticBallistics$energySpeed >= 0.0) {
            tag.putDouble("CBCRealisticEnergySpeed", this.cbcRealisticBallistics$energySpeed);
        }
        if (this.cbcRealisticBallistics$highAltitudeTransitActive) {
            tag.putBoolean("CBCRealisticHighAltitudeTransit", true);
        }
        if (this.cbcRealisticBallistics$despawnAtGameTime != Long.MIN_VALUE) {
            tag.putLong("CBCRealisticDespawnAtGameTime",
                    this.cbcRealisticBallistics$despawnAtGameTime);
        }
        if (this.cbcRealisticBallistics$stationarySinceGameTime != Long.MIN_VALUE) {
            tag.putLong("CBCRealisticStationarySinceGameTime",
                    this.cbcRealisticBallistics$stationarySinceGameTime);
        }
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void cbcRealisticBallistics$loadEnergySpeed(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("CBCRealisticEnergySpeed")) {
            double value = tag.getDouble("CBCRealisticEnergySpeed");
            if (Double.isFinite(value) && value >= 0.0) {
                this.cbcRealisticBallistics$energySpeed = value;
            }
        }
        this.cbcRealisticBallistics$highAltitudeTransitActive =
                tag.getBoolean("CBCRealisticHighAltitudeTransit");
        if (tag.contains("CBCRealisticDespawnAtGameTime")) {
            this.cbcRealisticBallistics$despawnAtGameTime =
                    tag.getLong("CBCRealisticDespawnAtGameTime");
        }
        if (tag.contains("CBCRealisticStationarySinceGameTime")) {
            this.cbcRealisticBallistics$stationarySinceGameTime =
                    tag.getLong("CBCRealisticStationarySinceGameTime");
        }
    }

    @Override
    public boolean cbcRealisticBallistics$isHighAltitudeTransitActive() {
        return this.cbcRealisticBallistics$highAltitudeTransitActive;
    }

    @Override
    public void cbcRealisticBallistics$activateHighAltitudeTransit() {
        this.cbcRealisticBallistics$highAltitudeTransitActive = true;
    }

    @Override
    public long cbcRealisticBallistics$getLastServerTick() {
        return this.cbcRealisticBallistics$lastServerTick;
    }

    @Override
    public void cbcRealisticBallistics$setLastServerTick(long gameTime) {
        this.cbcRealisticBallistics$lastServerTick = gameTime;
    }
}
