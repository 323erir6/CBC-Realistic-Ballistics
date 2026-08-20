from pathlib import Path

ROOT = Path('.')

def replace(path, old, new, count=1):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    if old not in text:
        raise SystemExit(f'Anchor not found in {path}: {old[:120]!r}')
    text = text.replace(old, new, count)
    p.write_text(text, encoding='utf-8')

# Version / compile dependencies
replace('gradle.properties', 'mod_version=1.3.3', 'mod_version=1.4.0')
replace('build.gradle.kts',
'''dependencies {
    compileOnly(files("../_deps_CreateBigCannons/createbigcannons-5.11.7+mc.1.21.1.jar"))
    // BaseConfigScreen is supplied at runtime by Create's bundled Ponder library.
    compileOnly(files("../_deps_CreateBigCannons/ponder-neoforge-1.0.82+mc1.21.1.jar"))
}''',
'''dependencies {
    compileOnly(files("../_deps_CreateBigCannons/createbigcannons-5.11.7+mc.1.21.1.jar"))
    // BaseConfigScreen is supplied at runtime by Create's bundled Ponder library.
    compileOnly(files("../_deps_CreateBigCannons/ponder-neoforge-1.0.82+mc1.21.1.jar"))
    // Optional compile-time integration. The main mod still starts without Sable.
    compileOnly(files("../_deps_sable_2_0_5/sable-neoforge-1.21.1-2.0.5.jar"))
    compileOnly(files("../_deps_sable/META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar"))
}''')

replace('src/main/resources/META-INF/neoforge.mods.toml',
'''[[dependencies.cbc_realistic_ballistics]]
modId="createbigcannons"
type="required"
versionRange="[5.11.7,5.12)"
ordering="AFTER"
side="BOTH"
''',
'''[[dependencies.cbc_realistic_ballistics]]
modId="createbigcannons"
type="required"
versionRange="[5.11.7,5.12)"
ordering="AFTER"
side="BOTH"

[[dependencies.cbc_realistic_ballistics]]
modId="sable"
type="optional"
versionRange="[2.0.5,2.0.6)"
ordering="AFTER"
side="BOTH"
''')

# Above 350 m, never request/generate terrain chunks.
replace('src/main/java/ua/ivan/cbcrealisticballistics/ProjectileChunkLoader.java',
'''        if (!finite(start) || !finite(velocity)) {
            release(level, ticketedChunks);
            return;
        }

        double horizontalSpeed = velocity.horizontalDistance();''',
'''        if (!finite(start) || !finite(velocity)) {
            release(level, ticketedChunks);
            return;
        }

        // High-altitude flight is simulated outside Minecraft's chunk/entity tick system.
        // Never hold, load, or generate terrain chunks while the projectile is above 350 m.
        if (start.y > HighAltitudeProjectileManager.BYPASS_ALTITUDE) {
            release(level, ticketedChunks);
            return;
        }

        double horizontalSpeed = velocity.horizontalDistance();''')

# Register the virtual-flight server ticker and clear it on shutdown.
replace('src/main/java/ua/ivan/cbcrealisticballistics/CBCRealisticBallistics.java',
'''        NeoForge.EVENT_BUS.addListener(CbcAtCompatibility::onServerStarted);
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onServerStopped);''',
'''        NeoForge.EVENT_BUS.addListener(CbcAtCompatibility::onServerStarted);
        NeoForge.EVENT_BUS.addListener(HighAltitudeProjectileManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(CBCRealisticBallistics::onServerStopped);''')
replace('src/main/java/ua/ivan/cbcrealisticballistics/CBCRealisticBallistics.java',
'''        ProjectileChunkLoader.clearRuntimeState();
        CbcAtCompatibility.reset();''',
'''        ProjectileChunkLoader.clearRuntimeState();
        HighAltitudeProjectileManager.clearRuntimeState();
        CbcAtCompatibility.reset();''')

