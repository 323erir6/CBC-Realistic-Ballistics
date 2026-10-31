package dev.cbcrealisticballistics.mixin.sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.HoldingSubLevel;
import dev.ryanhcode.sable.sublevel.storage.holding.GlobalSavedSubLevelPointer;
import dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.mixinterface.toast.SableToastableServer;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.util.UUID;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevelHoldingChunkMap.class, remap = false)
public abstract class SubLevelHoldingChunkMapMixin {
    @Shadow @Final private ServerSubLevelContainer container;
    @Shadow @Final private Object2ObjectMap<UUID, HoldingSubLevel> allHoldingSubLevels;

    @Unique
    private boolean cbcRealisticBallistics$suppressCurrentFailureToast;

    @Unique
    private boolean cbcRealisticBallistics$currentLoadDeferred;

    @Unique
    private static final Set<UUID> cbcRealisticBallistics$reportedPlotConflicts =
            ConcurrentHashMap.newKeySet();

    @Unique
    private static final Map<UUID, Long> cbcRealisticBallistics$retryAfterMillis =
            new ConcurrentHashMap<>();

    @Inject(method = "loadHoldingSubLevel", at = @At("HEAD"), cancellable = true)
    private void cbcRealisticBallistics$skipDuplicateOrConflictingLoad(
            HoldingSubLevel holdingSubLevel, CallbackInfo callback) {
        SubLevelData data = holdingSubLevel.data();
        this.cbcRealisticBallistics$currentLoadDeferred = false;
        long now = System.currentTimeMillis();
        if (now < cbcRealisticBallistics$retryAfterMillis.getOrDefault(data.uuid(), 0L)) {
            this.cbcRealisticBallistics$currentLoadDeferred = true;
            callback.cancel();
            return;
        }
        SubLevel existingById = this.container.getSubLevel(data.uuid());
        if (existingById != null) {
            if (existingById instanceof ServerSubLevel existingServerLevel) {
                GlobalSavedSubLevelPointer activePointer = existingServerLevel.getLastSerializationPointer();
                if (activePointer == null && holdingSubLevel.pointer() != null) {
                    existingServerLevel.setLastSerializationPointer(holdingSubLevel.pointer());
                }
            }
            // Keep the saved pointer intact. It may be the active body's only
            // valid persistence pointer and must be reconciled by Sable itself.
            this.allHoldingSubLevels.remove(data.uuid());
            callback.cancel();
            return;
        }

        CompoundTag plotTag = data.fullTag().getCompound("plot");
        int plotX = plotTag.getInt("plot_x");
        int plotZ = plotTag.getInt("plot_z");
        SubLevel existingByPlot = this.container.getSubLevel(plotX, plotZ);
        if (existingByPlot != null) {
            // A plot collision is not proof that either stored construction is
            // stale. Skip this attempt without deleting either pointer; this
            // prevents a load stall while keeping both records recoverable.
            if (cbcRealisticBallistics$reportedPlotConflicts.add(data.uuid())) {
                Sable.LOGGER.warn(
                        "Deferred Sable sub-level load at plot {}, {} because the plot is occupied; "
                                + "the saved construction remains queued for a later retry.",
                        plotX, plotZ);
            }
            cbcRealisticBallistics$retryAfterMillis.put(data.uuid(), now + 5_000L);
            this.cbcRealisticBallistics$currentLoadDeferred = true;
            // Do not remove the holding entry. Plot occupancy can be temporary
            // while another sub-level is still unloading; dropping it here
            // made the deferred construction disappear for the whole session.
            callback.cancel();
        }
    }

    @WrapOperation(
            method = "loadHoldingSubLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelSerializer;fullyLoad(Lnet/minecraft/server/level/ServerLevel;Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;)Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"
            )
    )
    private ServerSubLevel cbcRealisticBallistics$quarantineFailedLoad(
            ServerLevel level, SubLevelData data, Operation<ServerSubLevel> original) {
        ServerSubLevel loaded = original.call(level, data);
        if (loaded == null) {
            // Suppress only the intrusive client toast. Sable keeps the saved
            // pointer, and its log still records the exact read failure.
            this.cbcRealisticBallistics$suppressCurrentFailureToast = true;
            cbcRealisticBallistics$retryAfterMillis.put(data.uuid(),
                    System.currentTimeMillis() + 5_000L);
        }
        return loaded;
    }

    @WrapOperation(
            method = "loadHoldingSubLevel",
            at = @At(
                    value = "INVOKE",
                    target = "Ldev/ryanhcode/sable/mixinterface/toast/SableToastableServer;sable$reportSubLevelLoadFailure(Ldev/ryanhcode/sable/sublevel/storage/holding/GlobalSavedSubLevelPointer;)V"
            )
    )
    private void cbcRealisticBallistics$suppressRecoveredFailureToast(
            SableToastableServer server, GlobalSavedSubLevelPointer pointer, Operation<Void> original) {
        if (!this.cbcRealisticBallistics$suppressCurrentFailureToast) {
            original.call(server, pointer);
        }
    }

    @Inject(method = "loadHoldingSubLevel", at = @At("RETURN"))
    private void cbcRealisticBallistics$finishLoadAttempt(
            HoldingSubLevel holdingSubLevel, CallbackInfo callback) {
        if (this.cbcRealisticBallistics$suppressCurrentFailureToast) {
            // Sable removes the holding entry even when fullyLoad returns null.
            // Put the exact record back so a temporary deserialization or plot
            // ordering failure cannot permanently lose the construction.
            this.allHoldingSubLevels.put(
                    holdingSubLevel.data().uuid(), holdingSubLevel);
        } else if (!this.cbcRealisticBallistics$currentLoadDeferred) {
            cbcRealisticBallistics$reportedPlotConflicts.remove(
                    holdingSubLevel.data().uuid());
            cbcRealisticBallistics$retryAfterMillis.remove(
                    holdingSubLevel.data().uuid());
        }
        this.cbcRealisticBallistics$suppressCurrentFailureToast = false;
        this.cbcRealisticBallistics$currentLoadDeferred = false;
    }
}
