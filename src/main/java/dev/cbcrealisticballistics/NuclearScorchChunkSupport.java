package dev.cbcrealisticballistics;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class NuclearScorchChunkSupport {
    private static final AtomicLong NEXT_TICKET = new AtomicLong(1L);
    private static final List<HeldArea> HELD_AREAS = new ArrayList<>();

    private NuclearScorchChunkSupport() {
    }

    public static void prepare(
            ServerLevel level,
            double centerX,
            double centerZ,
            int radius,
            int columnsPerTick
    ) {
        if (radius < 2) {
            return;
        }
        long ticketKey = NEXT_TICKET.getAndIncrement();
        List<Long> chunks = intersectingChunks(centerX, centerZ, radius);
        for (long packed : chunks) {
            ChunkPos chunk = new ChunkPos(packed);
            level.getChunkSource().addRegionTicket(
                    CBCRealisticBallistics.NUCLEAR_SCORCH_TICKET,
                    chunk, 0, ticketKey, false
            );
        }

        // The addon starts processing columns in the same tick. Load only the
        // circle-intersecting chunks now so its hasChunk guard cannot cut a
        // wedge out of the thermal footprint.
        for (long packed : chunks) {
            ChunkPos chunk = new ChunkPos(packed);
            level.getChunk(chunk.x, chunk.z);
        }

        long circleColumns = (long) Math.ceil(Math.PI * radius * radius);
        long processingTicks = (circleColumns + Math.max(1, columnsPerTick) - 1)
                / Math.max(1, columnsPerTick);
        long releaseAt = level.getGameTime()
                + Math.max(200L, Math.min(12000L, processingTicks + 100L));
        HELD_AREAS.add(new HeldArea(level, chunks, ticketKey, releaseAt));
    }

    public static void onServerTickPost(ServerTickEvent.Post event) {
        Iterator<HeldArea> iterator = HELD_AREAS.iterator();
        while (iterator.hasNext()) {
            HeldArea area = iterator.next();
            if (area.level.getGameTime() < area.releaseAt
                    && area.level.getServer().isRunning()) {
                continue;
            }
            area.release();
            iterator.remove();
        }
    }

    public static void clearRuntimeState() {
        for (HeldArea area : HELD_AREAS) {
            area.release();
        }
        HELD_AREAS.clear();
    }

    static List<Long> intersectingChunks(double centerX, double centerZ, int radius) {
        int minChunkX = ((int) Math.floor(centerX - radius)) >> 4;
        int maxChunkX = ((int) Math.floor(centerX + radius)) >> 4;
        int minChunkZ = ((int) Math.floor(centerZ - radius)) >> 4;
        int maxChunkZ = ((int) Math.floor(centerZ + radius)) >> 4;
        double radiusSquared = (double) radius * radius;
        List<Long> result = new ArrayList<>();
        for (int chunkX = minChunkX; chunkX <= maxChunkX; ++chunkX) {
            double minX = chunkX * 16.0;
            double maxX = minX + 15.999999;
            double closestX = Math.max(minX, Math.min(centerX, maxX));
            double dx = closestX - centerX;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; ++chunkZ) {
                double minZ = chunkZ * 16.0;
                double maxZ = minZ + 15.999999;
                double closestZ = Math.max(minZ, Math.min(centerZ, maxZ));
                double dz = closestZ - centerZ;
                if (dx * dx + dz * dz <= radiusSquared) {
                    result.add(ChunkPos.asLong(chunkX, chunkZ));
                }
            }
        }
        return result;
    }

    private record HeldArea(
            ServerLevel level,
            List<Long> chunks,
            long ticketKey,
            long releaseAt
    ) {
        void release() {
            for (long packed : chunks) {
                level.getChunkSource().removeRegionTicket(
                        CBCRealisticBallistics.NUCLEAR_SCORCH_TICKET,
                        new ChunkPos(packed), 0, ticketKey, false
                );
            }
        }
    }
}