# Add high-altitude force integration that never queries blocks, fluids, chunks, or biomes.
replace('src/main/java/ua/ivan/cbcrealisticballistics/RealisticFlightModel.java',
'''        return physicalNextVelocity.subtract(velocity);
    }

    private static boolean affects(AbstractCannonProjectile projectile) {''',
'''        return physicalNextVelocity.subtract(velocity);
    }

    /**
     * Realistic force calculation for virtual high-altitude flight. This path is deliberately
     * chunk-independent: it does not read blocks, fluids, chunks or biomes at the projectile's
     * current coordinates. The biome base temperature is captured once while the projectile is
     * still in an already-loaded chunk when it enters virtual flight.
     */
    public static Vec3 highAltitudeForces(AbstractCannonProjectile projectile, Vec3 position,
                                          Vec3 velocity, float biomeBaseTemperature) {
        if (velocity.lengthSqr() < EPSILON) return Vec3.ZERO;

        Level level = projectile.level();
        double gravityTick = projectile.isNoGravity() ? 0.0
                : BallisticsConfig.GRAVITY_METRES_PER_SECOND_SQUARED.get()
                / TICK_SQUARED_PER_SECOND_SQUARED;

        Vec3 wind = windVelocity(level, position);
        Vec3 relativeAirVelocity = velocity.subtract(wind);
        double relativeSpeed = relativeAirVelocity.length();

        Vec3 aerodynamic = Vec3.ZERO;
        if (relativeSpeed >= EPSILON) {
            double temperatureC = 15.0 + (biomeBaseTemperature - 0.8) * 20.0
                    - Math.max(0.0, position.y - BallisticsConfig.SEA_LEVEL_Y.get()) * 0.0065;
            double density = airDensity(position.y, temperatureC);
            double speedOfSound = Math.max(250.0, 331.3 + 0.606 * temperatureC);
            double mach = relativeSpeed * TICKS_PER_SECOND / speedOfSound;
            double dragCoefficient = baseDragCoefficient(projectile) * machMultiplier(mach);
            double diameter = projectileDiameter(projectile);
            double area = Math.PI * diameter * diameter * 0.25;
            double referenceMass = Math.max(0.25, projectile.getProjectileMass());
            double relativeMass = Math.sqrt(referenceMass / 2.0);
            double massKg = Math.max(0.1,
                    BallisticsConfig.PROJECTILE_DENSITY_KG_M3.get()
                            * area
                            * diameter
                            * BallisticsConfig.PROJECTILE_LENGTH_CALIBERS.get()
                            * BallisticsConfig.PROJECTILE_SOLID_FRACTION.get()
                            * relativeMass);
            double dragAcceleration = 0.5 * density * dragCoefficient * area
                    / massKg * relativeSpeed * relativeSpeed;
            dragAcceleration = Math.min(dragAcceleration, relativeSpeed * 0.25);
            aerodynamic = relativeAirVelocity.normalize().scale(-dragAcceleration);
        }

        Vec3 acceleration = new Vec3(0.0, -gravityTick, 0.0).add(aerodynamic);
        if (BallisticsConfig.ENABLE_CORIOLIS.get()) {
            acceleration = acceleration.add(coriolisAcceleration(velocity));
        }
        if (BallisticsConfig.ENABLE_SPIN_DRIFT.get()) {
            acceleration = acceleration.add(spinDrift(projectile, velocity, gravityTick));
        }
        return finite(acceleration) ? acceleration : Vec3.ZERO;
    }

    private static boolean affects(AbstractCannonProjectile projectile) {''')

# Mixin: virtualize after a normal tick reaches >350 and suppress CBC/RPL native chunk forcing.
path = ROOT / 'src/main/java/ua/ivan/cbcrealisticballistics/mixin/AbstractCannonProjectileMixin.java'
text = path.read_text(encoding='utf-8')
text = text.replace('import org.spongepowered.asm.mixin.injection.Inject;\n',
                    'import org.spongepowered.asm.mixin.injection.Inject;\nimport org.spongepowered.asm.mixin.injection.Redirect;\n', 1)
