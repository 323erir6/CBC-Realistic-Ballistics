package dev.cbcrealisticballistics;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.minecraft.server.level.ServerLevel;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import dev.cbcrealisticballistics.access.ProjectileChunkTicketAccess;
import dev.cbcrealisticballistics.access.HighAltitudeTransitAccess;
import dev.cbcrealisticballistics.access.ImpactVelocityAccess;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

@Mod(CBCRealisticBallistics.MOD_ID)
public final class CBCRealisticBallistics {
    public static final String MOD_ID = "cbc_realistic_ballistics";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final TicketType<Long> PROJECTILE_CHUNK_TICKET = TicketType.create(
            MOD_ID + ":predicted_low_altitude_flight",
            Long::compareTo
    );
    public static final TicketType<Long> NUCLEAR_SCORCH_TICKET = TicketType.create(
            MOD_ID + ":nuclear_scorch",
            Long::compareTo
    );
    public static final TicketType<Long> NUCLEAR_TERRAIN_TICKET = TicketType.create(
            MOD_ID + ":nuclear_terrain",
            Long::compareTo
    );

    public CBCRealisticBallistics(IEventBus modBus, ModContainer container, Dist dist) {
        // SERVER configs are synchronized to connected NeoForge clients. That
        // keeps CBC's client prediction and the authoritative server path equal.
        container.registerConfig(ModConfig.Type.SERVER, BallisticsConfig.SPEC,
                "cbc-realistic-ballistics-server.toml");
        NeoForge.EVENT_BUS.addListener(PerformanceDiagnostics::onServerTickPre);
        NeoForge.EVENT_BUS.addListener(PerformanceDiagnostics::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NuclearVisualTracker::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NuclearScorchChunkSupport::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NuclearBlastManager::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NuclearAftermathManager::onServerTickPost);
        NeoForge.EVENT_BUS.addListener(NuclearAftermathManager::onChunkDataLoad);
        NeoForge.EVENT_BUS.addListener(NuclearAftermathManager::onChunkDataSave);
        NeoForge.EVENT_BUS.addListener(NuclearAftermathManager::onChunkLoad);
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onEntityJoinLevel);
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(HighAltitudeProjectileTransit::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(LowAltitudeTrajectoryChunkLoader::onLevelTickPost);
        NeoForge.EVENT_BUS.addListener(CbcAtCompatibility::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(CbcAtCompatibility::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onServerStopped);
        modBus.addListener(NuclearVisualNetwork::register);
        if (dist == Dist.CLIENT) {
            CBCRealisticBallisticsClient.registerConfigScreen(container);
        }
    }

    /**
     * Register at entity creation rather than waiting for CBC's first tick.
     * This also covers addon projectiles which override tick unusually and
     * prevents a fast shell from reaching an unloaded boundary unarmed.
     */
    private static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel nuclearLevel) {
            NuclearVisualTracker.register(event.getEntity(), nuclearLevel);
        }
        if (!(event.getLevel() instanceof ServerLevel level)
                || !(event.getEntity() instanceof AbstractCannonProjectile projectile)) {
            return;
        }
        if (ProjectileExclusions.isCbcAtFixRocket(projectile)) return;
        CbcAtSableRocketLaunchFix.finishSpawn(projectile);
        double configuredVelocityMps = RealisticFlightModel
                .configuredMilitaryMuzzleVelocity(projectile);
        if (configuredVelocityMps > 0.0 && projectile instanceof ImpactVelocityAccess access) {
            access.cbcRealisticBallistics$setConfiguredLaunchSpeed(configuredVelocityMps / 20.0);
        }
        if (!HighAltitudeProjectileTransit.shouldActivate(projectile)) return;
        if (projectile instanceof HighAltitudeTransitAccess access) {
            access.cbcRealisticBallistics$activateHighAltitudeTransit();
        }
        HighAltitudeProjectileTransit.register(projectile, level);
        LowAltitudeTrajectoryChunkLoader.track(projectile, level);
    }

    private static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel nuclearLevel) {
            NuclearVisualTracker.unregister(event.getEntity(), nuclearLevel);
        }
        if (event.getLevel() instanceof ServerLevel level
                && event.getEntity() instanceof AbstractCannonProjectile projectile) {
            HighAltitudeProjectileTransit.unregister(projectile, level);
        }
        if (event.getEntity() instanceof ProjectileChunkTicketAccess access) {
            access.cbcRealisticBallistics$releaseChunkTickets();
        }
    }

    public static void setProjectileChunkTicket(
            net.minecraft.server.level.ServerLevel level,
            long packedChunk,
            boolean add
    ) {
        long diagnosticStart = PerformanceDiagnostics.startTiming();
        try {
            ChunkPos chunk = new ChunkPos(packedChunk);
            if (add) {
                // Radius zero requests FULL data only for the actual predicted
                // trajectory chunk. It does not entity-tick a surrounding area.
                level.getChunkSource().addRegionTicket(
                        PROJECTILE_CHUNK_TICKET, chunk, 0, packedChunk, false);
            } else {
                level.getChunkSource().removeRegionTicket(
                        PROJECTILE_CHUNK_TICKET, chunk, 0, packedChunk, false);
            }
        } finally {
            PerformanceDiagnostics.recordTicketOperation(
                    PerformanceDiagnostics.elapsedSince(diagnosticStart), add);
        }
    }

    private static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        HighAltitudeProjectileTransit.clearRuntimeState();
        LowAltitudeTrajectoryChunkLoader.clearRuntimeState();
        RealisticFlightModel.clearRuntimeCaches();
        CbcAtCompatibility.reset();
        NuclearVisualTracker.clearRuntimeState();
        NuclearScorchChunkSupport.clearRuntimeState();
        NuclearBlastManager.clearRuntimeState();
        NuclearAftermathManager.clearRuntimeState();
    }
}
