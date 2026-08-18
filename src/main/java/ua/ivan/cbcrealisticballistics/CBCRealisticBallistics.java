package ua.ivan.cbcrealisticballistics;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import ua.ivan.cbcrealisticballistics.access.ProjectileChunkTicketAccess;

@Mod(CBCRealisticBallistics.MOD_ID)
public final class CBCRealisticBallistics {
    public static final String MOD_ID = "cbc_realistic_ballistics";
    public static final TicketType<Long> PROJECTILE_CHUNK_TICKET = TicketType.create(
            MOD_ID + ":projectile_flight",
            Long::compareTo
    );

    public CBCRealisticBallistics(IEventBus ignoredModBus, ModContainer container, Dist dist) {
        // SERVER configs are synchronized to connected NeoForge clients. That
        // keeps CBC's client prediction and the authoritative server path equal.
        container.registerConfig(ModConfig.Type.SERVER, BallisticsConfig.SPEC,
                "cbc-realistic-ballistics-server.toml");
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onEntityLeaveLevel);
        NeoForge.EVENT_BUS.addListener(CbcAtCompatibility::onServerAboutToStart);
        NeoForge.EVENT_BUS.addListener(CbcAtCompatibility::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onServerStopped);
        if (dist == Dist.CLIENT) {
            CBCRealisticBallisticsClient.registerConfigScreen(container);
        }
    }

    public static void setProjectileChunkTicket(
            net.minecraft.server.level.ServerLevel level,
            long packedChunk,
            boolean add
    ) {
        ChunkPos chunk = new ChunkPos(packedChunk);
        if (add) {
            level.getChunkSource().addRegionTicket(
                    PROJECTILE_CHUNK_TICKET, chunk, 2, packedChunk, true
            );
        } else {
            level.getChunkSource().removeRegionTicket(
                    PROJECTILE_CHUNK_TICKET, chunk, 2, packedChunk, true
            );
        }
    }

    private static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getEntity() instanceof AbstractCannonProjectile
                && event.getEntity() instanceof ProjectileChunkTicketAccess access) {
            access.cbcRealisticBallistics$releaseChunkTickets();
        }
    }

    private static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        ProjectileChunkLoader.clearRuntimeState();
        CbcAtCompatibility.reset();
    }
}