text = text.replace('import ua.ivan.cbcrealisticballistics.ProjectileChunkLoader;\n',
                    'import ua.ivan.cbcrealisticballistics.ProjectileChunkLoader;\nimport ua.ivan.cbcrealisticballistics.HighAltitudeProjectileManager;\n', 1)
old = '''        if (this.cbcRealisticBallistics$energySpeed < 0.0
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
'''
new = '''        if (this.cbcRealisticBallistics$energySpeed >= 0.0
                && this.cbcRealisticBallistics$tickStartDirection.lengthSqr() >= 1.0E-12
                && !projectile.isInGround() && !projectile.isRemoved()) {
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

        if (!projectile.isRemoved() && !projectile.isInGround()) {
            HighAltitudeProjectileManager.maybeVirtualize(projectile);
        }
    }

    /**
     * CBC/RPL has its own current-chunk forcing. This mod owns projectile chunk policy instead:
     * below 350 m ProjectileChunkLoader handles it, above 350 m virtual flight uses no terrain tickets.
     */
    @Redirect(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lrbasamoyai/ritchiesprojectilelib/RitchiesProjectileLib;queueForceLoad(Lnet/minecraft/server/level/ServerLevel;II)V",
                    remap = false
            ),
            remap = false
    )
    private void cbcRealisticBallistics$suppressNativeChunkForceLoad(ServerLevel level, int chunkX, int chunkZ) {
        // Intentionally empty.
    }
'''
if old not in text:
    raise SystemExit('Mixin tail anchor not found')
text = text.replace(old, new, 1)
path.write_text(text, encoding='utf-8')

