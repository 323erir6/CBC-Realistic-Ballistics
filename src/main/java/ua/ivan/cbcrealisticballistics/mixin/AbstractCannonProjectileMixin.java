package ua.ivan.cbcrealisticballistics.mixin;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import ua.ivan.cbcrealisticballistics.BallisticsConfig;
import ua.ivan.cbcrealisticballistics.ProjectileChunkLoader;
import ua.ivan.cbcrealisticballistics.ProjectileLifetimeController;
import ua.ivan.cbcrealisticballistics.RealisticFlightModel;
import ua.ivan.cbcrealisticballistics.access.ImpactVelocityAccess;
import ua.ivan.cbcrealisticballistics.access.ProjectileChunkTicketAccess;

@Mixin(AbstractCannonProjectile.class)
public abstract class AbstractCannonProjectileMixin
        implements ImpactVelocityAccess, ProjectileChunkTicketAccess {
    @Shadow
    protected abstract Vec3 getForces(Vec3 position, Vec3 velocity);
    @Shadow
    protected int inGroundTime;

    @Unique
    private double cbcRealisticBallistics$energySpeed = -1.0;
    @Unique
    private Vec3 cbcRealisticBallistics$tickStartDirection = Vec3.ZERO;
    @Unique
    private Vec3 cbcRealisticBallistics$savedTrajectoryVelocity = Vec3.ZERO;
    @Unique
    private int cbcRealisticBallistics$impactDepth;
    @Unique
    private final LongSet cbcRealisticBallistics$ticketedChunks = new LongOpenHashSet();
    @Unique
    private ServerLevel cbcRealisticBallistics$ticketLevel;

    @Inject(method = "getForces", at = @At("RETURN"), cancellable = true)
    private void cbcRealisticBallistics$replaceForces(Vec3 position, Vec3 velocity,
                                                       CallbackInfoReturnable<Vec3> cir) {
        if (this.cbcRealisticBallistics$impactDepth > 0) return;
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        cir.setReturnValue(RealisticFlightModel.replaceForces(
                projectile, position, velocity, cir.getReturnValue()));
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void cbcRealisticBallistics$captureReferenceVelocity(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        ProjectileLifetimeController.keepAlive(projectile);
        if (BallisticsConfig.DISABLE_PROJECTILE_DESPAWN.get()) {
            // CBC removes an otherwise valid projectile after 400 ticks in a
            // block. Reset only this ordinary despawn counter; detonation and
            // impact removal flags are intentionally left untouched.
            this.inGroundTime = 0;
        }
        if (projectile.level() instanceof ServerLevel serverLevel && !projectile.isRemoved()) {
            if (this.cbcRealisticBallistics$ticketLevel != null
                    && this.cbcRealisticBallistics$ticketLevel != serverLevel) {
                ProjectileChunkLoader.release(
                        this.cbcRealisticBallistics$ticketLevel,
                        this.cbcRealisticBallistics$ticketedChunks
                );
            }
            this.cbcRealisticBallistics$ticketLevel = serverLevel;
            ProjectileChunkLoader.update(
                    projectile, serverLevel, this.cbcRealisticBallistics$ticketedChunks
            );
        }
        Vec3 velocity = projectile.getDeltaMovement();
        double speed = velocity.length();
        if (this.cbcRealisticBallistics$energySpeed < 0.0 && Double.isFinite(speed)) {
            this.cbcRealisticBallistics$energySpeed = speed;
        }
        this.cbcRealisticBallistics$tickStartDirection = speed > 1.0E-9
                ? velocity.scale(1.0 / speed) : Vec3.ZERO;
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void cbcRealisticBallistics$advanceReferenceVelocity(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (projectile.isRemoved()) {
            this.cbcRealisticBallistics$releaseChunkTickets();
        }
        if (this.cbcRealisticBallistics$energySpeed < 0.0
                || this.cbcRealisticBallistics$tickStartDirection.lengthSqr() < 1.0E-12
                || projectile.isInGround() || projectile.isRemoved()) {
            return;
        }

        Vec3 trajectoryVelocity = projectile.getDeltaMovement();
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

    @Override
    public void cbcRealisticBallistics$releaseChunkTickets() {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (this.cbcRealisticBallistics$ticketLevel != null) {
            ProjectileChunkLoader.release(
                    this.cbcRealisticBallistics$ticketLevel,
                    this.cbcRealisticBallistics$ticketedChunks
            );
            this.cbcRealisticBallistics$ticketLevel = null;
        } else {
            this.cbcRealisticBallistics$ticketedChunks.clear();
        }
    }

    @Override
    public void cbcRealisticBallistics$beginImpactVelocity() {
        if (!BallisticsConfig.PRESERVE_ORIGINAL_IMPACT_ENERGY.get()) return;
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
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
    }

    @Inject(method = "readAdditionalSaveData", at = @At("TAIL"))
    private void cbcRealisticBallistics$loadEnergySpeed(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains("CBCRealisticEnergySpeed")) {
            double value = tag.getDouble("CBCRealisticEnergySpeed");
            if (Double.isFinite(value) && value >= 0.0) {
                this.cbcRealisticBallistics$energySpeed = value;
            }
        }
    }
}
