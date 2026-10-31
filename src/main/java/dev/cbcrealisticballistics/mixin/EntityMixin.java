package dev.cbcrealisticballistics.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import dev.cbcrealisticballistics.HighAltitudeProjectileTransit;
import dev.cbcrealisticballistics.ProjectileTransitSavedData;

@Mixin(Entity.class)
public abstract class EntityMixin {
    /**
     * Minecraft 1.21.1 ends every server-side Entity.setPosRaw call with a
     * synchronous Level.getChunk request. The returned chunk is discarded;
     * the call merely forces the destination chunk to exist. At artillery
     * velocity that generated several chunks per shell per tick and was the
     * dominant TPS cost of remote flight.
     *
     * Keep Entity's real coordinates, bounding box and section callback, but
     * do not materialize an absent terrain chunk merely because a virtual CBC
     * shell crossed it. Loaded chunks retain vanilla behavior and therefore
     * exact collision, fuzes and entity tracking around real targets.
     */
    @WrapOperation(
            method = "setPosRaw",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/Level;getChunk(II)Lnet/minecraft/world/level/chunk/LevelChunk;"
            )
    )
    private LevelChunk cbcRealisticBallistics$doNotMaterializeTransitChunk(
            Level level,
            int chunkX,
            int chunkZ,
            Operation<LevelChunk> original
    ) {
        Entity self = (Entity) (Object) this;
        if (level instanceof ServerLevel serverLevel
                && self instanceof AbstractCannonProjectile projectile
                && (HighAltitudeProjectileTransit.isActive(projectile)
                || ProjectileTransitSavedData.isRestoringEntity())
                && serverLevel.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            // setPosRaw immediately discards this return value, so null is
            // safe and prevents the server thread from blocking on IO or
            // world generation. The projectile remains owned by the transit
            // manager until it reaches loaded terrain again.
            return null;
        }
        return original.call(level, chunkX, chunkZ);
    }

    @Inject(method = "isAlwaysTicking", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$keepHighAltitudeProjectileTracked(
            CallbackInfoReturnable<Boolean> cir
    ) {
        Entity self = (Entity) (Object) this;
        if (self instanceof AbstractCannonProjectile projectile
                && HighAltitudeProjectileTransit.isActive(projectile)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "shouldBeSaved", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$doNotUnloadVirtualProjectile(
            CallbackInfoReturnable<Boolean> cir
    ) {
        Entity self = (Entity) (Object) this;
        if (self instanceof AbstractCannonProjectile projectile
                && HighAltitudeProjectileTransit.isActive(projectile)
                && !HighAltitudeProjectileTransit.isCurrentChunkLoaded(projectile)) {
            // A hidden chunk section is periodically offered to vanilla's
            // entity storage. Saving it there would immediately unload the
            // live projectile. The transit manager owns it until it reaches
            // an already loaded section again.
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "baseTick", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$skipWorldQueriesInUnloadedAir(CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self instanceof AbstractCannonProjectile projectile
                && HighAltitudeProjectileTransit.isActive(projectile)
                && !HighAltitudeProjectileTransit.isCurrentChunkLoaded(projectile)) {
            // Entity.baseTick checks portals, fluids, fire and supporting
            // blocks. Those reads may synchronously load a chunk. CBC still
            // advances position, velocity and rotation after this method.
            ci.cancel();
        }
    }
}