# New virtual-flight manager.
manager = r'''package ua.ivan.cbcrealisticballistics;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import ua.ivan.cbcrealisticballistics.access.ProjectileChunkTicketAccess;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Simulates CBC projectiles above 350 m without keeping terrain chunks loaded.
 *
 * While virtual, ordinary block/fluid collision is intentionally absent. The state advances once per
 * server tick using the same realistic aerodynamic model, but with no chunk/biome/block reads. The
 * projectile is materialized again only when it descends to 350 m or less, or when its swept segment
 * intersects an already-active entity or Sable construction.
 */
public final class HighAltitudeProjectileManager {
    public static final double BYPASS_ALTITUDE = 350.0;

    private static final Map<MinecraftServer, List<VirtualProjectile>> ACTIVE =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Set<UUID> ACTIVE_IDS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private HighAltitudeProjectileManager() {
    }

    public static void maybeVirtualize(AbstractCannonProjectile projectile) {
        if (!(projectile.level() instanceof ServerLevel level)
                || projectile.isRemoved()
                || projectile.isInGround()
                || projectile.getY() <= BYPASS_ALTITUDE
                || !finite(projectile.position())
                || !finite(projectile.getDeltaMovement())
                || !ACTIVE_IDS.add(projectile.getUUID())) {
            return;
        }

        CompoundTag snapshot = new CompoundTag();
        if (!projectile.save(snapshot)) {
            ACTIVE_IDS.remove(projectile.getUUID());
            return;
        }

        float biomeBaseTemperature = level.getBiome(projectile.blockPosition())
                .value().getBaseTemperature();

        if (projectile instanceof ProjectileChunkTicketAccess access) {
            access.cbcRealisticBallistics$releaseChunkTickets();
        }

        VirtualProjectile virtual = new VirtualProjectile(
                level,
                projectile.getType(),
                snapshot,
                projectile,
                projectile.position(),
                projectile.getDeltaMovement(),
                projectile.tickCount,
                biomeBaseTemperature,
                level.getGameTime()
        );
        ACTIVE.computeIfAbsent(level.getServer(), ignored -> new ArrayList<>()).add(virtual);

        // DISCARD is intentional: unlike unloading to a chunk, it does not leave a terrain-chunk copy
        // which could later duplicate the projectile when that chunk is loaded again.
        projectile.discard();
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        List<VirtualProjectile> projectiles = ACTIVE.get(server);
        if (projectiles == null || projectiles.isEmpty()) return;

        Iterator<VirtualProjectile> iterator = projectiles.iterator();
        while (iterator.hasNext()) {
            VirtualProjectile state = iterator.next();
            if (state.level.getServer() != server) {
                remove(iterator, state);
                continue;
            }

            // The real projectile already completed the tick in which it entered virtual flight.
            if (state.createdGameTime == state.level.getGameTime()) continue;

            if (!advance(state)) {
                remove(iterator, state);
            }
        }
        if (projectiles.isEmpty()) ACTIVE.remove(server);
    }

    public static void clearRuntimeState() {
        ACTIVE.clear();
        ACTIVE_IDS.clear();
    }

    private static boolean advance(VirtualProjectile state) {
        AbstractCannonProjectile template = state.template;
        template.tickCount = state.tickCount;
        template.setPos(state.position.x, state.position.y, state.position.z);
        template.setDeltaMovement(state.velocity);

        Vec3 acceleration = RealisticFlightModel.highAltitudeForces(
                template, state.position, state.velocity, state.biomeBaseTemperature
        );
        if (!finite(acceleration)) return false;

        Vec3 next = state.position.add(state.velocity).add(acceleration.scale(0.5));
        Vec3 nextVelocity = state.velocity.add(acceleration);
        if (!finite(next) || !finite(nextVelocity) || !insideHardWorldBounds(next)) return false;

        state.tickCount++;

        // Below the bypass altitude, normal CBC collision and the ordinary corridor loader resume.
        if (next.y <= BYPASS_ALTITUDE) {
            loadLandingChunk(state.level, next);
            return !materialize(state, next, nextVelocity);
        }

        Vec3 entityIntersection = firstEntityIntersection(state, state.position, next);
        Vec3 sableIntersection = null;
        if (ModList.get().isLoaded("sable")) {
            sableIntersection = SableHighAltitudeCollision.firstIntersection(
                    state.level, state.position, next, collisionRadius(state.template)
            );
        }

        Vec3 intersection = nearer(state.position, entityIntersection, sableIntersection);
        if (intersection != null) {
            Vec3 direction = next.subtract(state.position);
            double length = direction.length();
            Vec3 spawn = length > 1.0E-9
                    ? intersection.subtract(direction.scale(Math.min(0.25, length) / length))
                    : state.position;
            return !materialize(state, spawn, nextVelocity);
        }

        state.position = next;
        state.velocity = nextVelocity;
        return true;
    }

    private static Vec3 firstEntityIntersection(VirtualProjectile state, Vec3 start, Vec3 end) {
        double radius = collisionRadius(state.template) + 0.25;
        AABB swept = new AABB(
                Math.min(start.x, end.x) - radius,
                Math.min(start.y, end.y) - radius,
                Math.min(start.z, end.z) - radius,
                Math.max(start.x, end.x) + radius,
                Math.max(start.y, end.y) + radius,
                Math.max(start.z, end.z) + radius
        );

        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Entity entity : state.level.getEntities(null, swept, entity ->
                !entity.isRemoved()
                        && !(entity instanceof Projectile)
                        && entity.isPickable())) {
            AABB target = entity.getBoundingBox().inflate(radius);
            Optional<Vec3> hit = target.clip(start, end);
            if (hit.isEmpty()) continue;
            double distance = hit.get().distanceToSqr(start);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = hit.get();
            }
        }
        return best;
    }

    private static boolean materialize(VirtualProjectile state, Vec3 position, Vec3 velocity) {
        Entity fresh = state.type.create(state.level);
        if (!(fresh instanceof AbstractCannonProjectile projectile)) return false;

        projectile.load(state.snapshot.copy());
        projectile.setPos(position.x, position.y, position.z);
        projectile.setDeltaMovement(velocity);
        projectile.tickCount = state.tickCount;

        ACTIVE_IDS.remove(projectile.getUUID());
        if (!state.level.addFreshEntity(projectile)) {
            ACTIVE_IDS.add(projectile.getUUID());
            return false;
        }
        return true;
    }

    private static void loadLandingChunk(ServerLevel level, Vec3 position) {
        int chunkX = ((int) Math.floor(position.x)) >> 4;
        int chunkZ = ((int) Math.floor(position.z)) >> 4;
        // This is deliberately only reached at or below 350 m.
        level.getChunkSource().getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
    }

    private static double collisionRadius(AbstractCannonProjectile projectile) {
        return Math.max(0.25, Math.max(projectile.getBbWidth(), projectile.getBbHeight()) * 0.5);
    }

    private static Vec3 nearer(Vec3 start, Vec3 a, Vec3 b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.distanceToSqr(start) <= b.distanceToSqr(start) ? a : b;
    }

    private static boolean insideHardWorldBounds(Vec3 position) {
        return net.minecraft.world.level.Level.isInSpawnableBounds(BlockPos.containing(position));
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }

    private static void remove(Iterator<VirtualProjectile> iterator, VirtualProjectile state) {
        ACTIVE_IDS.remove(state.uuid());
        iterator.remove();
    }

    private static final class VirtualProjectile {
        private final ServerLevel level;
        private final EntityType<?> type;
        private final CompoundTag snapshot;
        private final AbstractCannonProjectile template;
        private Vec3 position;
        private Vec3 velocity;
        private int tickCount;
        private final float biomeBaseTemperature;
        private final long createdGameTime;

        private VirtualProjectile(ServerLevel level, EntityType<?> type, CompoundTag snapshot,
                                  AbstractCannonProjectile template, Vec3 position, Vec3 velocity,
                                  int tickCount, float biomeBaseTemperature, long createdGameTime) {
            this.level = level;
            this.type = type;
            this.snapshot = snapshot;
            this.template = template;
            this.position = position;
            this.velocity = velocity;
            this.tickCount = tickCount;
            this.biomeBaseTemperature = biomeBaseTemperature;
            this.createdGameTime = createdGameTime;
        }

        private UUID uuid() {
            return template.getUUID();
        }
    }
}
'''
(ROOT / 'src/main/java/ua/ivan/cbcrealisticballistics/HighAltitudeProjectileManager.java').write_text(manager, encoding='utf-8')

