package dev.cbcrealisticballistics;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntityType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.SectionPos;
import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.autocannon.AbstractAutocannonProjectile;
import rbasamoyai.createbigcannons.munitions.big_cannon.AbstractBigCannonProjectile;
import java.lang.reflect.Field;

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
    private static final java.util.concurrent.ConcurrentHashMap<EntityType<?>, ProjectileTypeProfile>
            TYPE_PROFILES = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Field> DUAL_CANNON_CONTRAPTION_FIELDS =
            new java.util.HashMap<>();
    private static final java.util.Set<Class<?>> NO_DUAL_CANNON_CONTRAPTION_FIELD =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final java.util.Map<Class<?>, RocketMotorFields> ROCKET_MOTOR_FIELDS =
            new java.util.HashMap<>();
    private static final java.util.Set<Class<?>> NO_ROCKET_MOTOR_FIELDS =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final String MILITARY_CANNON_FAMILY_TAG =
            "cbc_realistic_ballistics:military_cannon_family";
    private static final java.util.concurrent.ConcurrentHashMap<Level, Long> WIND_SEEDS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<Level, LevelRuntime> LEVEL_RUNTIMES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private RealisticFlightModel() {
    }

    public static void clearRuntimeCaches() {
        TYPE_PROFILES.clear();
        DUAL_CANNON_CONTRAPTION_FIELDS.clear();
        NO_DUAL_CANNON_CONTRAPTION_FIELD.clear();
        ROCKET_MOTOR_FIELDS.clear();
        NO_ROCKET_MOTOR_FIELDS.clear();
        WIND_SEEDS.clear();
        LEVEL_RUNTIMES.clear();
    }

    public static Vec3 replaceForces(AbstractCannonProjectile projectile, Vec3 position,
                                     Vec3 velocity, Vec3 cbcForces) {
        Level level = projectile.level();
        LevelRuntime runtime = runtime(level);
        FlightSettings settings = runtime.settings(level);
        if (!settings.enabled || velocity.lengthSqr() < EPSILON
                || !affects(projectile, settings)) {
            return cbcForces;
        }
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
            // Never use Level#getFluidState for a flying server projectile.
            // That convenience method may synchronously request/generate the
            // chunk even when the projectile is otherwise in virtual transit.
            // A direct getChunkNow lookup is strictly non-blocking and keeps
            // exact fluid behaviour whenever the full chunk is already present.
            net.minecraft.world.level.chunk.LevelChunk chunk =
                    serverLevel.getChunkSource().getChunkNow(
                            projectile.blockPosition().getX() >> 4,
                            projectile.blockPosition().getZ() >> 4);
            if (chunk != null
                    && !chunk.getFluidState(projectile.blockPosition()).isEmpty()) {
                return cbcForces;
            }
        } else if (!level.getFluidState(projectile.blockPosition()).isEmpty()) {
            return cbcForces;
        }

        double gravityTick = projectile.isNoGravity() ? 0.0
                : settings.gravity
                / TICK_SQUARED_PER_SECOND_SQUARED;

        Vec3 wind = windVelocity(level, position, settings, runtime);
        Vec3 relativeAirVelocity = velocity.subtract(wind);
        Vec3 acceleration = new Vec3(0.0, -gravityTick, 0.0)
                .add(aerodynamicAcceleration(projectile, level, position, relativeAirVelocity,
                        settings, runtime))
                .add(rocketMotorAcceleration(projectile, velocity, settings));

        if (settings.enableCoriolis) {
            acceleration = acceleration.add(coriolisAcceleration(
                    velocity, settings.coriolisOmegaY, settings.coriolisOmegaZ));
        }
        if (settings.enableSpinDrift) {
            acceleration = acceleration.add(spinDrift(
                    projectile, velocity, gravityTick, settings.spinDriftFactor));
        }

        Vec3 physicalNextVelocity = velocity.add(acceleration);
        if (!finite(physicalNextVelocity) || physicalNextVelocity.lengthSqr() < EPSILON) {
            return cbcForces;
        }

        return physicalNextVelocity.subtract(velocity);
    }

    /**
     * Worldgen-free force estimate used only to plan the low-altitude chunk
     * corridor at launch. It deliberately uses the neutral biome temperature
     * instead of asking an unloaded position for its biome, which would load
     * or generate the very chunks the planner is trying to schedule.
     */
    public static Vec3 predictForces(AbstractCannonProjectile projectile, Vec3 position,
                                     Vec3 velocity) {
        Level level = projectile.level();
        LevelRuntime runtime = runtime(level);
        FlightSettings settings = runtime.settings(level);
        if (!settings.enabled || velocity.lengthSqr() < EPSILON
                || !affects(projectile, settings)) {
            return new Vec3(0.0, projectile.isNoGravity() ? 0.0
                    : -settings.gravity / TICK_SQUARED_PER_SECOND_SQUARED, 0.0);
        }

        double gravityTick = projectile.isNoGravity() ? 0.0
                : settings.gravity / TICK_SQUARED_PER_SECOND_SQUARED;
        Vec3 wind = windVelocity(level, position, settings, runtime);
        Vec3 relativeAirVelocity = velocity.subtract(wind);
        double neutralTemperature = 15.0
                - Math.max(0.0, position.y - settings.seaLevelY) * 0.0065;
        Vec3 acceleration = new Vec3(0.0, -gravityTick, 0.0)
                .add(aerodynamicAccelerationAtTemperature(
                        projectile, position, relativeAirVelocity,
                        neutralTemperature, settings));
        if (settings.enableCoriolis) {
            acceleration = acceleration.add(coriolisAcceleration(
                    velocity, settings.coriolisOmegaY, settings.coriolisOmegaZ));
        }
        return finite(acceleration) ? acceleration : Vec3.ZERO;
    }

    private static boolean affects(AbstractCannonProjectile projectile, FlightSettings settings) {
        if (ProjectileExclusions.isCbcAtFixRocket(projectile)) return false;
        if (projectile instanceof AbstractBigCannonProjectile) {
            return settings.affectBigCannons;
        }
        if (projectile instanceof AbstractAutocannonProjectile) {
            return settings.affectAutocannons;
        }
        // Addon projectiles which extend CBC's common projectile base also get
        // realistic flight when at least one family is enabled.
        return settings.affectBigCannons || settings.affectAutocannons;
    }

    private static Vec3 aerodynamicAcceleration(AbstractCannonProjectile projectile, Level level,
                                                 Vec3 position, Vec3 relativeVelocity,
                                                 FlightSettings settings, LevelRuntime runtime) {
        double temperatureC = temperatureCelsius(level, position, settings, runtime);
        return aerodynamicAccelerationAtTemperature(
                projectile, position, relativeVelocity, temperatureC, settings);
    }

    private static Vec3 aerodynamicAccelerationAtTemperature(
            AbstractCannonProjectile projectile,
            Vec3 position,
            Vec3 relativeVelocity,
            double temperatureC,
            FlightSettings settings
    ) {
        double relativeSpeed = relativeVelocity.length();
        if (relativeSpeed < EPSILON) return Vec3.ZERO;

        double density = airDensity(position.y, temperatureC, settings);
        double speedOfSound = Math.max(250.0, 331.3 + 0.606 * temperatureC);
        double mach = relativeSpeed * TICKS_PER_SECOND / speedOfSound;
        ProjectileTypeProfile typeProfile = typeProfile(projectile);
        boolean rocket = typeProfile.rocket;
        double baseDragCoefficient = rocket
                ? settings.rocketDragCoefficient
                : typeProfile.baseDragCoefficient;
        double dragCoefficient = baseDragCoefficient * machMultiplier(mach);

        // Diameter is intentionally not cached by EntityType: Military Supplement
        // uses the same projectile entity types in its 60, 80, 125 and 180 mm
        // cannons. The launcher family is recorded on each individual round.
        double diameter = projectileDiameter(projectile, settings);
        double area = Math.PI * diameter * diameter * 0.25;
        double referenceMass = Math.max(0.25, projectile.getProjectileMass());
        double relativeMass = Math.sqrt(referenceMass / 2.0);
        double lengthCalibers = rocket
                ? settings.rocketLengthCalibers
                : settings.projectileLengthCalibers;
        double massKg = Math.max(0.1,
                settings.projectileDensity
                        * area
                        * diameter
                        * lengthCalibers
                        * settings.projectileSolidFraction
                        * relativeMass);

        // With 1 block = 1 metre and 20 ticks/s, converting v^2 to m/s and
        // acceleration back to blocks/tick^2 cancels the factor of 400.
        double dragAcceleration = 0.5 * density * dragCoefficient * area
                / massKg * relativeSpeed * relativeSpeed;
        double maximumSpeedLoss = rocket
                ? settings.rocketMaximumSpeedLossPerTick
                : 0.25;
        dragAcceleration = Math.min(dragAcceleration, relativeSpeed * maximumSpeedLoss);
        double scale = -dragAcceleration / relativeSpeed;
        return new Vec3(relativeVelocity.x * scale, relativeVelocity.y * scale,
                relativeVelocity.z * scale);
    }

    private static Vec3 windVelocity(Level level, Vec3 position, FlightSettings settings,
                                     LevelRuntime runtime) {
        if (!settings.enableWind) return Vec3.ZERO;

        double baseSpeed = settings.windSpeed;
        double gustSpeed = settings.gustSpeed;

        // Weather is an actual source of wind instead of a multiplier of the
        // configured value. Rain/thunder levels change only with Minecraft's
        // weather; the wind field itself never depends on game time.
        double rain = 0.0;
        double thunder = 0.0;
        if (settings.weatherAffectsWind) {
            rain = runtime.rain;
            thunder = runtime.thunder;
        }
        double effectiveBaseSpeed = baseSpeed
                + settings.rainWindBonus * rain
                + settings.thunderWindBonus * thunder;
        double effectiveGustSpeed = gustSpeed
                + settings.rainGustBonus * rain
                + settings.thunderGustBonus * thunder;
        if (effectiveBaseSpeed <= 0.0 && effectiveGustSpeed <= 0.0) return Vec3.ZERO;

        long seed = windSeed(level);
        double regionSize = settings.windRegionSize;
        double sampleX = position.x / regionSize;
        double sampleZ = position.z / regionSize;
        long cellX = (long) Math.floor(sampleX);
        long cellZ = (long) Math.floor(sampleZ);
        WindCell cell = runtime.windCell(seed, cellX, cellZ);
        double tx = smoothStep(sampleX - cellX);
        double tz = smoothStep(sampleZ - cellZ);
        double speedNoise = cell.sample(0, tx, tz);
        double directionNoise = cell.sample(1, tx, tz);
        double verticalNoise = cell.sample(2, tx, tz);

        double regionalMultiplier = Math.max(0.0, 1.0
                + speedNoise * settings.windSpeedVariation);
        double spatialVariation = effectiveGustSpeed * speedNoise;
        double altitude = Math.max(0.0, position.y - settings.seaLevelY);
        double maximumAltitudeGain = settings.altitudeWindMaximumMultiplier - 1.0;
        double altitudeFactor = 1.0 + Math.min(maximumAltitudeGain,
                Math.log1p(altitude / 10.0) * 0.08);
        double horizontalSpeed = Math.max(0.0,
                (effectiveBaseSpeed * regionalMultiplier + spatialVariation) * altitudeFactor)
                / TICKS_PER_SECOND;

        double direction = Math.toRadians(settings.windDirection
                + directionNoise * settings.windDirectionVariation);
        double verticalTurbulence = effectiveGustSpeed
                * settings.verticalTurbulenceFraction
                * verticalNoise / TICKS_PER_SECOND;
        return new Vec3(Math.sin(direction) * horizontalSpeed, verticalTurbulence,
                Math.cos(direction) * horizontalSpeed);
    }

    private static long windSeed(Level level) {
        Long cached = WIND_SEEDS.get(level);
        if (cached != null) return cached;
        long dimension = level.dimension().location().toString().hashCode();
        // A computer cannot normally read Minecraft's private world seed.
        // Using the public config seed makes the server, Lua fire-control and
        // the web calculator reproduce exactly the same spatial wind field.
        long seed = mix64(BallisticsConfig.WIND_SEED_SALT.get()
                ^ dimension * 0x9E3779B97F4A7C15L);
        WIND_SEEDS.put(level, seed);
        return seed;
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

    private static Vec3 coriolisAcceleration(
            Vec3 velocityTick,
            double omegaY,
            double omegaZ
    ) {
        // -2 * (omega x velocity) with m/s -> blocks/tick^2 conversion.
        double scale = -2.0 * TICKS_PER_SECOND / TICK_SQUARED_PER_SECOND_SQUARED;
        return new Vec3(
                (omegaY * velocityTick.z - omegaZ * velocityTick.y) * scale,
                omegaZ * velocityTick.x * scale,
                -omegaY * velocityTick.x * scale
        );
    }

    private static Vec3 spinDrift(AbstractCannonProjectile projectile, Vec3 velocity,
                                  double gravityTick, double spinDriftFactor) {
        double horizontalSqr = velocity.x * velocity.x + velocity.z * velocity.z;
        if (horizontalSqr < EPSILON) return Vec3.ZERO;
        double inverseHorizontal = 1.0 / Math.sqrt(horizontalSqr);
        double buildUp = Math.min(1.0, projectile.tickCount / 100.0);
        double acceleration = gravityTick * spinDriftFactor * buildUp;
        return new Vec3(
                velocity.z * inverseHorizontal * acceleration,
                0.0,
                -velocity.x * inverseHorizontal * acceleration
        );
    }

    private static double temperatureCelsius(Level level, Vec3 position,
                                             FlightSettings settings, LevelRuntime runtime) {
        float biomeTemperature = runtime.biomeTemperature(level, position);
        // Minecraft's common temperate value (0.8) maps to roughly 15 C.
        return 15.0 + (biomeTemperature - 0.8) * 20.0
                - Math.max(0.0, position.y - settings.seaLevelY) * 0.0065;
    }

    private static double airDensity(double y, double temperatureC, FlightSettings settings) {
        double altitude = y - settings.seaLevelY;
        double pressureFactor = Math.exp(-altitude / settings.scaleHeight);
        double temperatureFactor = 288.15 / Math.max(150.0, temperatureC + 273.15);
        // Do not impose an artificial high-altitude atmosphere. A tiny
        // positive floor only protects later divisions from denormal values.
        return Math.clamp(SEA_LEVEL_DENSITY * pressureFactor * temperatureFactor, 1.0E-9, 3.0);
    }

    private static double baseDragCoefficient(ResourceLocation typeId, String fallbackClassName) {
        String name = typeId == null
                ? fallbackClassName
                : typeId.getPath().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("trafficcone")) return 0.90;
        if (name.contains("mortar") || name.contains("grapeshot")) return 0.47;
        // APFSDS/APDS remain long, fin-stabilised penetrators regardless of
        // whether an addon fires them from a medium cannon or a normal CBC big
        // cannon. Keeping this before the family-specific branches also makes
        // the in-game trajectory match the artillery calculator's profile.
        if (name.contains("apfsds") || name.contains("apds")) return 0.18;
        if (name.contains("mediumcannon")) {
            if (name.startsWith("ap") || name.contains("aphe")) return 0.22;
            return 0.25;
        }
        if (name.contains("apshot") || name.contains("solidshot")) return 0.28;
        if (name.contains("apshell")) return 0.22;
        if (name.contains("autocannon") || name.contains("machinegun")) return 0.20;
        if (name.contains("shell")) return 0.25;
        return 0.30;
    }

    private static double projectileDiameter(
            AbstractCannonProjectile projectile,
            FlightSettings settings
    ) {
        if (settings.useNominalCalibers) {
            ProjectileTypeProfile profile = typeProfile(projectile);
            ResourceLocation typeId = profile.typeId;
            if (profile.heavyAutocannon) {
                return settings.heavyAutocannonCaliber;
            }
            if (projectile instanceof AbstractAutocannonProjectile
                    || profile.addonAutocannon) {
                return settings.autocannonCaliber;
            }
            if (profile.standardCbcBigCannon) {
                return settings.cbcBigCannonCaliber;
            }
            if (typeId != null && typeId.getNamespace().equals("createbigcannons")
                    && (typeId.getPath().equals("drop_mortar_shell")
                    || typeId.getPath().equals("mortar_stone"))) {
                return settings.dropMortarCaliber;
            }
            int militaryFamily = militaryCannonFamily(projectile, typeId);
            if (militaryFamily != 0) {
                return switch (militaryFamily) {
                    case 1 -> settings.militarySmallCaliber;
                    case 2 -> settings.militaryMediumCaliber;
                    case 3 -> settings.militaryLargeCaliber;
                    case 4 -> settings.militaryGiantCaliber;
                    default -> throw new IllegalStateException("Unknown Military Supplement cannon family");
                };
            }
            if (profile.torpedo) {
                return settings.torpedoCaliber;
            }
            if (profile.mediumRocket) {
                return settings.mediumRocketCaliber;
            }
            if (profile.smallRocket) {
                return settings.rocketPodCaliber;
            }
            if (profile.mediumCannon) {
                return settings.mediumCannonCaliber;
            }
        }
        return Math.max(0.05, Math.min(projectile.getBbWidth(), projectile.getBbHeight()));
    }

    static double configuredMilitaryMuzzleVelocity(AbstractCannonProjectile projectile) {
        if (!BallisticsConfig.ENABLED.get()) return 0.0;
        ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
        return switch (militaryCannonFamily(projectile, typeId)) {
            case 1 -> BallisticsConfig.MILITARY_SMALL_CANNON_MUZZLE_VELOCITY_MPS.get();
            case 2 -> BallisticsConfig.MILITARY_MEDIUM_CANNON_MUZZLE_VELOCITY_MPS.get();
            case 3 -> BallisticsConfig.MILITARY_LARGE_CANNON_MUZZLE_VELOCITY_MPS.get();
            case 4 -> BallisticsConfig.MILITARY_GIANT_CANNON_MUZZLE_VELOCITY_MPS.get();
            default -> 0.0;
        };
    }

    private static int militaryCannonFamily(AbstractCannonProjectile projectile,
                                             ResourceLocation typeId) {
        if (typeId == null || !typeId.getNamespace().equals("cbcmoreshells")
                || !(typeId.getPath().startsWith("normal_")
                || typeId.getPath().startsWith("extended_"))) {
            return 0;
        }

        int saved = projectile.getPersistentData().getInt(MILITARY_CANNON_FAMILY_TAG);
        if (saved >= 1 && saved <= 4) return saved;

        String materialPath = dualCannonMaterialPath(projectile);
        int family;
        if (materialPath == null) {
            // Old rounds loaded from a save do not retain the launch contraption.
            // Normal ammunition belongs to the small family; extended rounds use
            // the large family as the safest deterministic fallback.
            family = typeId.getPath().startsWith("extended_") ? 3 : 1;
        } else if (materialPath.startsWith("large_single_")) {
            family = 3;
        } else if (materialPath.startsWith("large_")) {
            family = 4;
        } else if (materialPath.startsWith("wide_")) {
            family = 2;
        } else {
            family = 1;
        }
        projectile.getPersistentData().putInt(MILITARY_CANNON_FAMILY_TAG, family);
        return family;
    }

    private static String dualCannonMaterialPath(AbstractCannonProjectile projectile) {
        try {
            Class<?> projectileClass = projectile.getClass();
            Field field = DUAL_CANNON_CONTRAPTION_FIELDS.get(projectileClass);
            if (field == null && !NO_DUAL_CANNON_CONTRAPTION_FIELD.contains(projectileClass)) {
                Class<?> current = projectileClass;
                while (current != null && field == null) {
                    try {
                        field = current.getDeclaredField("contraption");
                        field.setAccessible(true);
                    } catch (NoSuchFieldException ignored) {
                        current = current.getSuperclass();
                    }
                }
                if (field == null) {
                    NO_DUAL_CANNON_CONTRAPTION_FIELD.add(projectileClass);
                } else {
                    DUAL_CANNON_CONTRAPTION_FIELDS.put(projectileClass, field);
                }
            }
            if (field == null) return null;
            Object contraption = field.get(projectile);
            if (contraption == null) return null;
            Object material = contraption.getClass().getMethod("getCannonMaterial")
                    .invoke(contraption);
            if (material == null) return null;
            Object name = material.getClass().getMethod("name").invoke(material);
            return name instanceof ResourceLocation id ? id.getPath() : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    /** Keeps CBC AT's native solid-rocket motor while replacing only atmosphere/gravity. */
    private static Vec3 rocketMotorAcceleration(AbstractCannonProjectile projectile,
                                                 Vec3 velocity, FlightSettings settings) {
        ProjectileTypeProfile profile = typeProfile(projectile);
        if (!profile.smallRocket && !profile.mediumRocket) {
            return Vec3.ZERO;
        }
        try {
            Class<?> projectileClass = projectile.getClass();
            RocketMotorFields fields = ROCKET_MOTOR_FIELDS.get(projectileClass);
            if (fields == null && !NO_ROCKET_MOTOR_FIELDS.contains(projectileClass)) {
                Field fuel = findField(projectileClass, "fuel");
                Field nextThrust = findField(projectileClass, "nextThrust");
                if (fuel == null || nextThrust == null) {
                    NO_ROCKET_MOTOR_FIELDS.add(projectileClass);
                    return Vec3.ZERO;
                }
                fuel.setAccessible(true);
                nextThrust.setAccessible(true);
                fields = new RocketMotorFields(fuel, nextThrust);
                ROCKET_MOTOR_FIELDS.put(projectileClass, fields);
            }
            if (fields == null || ((Number) fields.fuel().get(projectile)).intValue() <= 0
                    || velocity.lengthSqr() < EPSILON) {
                return Vec3.ZERO;
            }
            double thrust = ((Number) fields.nextThrust().get(projectile)).doubleValue();
            if (profile.smallRocket) {
                thrust *= settings.smallRocketThrustMultiplier;
            } else if (profile.mediumRocket) {
                thrust *= settings.mediumRocketThrustMultiplier;
            }
            return Double.isFinite(thrust) && thrust > 0.0
                    ? velocity.normalize().scale(thrust) : Vec3.ZERO;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Vec3.ZERO;
        }
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static boolean isTorpedoProjectile(ResourceLocation typeId) {
        return typeId != null && typeId.getPath().contains("torpedo")
                && !typeId.getPath().endsWith("_burst");
    }

    private static boolean isSmallRocketProjectile(ResourceLocation typeId) {
        if (typeId == null || !typeId.getNamespace().equals("cbc_at")) return false;
        return switch (typeId.getPath()) {
            case "ap_rocket", "flak_rocket", "he_rocket", "hei_rocket" -> true;
            default -> false;
        };
    }

    private static boolean isMediumRocketProjectile(ResourceLocation typeId) {
        return typeId != null && typeId.getNamespace().equals("cbc_at")
                && typeId.getPath().startsWith("medium_")
                && typeId.getPath().endsWith("_rocket");
    }

    private static boolean isRocketProjectile(ResourceLocation typeId) {
        return isSmallRocketProjectile(typeId)
                || isMediumRocketProjectile(typeId)
                || isEnhancedShellsRocket(typeId);
    }

    private static boolean isEnhancedShellsProjectile(ResourceLocation typeId) {
        return typeId != null && typeId.getNamespace().equals("cbc_enhanced_shells")
                && !typeId.getPath().equals("small_missile")
                && !typeId.getPath().equals("cluster_bomblet");
    }

    private static boolean isEnhancedShellsRocket(ResourceLocation typeId) {
        return typeId != null && typeId.getNamespace().equals("cbc_enhanced_shells")
                && typeId.getPath().equals("rocket");
    }

    /**
     * Identifies only the real 20 mm rapid-fire families used by CBC and the
     * addons in the supported pack. Unknown projectiles deliberately return
     * false so they keep seamless unloaded-chunk transit.
     *
     * <p>Military Supplement uses the package name {@code dual_cannon} for the
     * ammunition shared by its 60, 80, 125 and 180 mm cannon families. That
     * package name must never be treated as a calibre: doing so used to disable
     * transit for Small Single Cannon rounds as well as every other Military
     * Supplement cannon using the same ammunition.</p>
     */
    public static boolean isTwentyMillimetreProjectile(
            AbstractCannonProjectile projectile
    ) {
        ProjectileTypeProfile profile = typeProfile(projectile);
        String path = profile.path;

        if (profile.heavyAutocannon
                || path.startsWith("ha_")) {
            return false;
        }
        if (profile.mediumCannon) {
            return false;
        }
        if (projectile instanceof AbstractAutocannonProjectile
                || profile.addonAutocannon) {
            return true;
        }

        return path.contains("autocannon")
                || path.contains("machine_gun")
                || path.contains("rotary_cannon")
                || path.contains("twin_autocannon");
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

    private static boolean isMediumCannonProjectile(ResourceLocation typeId) {
        return typeId != null
                && typeId.getNamespace().equals("cbcmodernwarfare")
                && typeId.getPath().endsWith("_mediumshell");
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

    private record RocketMotorFields(Field fuel, Field nextThrust) {
    }

    private static LevelRuntime runtime(Level level) {
        return LEVEL_RUNTIMES.computeIfAbsent(level, ignored -> new LevelRuntime());
    }

    private static ProjectileTypeProfile typeProfile(AbstractCannonProjectile projectile) {
        return TYPE_PROFILES.computeIfAbsent(projectile.getType(), ignored -> {
            ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(projectile.getType());
            String className = projectile.getClass().getName()
                    .toLowerCase(java.util.Locale.ROOT);
            String simpleName = projectile.getClass().getSimpleName()
                    .toLowerCase(java.util.Locale.ROOT);
            String path = typeId == null ? "" : typeId.getPath()
                    .toLowerCase(java.util.Locale.ROOT);
            boolean smallRocket = isSmallRocketProjectile(typeId);
            boolean mediumRocket = isMediumRocketProjectile(typeId);
            boolean enhancedRocket = isEnhancedShellsRocket(typeId);
            return new ProjectileTypeProfile(
                    typeId,
                    className,
                    path,
                    isHeavyAutocannonProjectile(typeId),
                    isAddonAutocannonProjectile(typeId),
                    isStandardCbcBigCannonProjectile(typeId)
                            || isEnhancedShellsProjectile(typeId),
                    isMediumCannonProjectile(typeId),
                    isTorpedoProjectile(typeId),
                    smallRocket,
                    mediumRocket,
                    smallRocket || mediumRocket || enhancedRocket,
                    baseDragCoefficient(typeId, simpleName)
            );
        });
    }

    private record ProjectileTypeProfile(
            ResourceLocation typeId,
            String className,
            String path,
            boolean heavyAutocannon,
            boolean addonAutocannon,
            boolean standardCbcBigCannon,
            boolean mediumCannon,
            boolean torpedo,
            boolean smallRocket,
            boolean mediumRocket,
            boolean rocket,
            double baseDragCoefficient
    ) {
    }

    /** All Forge config lookups and weather reads are shared by a whole volley. */
    private record FlightSettings(
            boolean enabled,
            boolean affectBigCannons,
            boolean affectAutocannons,
            double gravity,
            double seaLevelY,
            double scaleHeight,
            double projectileDensity,
            double projectileLengthCalibers,
            double projectileSolidFraction,
            boolean useNominalCalibers,
            double cbcBigCannonCaliber,
            double autocannonCaliber,
            double heavyAutocannonCaliber,
            double mediumCannonCaliber,
            double militarySmallCaliber,
            double militaryMediumCaliber,
            double militaryLargeCaliber,
            double militaryGiantCaliber,
            double dropMortarCaliber,
            double torpedoCaliber,
            double rocketPodCaliber,
            double mediumRocketCaliber,
            double rocketDragCoefficient,
            double rocketLengthCalibers,
            double rocketMaximumSpeedLossPerTick,
            double smallRocketThrustMultiplier,
            double mediumRocketThrustMultiplier,
            boolean enableWind,
            boolean weatherAffectsWind,
            double windSpeed,
            double windDirection,
            double gustSpeed,
            double windRegionSize,
            double windDirectionVariation,
            double windSpeedVariation,
            double rainWindBonus,
            double thunderWindBonus,
            double rainGustBonus,
            double thunderGustBonus,
            double verticalTurbulenceFraction,
            double altitudeWindMaximumMultiplier,
            boolean enableCoriolis,
            double coriolisOmegaY,
            double coriolisOmegaZ,
            boolean enableSpinDrift,
            double spinDriftFactor
    ) {
        private static FlightSettings read() {
            double latitude = Math.toRadians(BallisticsConfig.LATITUDE_DEGREES.get());
            return new FlightSettings(
                    BallisticsConfig.ENABLED.get(),
                    BallisticsConfig.AFFECT_BIG_CANNONS.get(),
                    BallisticsConfig.AFFECT_AUTOCANNONS.get(),
                    BallisticsConfig.GRAVITY_METRES_PER_SECOND_SQUARED.get(),
                    BallisticsConfig.SEA_LEVEL_Y.get(),
                    BallisticsConfig.SCALE_HEIGHT_METRES.get(),
                    BallisticsConfig.PROJECTILE_DENSITY_KG_M3.get(),
                    BallisticsConfig.PROJECTILE_LENGTH_CALIBERS.get(),
                    BallisticsConfig.PROJECTILE_SOLID_FRACTION.get(),
                    BallisticsConfig.USE_NOMINAL_CALIBER_OVERRIDES.get(),
                    BallisticsConfig.CBC_BIG_CANNON_CALIBER_METRES.get(),
                    BallisticsConfig.AUTOCANNON_CALIBER_METRES.get(),
                    BallisticsConfig.HEAVY_AUTOCANNON_CALIBER_METRES.get(),
                    BallisticsConfig.MEDIUM_CANNON_CALIBER_METRES.get(),
                    BallisticsConfig.MILITARY_SMALL_CANNON_CALIBER_METRES.get(),
                    BallisticsConfig.MILITARY_MEDIUM_CANNON_CALIBER_METRES.get(),
                    BallisticsConfig.MILITARY_LARGE_CANNON_CALIBER_METRES.get(),
                    BallisticsConfig.MILITARY_GIANT_CANNON_CALIBER_METRES.get(),
                    BallisticsConfig.DROP_MORTAR_CALIBER_METRES.get(),
                    BallisticsConfig.TORPEDO_TUBE_CALIBER_METRES.get(),
                    BallisticsConfig.ROCKET_POD_CALIBER_METRES.get(),
                    BallisticsConfig.MEDIUM_ROCKET_RAIL_CALIBER_METRES.get(),
                    BallisticsConfig.ROCKET_DRAG_COEFFICIENT.get(),
                    BallisticsConfig.ROCKET_LENGTH_CALIBERS.get(),
                    BallisticsConfig.ROCKET_MAX_SPEED_LOSS_PER_TICK.get(),
                    BallisticsConfig.SMALL_ROCKET_THRUST_MULTIPLIER.get(),
                    BallisticsConfig.MEDIUM_ROCKET_THRUST_MULTIPLIER.get(),
                    BallisticsConfig.ENABLE_WIND.get(),
                    BallisticsConfig.WEATHER_AFFECTS_WIND.get(),
                    BallisticsConfig.WIND_SPEED_METRES_PER_SECOND.get(),
                    BallisticsConfig.WIND_DIRECTION_DEGREES.get(),
                    BallisticsConfig.GUST_SPEED_METRES_PER_SECOND.get(),
                    BallisticsConfig.WIND_REGION_SIZE_METRES.get(),
                    BallisticsConfig.WIND_DIRECTION_VARIATION_DEGREES.get(),
                    BallisticsConfig.WIND_SPEED_VARIATION_FRACTION.get(),
                    BallisticsConfig.RAIN_WIND_BONUS_METRES_PER_SECOND.get(),
                    BallisticsConfig.THUNDER_WIND_BONUS_METRES_PER_SECOND.get(),
                    BallisticsConfig.RAIN_GUST_BONUS_METRES_PER_SECOND.get(),
                    BallisticsConfig.THUNDER_GUST_BONUS_METRES_PER_SECOND.get(),
                    BallisticsConfig.VERTICAL_TURBULENCE_FRACTION.get(),
                    BallisticsConfig.ALTITUDE_WIND_MAXIMUM_MULTIPLIER.get(),
                    BallisticsConfig.ENABLE_CORIOLIS.get(),
                    EARTH_ANGULAR_SPEED * Math.sin(latitude),
                    -EARTH_ANGULAR_SPEED * Math.cos(latitude),
                    BallisticsConfig.ENABLE_SPIN_DRIFT.get(),
                    BallisticsConfig.SPIN_DRIFT_FACTOR.get()
            );
        }
    }

    private static final class LevelRuntime {
        private final Long2ObjectOpenHashMap<WindCell> windCells = new Long2ObjectOpenHashMap<>();
        private final Long2FloatOpenHashMap biomeTemperatures = new Long2FloatOpenHashMap();
        private final LongArrayFIFOQueue windCellOrder = new LongArrayFIFOQueue();
        private final LongArrayFIFOQueue biomeTemperatureOrder = new LongArrayFIFOQueue();
        private long settingsTick = Long.MIN_VALUE;
        private FlightSettings settings;
        private float rain;
        private float thunder;

        private LevelRuntime() {
            this.biomeTemperatures.defaultReturnValue(Float.NaN);
        }

        private FlightSettings settings(Level level) {
            long gameTime = level.getGameTime();
            if (this.settings == null || this.settingsTick != gameTime) {
                this.settingsTick = gameTime;
                this.settings = FlightSettings.read();
                if (this.settings.weatherAffectsWind) {
                    this.rain = level.getRainLevel(1.0F);
                    this.thunder = level.getThunderLevel(1.0F);
                } else {
                    this.rain = 0.0F;
                    this.thunder = 0.0F;
                }
            }
            return this.settings;
        }

        private WindCell windCell(long seed, long cellX, long cellZ) {
            long key = ChunkPos.asLong((int) cellX, (int) cellZ);
            WindCell cell = this.windCells.get(key);
            if (cell == null || cell.seed != seed || cell.x != cellX || cell.z != cellZ) {
                cell = new WindCell(seed, cellX, cellZ);
                // Incremental FIFO eviction avoids a large clear/GC spike in a
                // world with sustained long-range fire.
                if (!this.windCells.containsKey(key)) {
                    if (this.windCells.size() >= 8192 && !this.windCellOrder.isEmpty()) {
                        this.windCells.remove(this.windCellOrder.dequeueLong());
                    }
                    this.windCellOrder.enqueue(key);
                }
                this.windCells.put(key, cell);
            }
            return cell;
        }

        private float biomeTemperature(Level level, Vec3 position) {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel
                    && serverLevel.getChunkSource().getChunkNow(
                    ((int) Math.floor(position.x)) >> 4,
                    ((int) Math.floor(position.z)) >> 4) == null) {
                return 0.8F;
            }
            // Minecraft's biome lookup is quantized to 4x4x4 quart cells.
            // Caching at that exact resolution preserves the old trajectory;
            // a 16-block section cache could incorrectly merge biome borders.
            int quartX = ((int) Math.floor(position.x)) >> 2;
            int quartY = ((int) Math.floor(position.y)) >> 2;
            int quartZ = ((int) Math.floor(position.z)) >> 2;
            long key = net.minecraft.core.BlockPos.asLong(quartX, quartY, quartZ);
            float cached = this.biomeTemperatures.get(key);
            if (!Float.isNaN(cached)) return cached;
            float value = level.getBiome(net.minecraft.core.BlockPos.containing(position))
                    .value().getBaseTemperature();
            if (this.biomeTemperatures.size() >= 65536
                    && !this.biomeTemperatureOrder.isEmpty()) {
                this.biomeTemperatures.remove(this.biomeTemperatureOrder.dequeueLong());
            }
            this.biomeTemperatures.put(key, value);
            this.biomeTemperatureOrder.enqueue(key);
            return value;
        }
    }

    private static final class WindCell {
        private static final long[] CHANNELS = {0x51EEDL, 0xD1CE7L, 0xA17L};
        private final long seed;
        private final long x;
        private final long z;
        private final double[] corners = new double[12];

        private WindCell(long seed, long x, long z) {
            this.seed = seed;
            this.x = x;
            this.z = z;
            for (int channel = 0; channel < CHANNELS.length; channel++) {
                int offset = channel * 4;
                long salt = CHANNELS[channel];
                this.corners[offset] = latticeNoise(seed, x, z, salt);
                this.corners[offset + 1] = latticeNoise(seed, x + 1, z, salt);
                this.corners[offset + 2] = latticeNoise(seed, x, z + 1, salt);
                this.corners[offset + 3] = latticeNoise(seed, x + 1, z + 1, salt);
            }
        }

        private double sample(int channel, double tx, double tz) {
            int offset = channel * 4;
            return lerp(
                    lerp(this.corners[offset], this.corners[offset + 1], tx),
                    lerp(this.corners[offset + 2], this.corners[offset + 3], tx),
                    tz
            );
        }
    }
}
