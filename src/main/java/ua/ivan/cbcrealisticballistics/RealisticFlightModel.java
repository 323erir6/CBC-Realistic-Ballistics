package ua.ivan.cbcrealisticballistics;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;

/**
 * Calculates the physical trajectory velocity. CBC's original energy speed is
 * tracked separately by the mixin. Depending on the server config, impacts use
 * either that original CBC speed or this physical trajectory speed.
 */
public final class RealisticFlightModel {
    private static final double TICKS_PER_SECOND = 20.0;
    private static final double TICK_SQUARED_PER_SECOND_SQUARED = 400.0;
    private static final double SEA_LEVEL_DENSITY = 1.225;
    private static final double EARTH_ANGULAR_SPEED = 7.2921159E-5;
    private static final double EPSILON = 1.0E-12;

    private RealisticFlightModel() {
    }

    public static Vec3 replaceForces(AbstractCannonProjectile projectile, Vec3 position,
                                     Vec3 velocity, Vec3 cbcForces) {
        if (!BallisticsConfig.ENABLED.get() || velocity.lengthSqr() < EPSILON
                || !affects(projectile)) {
            return cbcForces;
        }
        if (!projectile.level().getFluidState(projectile.blockPosition()).isEmpty()) {
            return cbcForces;
        }

        Level level = projectile.level();
        double gravityTick = projectile.isNoGravity() ? 0.0
                : BallisticsConfig.GRAVITY_METRES_PER_SECOND_SQUARED.get()
                / TICK_SQUARED_PER_SECOND_SQUARED;

        Vec3 wind = windVelocity(level, position);
        Vec3 relativeAirVelocity = velocity.subtract(wind);
        Vec3 acceleration = new Vec3(0.0, -gravityTick, 0.0)
                .add(aerodynamicAcceleration(projectile, level, position, relativeAirVelocity));

        if (BallisticsConfig.ENABLE_CORIOLIS.get()) {
            acceleration = acceleration.add(coriolisAcceleration(velocity));
        }
        if (BallisticsConfig.ENABLE_SPIN_DRIFT.get()) {
            acceleration = acceleration.add(spinDrift(projectile, velocity, gravityTick));
        }

        Vec3 physicalNextVelocity = velocity.add(acceleration);
        if (!finite(physicalNextVelocity) || physicalNextVelocity.lengthSqr() < EPSILON) {
            return cbcForces;
        }

        return physicalNextVelocity.subtract(velocity);
    }

    private static boolean affects(AbstractCannonProjectile projectile) {
        if (projectile instanceof AbstractBigCannonProjectile) {
            return BallisticsConfig.AFFECT_BIG_CANNONS.get();
        }
        if (projectile instanceof AbstractAutocannonProjectile) {
            return BallisticsConfig.AFFECT_AUTOCANNONS.get();
        }
        // Addon projectiles which extend CBC's common projectile base also get
        // realistic flight when at least one family is enabled.
        return BallisticsConfig.AFFECT_BIG_CANNONS.get()
                || BallisticsConfig.AFFECT_AUTOCANNONS.get();
    }

    private static Vec3 aerodynamicAcceleration(AbstractCannonProjectile projectile, Level level,
                                                 Vec3 position, Vec3 relativeVelocity) {
        double relativeSpeed = relativeVelocity.length();
        if (relativeSpeed < EPSILON) return Vec3.ZERO;

        double temperatureC = temperatureCelsius(level, position);
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

        // With 1 block = 1 metre and 20 ticks/s, converting v^2 to m/s and
        // acceleration back to blocks/tick^2 cancels the factor of 400.
        double dragAcceleration = 0.5 * density * dragCoefficient * area
                / massKg * relativeSpeed * relativeSpeed;
        dragAcceleration = Math.min(dragAcceleration, relativeSpeed * 0.25);
        return relativeVelocity.normalize().scale(-dragAcceleration);
    }