sable = r'''package ua.ivan.cbcrealisticballistics;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/** Loaded only when Sable is present. */
final class SableHighAltitudeCollision {
    private SableHighAltitudeCollision() {
    }

    static Vec3 firstIntersection(ServerLevel level, Vec3 start, Vec3 end, double radius) {
        BoundingBox3d swept = new BoundingBox3d(
                Math.min(start.x, end.x) - radius,
                Math.min(start.y, end.y) - radius,
                Math.min(start.z, end.z) - radius,
                Math.max(start.x, end.x) + radius,
                Math.max(start.y, end.y) + radius,
                Math.max(start.z, end.z) + radius
        );

        Vec3 best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, swept)) {
            if (subLevel.isRemoved()) continue;
            BoundingBox3dc bounds = subLevel.boundingBox();
            AABB box = new AABB(
                    bounds.minX() - radius,
                    bounds.minY() - radius,
                    bounds.minZ() - radius,
                    bounds.maxX() + radius,
                    bounds.maxY() + radius,
                    bounds.maxZ() + radius
            );
            Optional<Vec3> hit = box.clip(start, end);
            if (hit.isEmpty()) continue;
            double distance = hit.get().distanceToSqr(start);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = hit.get();
            }
        }
        return best;
    }
}
'''
(ROOT / 'src/main/java/ua/ivan/cbcrealisticballistics/SableHighAltitudeCollision.java').write_text(sable, encoding='utf-8')

print('High-altitude virtual-flight patch applied')
