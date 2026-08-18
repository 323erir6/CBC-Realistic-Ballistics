package ua.ivan.cbcrealisticballistics;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;

/** Keeps the current and imminent flight corridor of every CBC projectile entity-ticking. */
public final class ProjectileChunkLoader {
    private static final double MIN_FORWARD_MARGIN_BLOCKS = 32.0;
    private static final double LOOKAHEAD_TICKS = 2.0;
    private static final Map<ServerLevel, Long2IntOpenHashMap> CHUNK_REFERENCES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    private ProjectileChunkLoader() {
    }

    public static void update(
            AbstractCannonProjectile projectile,
            ServerLevel level,
            LongSet ticketedChunks
    ) {
        Vec3 start = projectile.position();
        Vec3 velocity = projectile.getDeltaMovement();
        if (!finite(start) || !finite(velocity)) {
            release(level, ticketedChunks);
            return;
        }

        double horizontalSpeed = velocity.horizontalDistance();
        double lookaheadScale = LOOKAHEAD_TICKS;
        if (horizontalSpeed > 1.0E-9) {
            lookaheadScale += MIN_FORWARD_MARGIN_BLOCKS / horizontalSpeed;
        }
        Vec3 end = start.add(velocity.scale(lookaheadScale));

        LongSet wantedChunks = new LongOpenHashSet();
        traceFlightChunks(level, start.x, start.z, end.x, end.z, wantedChunks);

        LongIterator oldChunks = ticketedChunks.iterator();
        while (oldChunks.hasNext()) {
            long packed = oldChunks.nextLong();
            if (!wantedChunks.contains(packed)) {
                releaseOne(level, packed);
                oldChunks.remove();
            }
        }

        for (long packed : wantedChunks) {
            if (ticketedChunks.contains(packed)) {
                continue;
            }
            acquireOne(level, packed);
            ticketedChunks.add(packed);

            // A ticket keeps the chunk active after it is available. Loading a newly entered part of the
            // corridor here prevents a fast shell from crossing the boundary before the ticket queue catches up.
            ChunkPos chunk = new ChunkPos(packed);
            level.getChunkSource().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, true);
        }
    }

    public static void release(ServerLevel level, LongSet ticketedChunks) {
        for (long packed : ticketedChunks) {
            releaseOne(level, packed);
        }
        ticketedChunks.clear();
    }

    public static void clearRuntimeState() {
        CHUNK_REFERENCES.clear();
    }

    private static void acquireOne(ServerLevel level, long packed) {
        Long2IntOpenHashMap references = CHUNK_REFERENCES.computeIfAbsent(
                level, ignored -> new Long2IntOpenHashMap()
        );
        int previous = references.get(packed);
        references.put(packed, previous + 1);
        if (previous == 0) {
            CBCRealisticBallistics.setProjectileChunkTicket(level, packed, true);
        }
    }

    private static void releaseOne(ServerLevel level, long packed) {
        Long2IntOpenHashMap references = CHUNK_REFERENCES.get(level);
        if (references == null) {
            return;
        }
        int previous = references.get(packed);
        if (previous <= 1) {
            references.remove(packed);
            CBCRealisticBallistics.setProjectileChunkTicket(level, packed, false);
            if (references.isEmpty()) {
                CHUNK_REFERENCES.remove(level);
            }
        } else {
            references.put(packed, previous - 1);
        }
    }

    private static void traceFlightChunks(
            ServerLevel level,
            double startX,
            double startZ,
            double endX,
            double endZ,
            LongSet output
    ) {
        int chunkX = blockToChunk(startX);
        int chunkZ = blockToChunk(startZ);
        int endChunkX = blockToChunk(endX);
        int endChunkZ = blockToChunk(endZ);

        if (!addIfInsideWorld(level, chunkX, chunkZ, output)) {
            return;
        }

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
            if (!addIfInsideWorld(level, chunkX, chunkZ, output)) {
                break;
            }
        }
    }

    private static boolean addIfInsideWorld(ServerLevel level, int chunkX, int chunkZ, LongSet output) {
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        BlockPos center = new BlockPos(chunk.getMiddleBlockX(), 0, chunk.getMiddleBlockZ());
        if (!Level.isInSpawnableBounds(center) || !level.getWorldBorder().isWithinBounds(chunk)) {
            return false;
        }
        output.add(chunk.toLong());
        return true;
    }

    private static int blockToChunk(double coordinate) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }
}