    private static Vec3 windVelocity(Level level, Vec3 position) {
        if (!BallisticsConfig.ENABLE_WIND.get()) return Vec3.ZERO;

        double baseSpeed = BallisticsConfig.WIND_SPEED_METRES_PER_SECOND.get();
        double gustSpeed = BallisticsConfig.GUST_SPEED_METRES_PER_SECOND.get();

        // Weather is an actual source of wind instead of a multiplier of the
        // configured value. Rain/thunder levels change only with Minecraft's
        // weather; the wind field itself never depends on game time.
        double rain = 0.0;
        double thunder = 0.0;
        if (BallisticsConfig.WEATHER_AFFECTS_WIND.get()) {
            rain = level.getRainLevel(1.0F);
            thunder = level.getThunderLevel(1.0F);
        }
        double effectiveBaseSpeed = baseSpeed
                + BallisticsConfig.RAIN_WIND_BONUS_METRES_PER_SECOND.get() * rain
                + BallisticsConfig.THUNDER_WIND_BONUS_METRES_PER_SECOND.get() * thunder;
        double effectiveGustSpeed = gustSpeed
                + BallisticsConfig.RAIN_GUST_BONUS_METRES_PER_SECOND.get() * rain
                + BallisticsConfig.THUNDER_GUST_BONUS_METRES_PER_SECOND.get() * thunder;
        if (effectiveBaseSpeed <= 0.0 && effectiveGustSpeed <= 0.0) return Vec3.ZERO;

        long seed = windSeed(level);
        double regionSize = BallisticsConfig.WIND_REGION_SIZE_METRES.get();
        double speedNoise = smoothNoise(seed, position.x / regionSize, position.z / regionSize, 0x51EEDL);
        double directionNoise = smoothNoise(seed, position.x / regionSize, position.z / regionSize, 0xD1CE7L);
        double verticalNoise = smoothNoise(seed, position.x / regionSize, position.z / regionSize, 0xA17L);

        double regionalMultiplier = Math.max(0.0, 1.0
                + speedNoise * BallisticsConfig.WIND_SPEED_VARIATION_FRACTION.get());
        double spatialVariation = effectiveGustSpeed * speedNoise;
        double altitude = Math.max(0.0, position.y - BallisticsConfig.SEA_LEVEL_Y.get());
        double maximumAltitudeGain = BallisticsConfig.ALTITUDE_WIND_MAXIMUM_MULTIPLIER.get() - 1.0;
        double altitudeFactor = 1.0 + Math.min(maximumAltitudeGain,
                Math.log1p(altitude / 10.0) * 0.08);
        double horizontalSpeed = Math.max(0.0,
                (effectiveBaseSpeed * regionalMultiplier + spatialVariation) * altitudeFactor)
                / TICKS_PER_SECOND;

        double direction = Math.toRadians(BallisticsConfig.WIND_DIRECTION_DEGREES.get()
                + directionNoise * BallisticsConfig.WIND_DIRECTION_VARIATION_DEGREES.get());
        double verticalTurbulence = effectiveGustSpeed
                * BallisticsConfig.VERTICAL_TURBULENCE_FRACTION.get()
                * verticalNoise / TICKS_PER_SECOND;
        return new Vec3(Math.sin(direction) * horizontalSpeed, verticalTurbulence,
                Math.cos(direction) * horizontalSpeed);
    }

    private static long windSeed(Level level) {
        long worldSeed;
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            worldSeed = serverLevel.getSeed();
        } else {
            // The authoritative path is calculated on the server. A stable
            // dimension fallback keeps client prediction deterministic until
            // the normal precise-motion packets correct it.
            worldSeed = level.dimension().location().toString().hashCode();
        }
        long dimension = level.dimension().location().toString().hashCode();
        return mix64(worldSeed
                ^ BallisticsConfig.WIND_SEED_SALT.get()
                ^ dimension * 0x9E3779B97F4A7C15L);
    }

    private static double smoothNoise(long seed, double x, double z, long channel) {
        long x0 = (long) Math.floor(x);
        long z0 = (long) Math.floor(z);
        double tx = smoothStep(x - x0);
        double tz = smoothStep(z - z0);
        double n00 = latticeNoise(seed, x0, z0, channel);
        double n10 = latticeNoise(seed, x0 + 1, z0, channel);
        double n01 = latticeNoise(seed, x0, z0 + 1, channel);
        double n11 = latticeNoise(seed, x0 + 1, z0 + 1, channel);
        return lerp(lerp(n00, n10, tx), lerp(n01, n11, tx), tz);
    }

    private static double latticeNoise(long seed, long x, long z, long channel) {
        long value = mix64(seed
                ^ mix64(x * 0x632BE59BD9B4E019L)
                ^ mix64(z * 0x9E3779B97F4A7C15L)
                ^ channel);
        return ((value >>> 11) * 0x1.0p-53) * 2.0 - 1.0;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    private static double smoothStep(double value) {
        return value * value * (3.0 - 2.0 * value);
    }

    private static Vec3 coriolisAcceleration(Vec3 velocityTick) {
        double latitude = Math.toRadians(BallisticsConfig.LATITUDE_DEGREES.get());
        // Local axes: +X east, +Y up, +Z south. Omega therefore has north as -Z.
        Vec3 omega = new Vec3(0.0, EARTH_ANGULAR_SPEED * Math.sin(latitude),
                -EARTH_ANGULAR_SPEED * Math.cos(latitude));
        Vec3 velocityMetresPerSecond = velocityTick.scale(TICKS_PER_SECOND);
        return omega.cross(velocityMetresPerSecond).scale(-2.0
                / TICK_SQUARED_PER_SECOND_SQUARED);
    }

    private static Vec3 spinDrift(AbstractCannonProjectile projectile, Vec3 velocity,
                                  double gravityTick) {
        Vec3 horizontal = new Vec3(velocity.x, 0.0, velocity.z);
        if (horizontal.lengthSqr() < EPSILON) return Vec3.ZERO;
        Vec3 right = new Vec3(horizontal.z, 0.0, -horizontal.x).normalize();
        double buildUp = Math.min(1.0, projectile.tickCount / 100.0);
        double acceleration = gravityTick * BallisticsConfig.SPIN_DRIFT_FACTOR.get()
                * buildUp;
        return right.scale(acceleration);
    }

    private static double temperatureCelsius(Level level, Vec3 position) {
        float biomeTemperature = level.getBiome(net.minecraft.core.BlockPos.containing(position))
                .value().getBaseTemperature();
        // Minecraft's common temperate value (0.8) maps to roughly 15 C.
        return 15.0 + (biomeTemperature - 0.8) * 20.0
                - Math.max(0.0, position.y - BallisticsConfig.SEA_LEVEL_Y.get()) * 0.0065;
    }

    private static double airDensity(double y, double temperatureC) {
        double altitude = y - BallisticsConfig.SEA_LEVEL_Y.get();
        double pressureFactor = Math.exp(-altitude / BallisticsConfig.SCALE_HEIGHT_METRES.get());
        double temperatureFactor = 288.15 / Math.max(150.0, temperatureC + 273.15);
        return Math.clamp(SEA_LEVEL_DENSITY * pressureFactor * temperatureFactor, 0.02, 3.0);
    }

    private static double baseDragCoefficient(AbstractCannonProjectile projectile) {
        String name = projectile.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("trafficcone")) return 0.90;
        if (name.contains("mortar") || name.contains("grapeshot")) return 0.47;
        if (name.contains("mediumcannon")) {
            if (name.contains("apfsds") || name.contains("apds")) return 0.18;
            if (name.startsWith("ap") || name.contains("aphe")) return 0.22;
            return 0.25;
        }
        if (name.contains("apshot") || name.contains("solidshot")) return 0.28;
        if (name.contains("apshell")) return 0.22;
        if (name.contains("autocannon") || name.contains("machinegun")) return 0.20;
        if (name.contains("shell")) return 0.25;
        return 0.30;
    }

    private static double projectileDiameter(AbstractCannonProjectile projectile) {
        if (BallisticsConfig.USE_NOMINAL_CALIBER_OVERRIDES.get()) {
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
            String className = projectile.getClass().getSimpleName()
                    .toLowerCase(java.util.Locale.ROOT);
            if (isHeavyAutocannonProjectile(typeId)) {
                return BallisticsConfig.HEAVY_AUTOCANNON_CALIBER_METRES.get();
            }
            if (projectile instanceof AbstractAutocannonProjectile
                    || className.contains("autocannon")
                    || className.contains("machinegun")
                    || isAddonAutocannonProjectile(typeId)) {
                return BallisticsConfig.AUTOCANNON_CALIBER_METRES.get();
            }
            if (isStandardCbcBigCannonProjectile(typeId)) {
                return BallisticsConfig.CBC_BIG_CANNON_CALIBER_METRES.get();
            }
            if (className.contains("mediumcannon")
                    || (typeId != null
                    && typeId.getNamespace().equals("cbcmodernwarfare")
                    && typeId.getPath().contains("mediumshell"))) {
                return BallisticsConfig.MEDIUM_CANNON_CALIBER_METRES.get();
            }
        }
        return Math.max(0.05, Math.min(projectile.getBbWidth(), projectile.getBbHeight()));
    }

    private static boolean isAddonAutocannonProjectile(ResourceLocation typeId) {
        if (typeId == null) return false;
        String namespace = typeId.getNamespace();
        String path = typeId.getPath();
        if (path.contains("autocannon") || path.contains("machine_gun")) return true;
        if (!namespace.equals("cbc_at")) return false;
        return path.equals("apds_projectile")
                || path.equals("apdsfs_projectile")
                || path.equals("he_projectile")
                || path.equals("hei_projectile")
                || path.equals("cluster_projectile");
    }

    private static boolean isHeavyAutocannonProjectile(ResourceLocation typeId) {
        return typeId != null
                && typeId.getNamespace().equals("cbc_at")
                && typeId.getPath().startsWith("ha_")
                && typeId.getPath().endsWith("_projectile");
    }

    private static boolean isStandardCbcBigCannonProjectile(ResourceLocation typeId) {
        if (typeId == null || !typeId.getNamespace().equals("createbigcannons")) return false;
        return switch (typeId.getPath()) {
            case "ap_shell", "ap_shot", "bag_of_grapeshot", "fluid_shell", "he_shell", "shot",
                    "shrapnel_shell", "smoke_shell", "traffic_cone" -> true;
            default -> false;
        };
    }

    private static double machMultiplier(double mach) {
        if (mach < 0.75) return 1.0;
        if (mach < 0.95) return lerp(1.0, 1.65, (mach - 0.75) / 0.20);
        if (mach < 1.10) return lerp(1.65, 2.05, (mach - 0.95) / 0.15);
        if (mach < 1.50) return lerp(2.05, 1.45, (mach - 1.10) / 0.40);
        if (mach < 3.00) return lerp(1.45, 1.10, (mach - 1.50) / 1.50);
        return 1.10;
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * Math.clamp(t, 0.0, 1.0);
    }

    private static boolean finite(Vec3 value) {
        return Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
