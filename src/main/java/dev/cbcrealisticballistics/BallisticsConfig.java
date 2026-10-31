package dev.cbcrealisticballistics;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BallisticsConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.DoubleValue GRAVITY_METRES_PER_SECOND_SQUARED;
    public static final ModConfigSpec.DoubleValue SEA_LEVEL_Y;
    public static final ModConfigSpec.DoubleValue SCALE_HEIGHT_METRES;
    public static final ModConfigSpec.DoubleValue PROJECTILE_DENSITY_KG_M3;
    public static final ModConfigSpec.DoubleValue PROJECTILE_LENGTH_CALIBERS;
    public static final ModConfigSpec.DoubleValue PROJECTILE_SOLID_FRACTION;
    public static final ModConfigSpec.BooleanValue USE_NOMINAL_CALIBER_OVERRIDES;
    public static final ModConfigSpec.DoubleValue CBC_BIG_CANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue AUTOCANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue HEAVY_AUTOCANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MEDIUM_CANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MILITARY_SMALL_CANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MILITARY_MEDIUM_CANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MILITARY_LARGE_CANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MILITARY_GIANT_CANNON_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MILITARY_SMALL_CANNON_MUZZLE_VELOCITY_MPS;
    public static final ModConfigSpec.DoubleValue MILITARY_MEDIUM_CANNON_MUZZLE_VELOCITY_MPS;
    public static final ModConfigSpec.DoubleValue MILITARY_LARGE_CANNON_MUZZLE_VELOCITY_MPS;
    public static final ModConfigSpec.DoubleValue MILITARY_GIANT_CANNON_MUZZLE_VELOCITY_MPS;
    public static final ModConfigSpec.DoubleValue DROP_MORTAR_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue TORPEDO_TUBE_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue ROCKET_POD_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue MEDIUM_ROCKET_RAIL_CALIBER_METRES;
    public static final ModConfigSpec.DoubleValue ROCKET_DRAG_COEFFICIENT;
    public static final ModConfigSpec.DoubleValue ROCKET_LENGTH_CALIBERS;
    public static final ModConfigSpec.DoubleValue ROCKET_MAX_SPEED_LOSS_PER_TICK;
    public static final ModConfigSpec.DoubleValue SMALL_ROCKET_THRUST_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue MEDIUM_ROCKET_THRUST_MULTIPLIER;
    public static final ModConfigSpec.DoubleValue WIND_SPEED_METRES_PER_SECOND;
    public static final ModConfigSpec.DoubleValue WIND_DIRECTION_DEGREES;
    public static final ModConfigSpec.DoubleValue GUST_SPEED_METRES_PER_SECOND;
    public static final ModConfigSpec.BooleanValue ENABLE_WIND;
    public static final ModConfigSpec.BooleanValue WEATHER_AFFECTS_WIND;
    public static final ModConfigSpec.DoubleValue WIND_REGION_SIZE_METRES;
    public static final ModConfigSpec.LongValue WIND_SEED_SALT;
    public static final ModConfigSpec.DoubleValue WIND_DIRECTION_VARIATION_DEGREES;
    public static final ModConfigSpec.DoubleValue WIND_SPEED_VARIATION_FRACTION;
    public static final ModConfigSpec.DoubleValue RAIN_WIND_BONUS_METRES_PER_SECOND;
    public static final ModConfigSpec.DoubleValue THUNDER_WIND_BONUS_METRES_PER_SECOND;
    public static final ModConfigSpec.DoubleValue RAIN_GUST_BONUS_METRES_PER_SECOND;
    public static final ModConfigSpec.DoubleValue THUNDER_GUST_BONUS_METRES_PER_SECOND;
    public static final ModConfigSpec.DoubleValue VERTICAL_TURBULENCE_FRACTION;
    public static final ModConfigSpec.DoubleValue ALTITUDE_WIND_MAXIMUM_MULTIPLIER;
    public static final ModConfigSpec.BooleanValue ENABLE_CORIOLIS;
    public static final ModConfigSpec.DoubleValue LATITUDE_DEGREES;
    public static final ModConfigSpec.BooleanValue ENABLE_SPIN_DRIFT;
    public static final ModConfigSpec.DoubleValue SPIN_DRIFT_FACTOR;
    public static final ModConfigSpec.BooleanValue AFFECT_BIG_CANNONS;
    public static final ModConfigSpec.BooleanValue AFFECT_AUTOCANNONS;
    public static final ModConfigSpec.BooleanValue PRESERVE_ORIGINAL_IMPACT_ENERGY;
    public static final ModConfigSpec.BooleanValue DISABLE_PROJECTILE_DESPAWN;
    public static final ModConfigSpec.DoubleValue PROJECTILE_DESPAWN_TIMEOUT_SECONDS;
    public static final ModConfigSpec.DoubleValue STATIONARY_PROJECTILE_TIMEOUT_SECONDS;
    public static final ModConfigSpec.BooleanValue HIGH_ALTITUDE_TRANSIT_ENABLED;
    public static final ModConfigSpec.DoubleValue HIGH_ALTITUDE_TRANSIT_MIN_Y;
    public static final ModConfigSpec.BooleanValue PERFORMANCE_DIAGNOSTICS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.comment(
                "Physical trajectory model for Create: Big Cannons.",
                "Flight and, by default, impact calculations use the same physical speed.",
                "Explosion power and projectile toughness values are not changed by this mod.")
                .push("ballistics");

        ENABLED = builder.comment("Master switch.").define("enabled", true);
        AFFECT_BIG_CANNONS = builder.comment("Apply to big-cannon projectiles and addon subclasses.")
                .define("affectBigCannons", true);
        AFFECT_AUTOCANNONS = builder.comment("Apply to autocannon and machine-gun projectiles.")
                .define("affectAutocannons", true);
        PRESERVE_ORIGINAL_IMPACT_ENERGY = builder.comment(
                        "Keep CBC's original impact speed for penetration, block damage and impact force.",
                        "When disabled, impacts use the physical speed produced by this mod's trajectory.",
                        "Disabled by default so aerodynamic energy loss also affects the impact.")
                .define("preserveOriginalImpactEnergy", false);
        DISABLE_PROJECTILE_DESPAWN = builder.comment(
                        "Prevent lifetime despawn while CBC projectiles and addon subclasses are still flying.",
                        "Spent or embedded projectiles are cleaned up normally, preventing invisible ghost entities.",
                        "Impact, detonation and explicit removal still work. This does not change or overwrite",
                        "the programmable-fuze persistence setting, so both mods can be installed together.")
                .define("disableProjectileDespawn", true);
        PROJECTILE_DESPAWN_TIMEOUT_SECONDS = builder.comment(
                        "Hard maximum lifetime for every CBC projectile and addon subclass, in seconds.",
                        "The default 600 seconds is ten minutes. This safety timeout still applies when",
                        "disableProjectileDespawn or programmable-fuze persistence keeps a flying round alive.")
                .defineInRange("projectileDespawnTimeoutSeconds", 600.0, 1.0, 86_400.0);
        STATIONARY_PROJECTILE_TIMEOUT_SECONDS = builder.comment(
                        "Remove every CBC projectile or addon subclass after it has remained practically",
                        "motionless for this many seconds. This cleanup is independent of projectile type,",
                        "native lifetime, disableProjectileDespawn and programmable-fuze persistence.",
                        "Movement immediately resets the timer. The default is 30 seconds.")
                .defineInRange("stationaryProjectileTimeoutSeconds", 30.0, 1.0, 3_600.0);
        HIGH_ALTITUDE_TRANSIT_ENABLED = builder.comment(
                        "Keep every non-20-mm CBC projectile moving through unloaded and ungenerated chunks from its",
                        "first flight tick. High flight remains ticket-free; below Y=350 a bounded asynchronous",
                        "planner warms only the final corridor one chunk at a time. Blocks in already loaded chunks,",
                        "entities and Sable constructs still collide without streaming every route chunk.",
                        "Entity and Sable collision remains active throughout the flight. Only actual 20 mm",
                        "autocannon, machine-gun, rotary and twin-autocannon rounds are deliberately excluded.",
                        "Military Supplement's 60/80/125/180 mm cannon rounds remain enabled.")
                .define("highAltitudeTransitEnabled", true);
        HIGH_ALTITUDE_TRANSIT_MIN_Y = builder.comment(
                        "Legacy compatibility value retained so existing configs remain valid.",
                        "Seamless transit now starts on the first projectile tick at every altitude.")
                .defineInRange("highAltitudeTransitMinY", 350.0, -2048.0, 30000000.0);
        PERFORMANCE_DIAGNOSTICS = builder.comment(
                        "Enable the slow-tick profiler and its performance log.",
                        "Disabled by default because production flight should not pay for timers,",
                        "per-tick metric objects or stack sampling. Enable only while diagnosing TPS.")
                .define("performanceDiagnostics", false);

        builder.comment("Environment and atmosphere").push("environment");
        GRAVITY_METRES_PER_SECOND_SQUARED = builder
                .comment("Gravitational acceleration. Earth standard is 9.80665 m/s^2.")
                .defineInRange("gravityMetresPerSecondSquared", 9.80665, 0.0, 100.0);
        SEA_LEVEL_Y = builder.comment("World Y treated as sea level.")
                .defineInRange("seaLevelY", 64.0, -2048.0, 4096.0);
        SCALE_HEIGHT_METRES = builder
                .comment("Atmospheric scale height. Earth's troposphere is approximately 8500 m.")
                .defineInRange("atmosphericScaleHeightMetres", 8500.0, 100.0, 100000.0);
        PROJECTILE_DENSITY_KG_M3 = builder
                .comment("Reference material density. 7800 kg/m^3 is ordinary steel.")
                .defineInRange("projectileDensityKgPerCubicMetre", 7800.0, 100.0, 30000.0);
        PROJECTILE_LENGTH_CALIBERS = builder
                .comment("Estimated projectile length divided by calibre.")
                .defineInRange("projectileLengthInCalibers", 3.0, 0.5, 10.0);
        PROJECTILE_SOLID_FRACTION = builder
                .comment("Fraction of the bounding cylinder occupied by metal/explosive."
                        + " 0.5 gives roughly 4.7 tonnes for a 0.8 m CBC HE shell.")
                .defineInRange("projectileSolidFraction", 0.5, 0.05, 1.0);
        USE_NOMINAL_CALIBER_OVERRIDES = builder.comment(
                        "Use known real-world nominal calibres for supported addon cannon families instead of",
                        "their visually enlarged Minecraft entity hitboxes.")
                .define("useNominalCaliberOverrides", true);
        CBC_BIG_CANNON_CALIBER_METRES = builder.comment(
                        "Nominal calibre of the standard Create: Big Cannons big cannon.",
                        "The default treats it as an 875 mm cannon.")
                .defineInRange("cbcBigCannonCaliberMetres", 0.875, 0.01, 2.0);
        AUTOCANNON_CALIBER_METRES = builder.comment(
                        "Nominal calibre of CBC autocannons, rotary cannons and twin autocannons.",
                        "The default treats these weapons as 20 mm autocannons.")
                .defineInRange("autocannonCaliberMetres", 0.020, 0.005, 0.5);
        HEAVY_AUTOCANNON_CALIBER_METRES = builder.comment(
                        "Nominal calibre of the CBC AT Heavy Autocannon.",
                        "The default treats it as a 45 mm autocannon.")
                .defineInRange("heavyAutocannonCaliberMetres", 0.045, 0.005, 0.5);
        MEDIUM_CANNON_CALIBER_METRES = builder.comment(
                        "CBC Modern Warfare Medium Cannon calibre. The default treats it as a 155 mm gun.")
                .defineInRange("mediumCannonCaliberMetres", 0.155, 0.01, 2.0);
        MILITARY_SMALL_CANNON_CALIBER_METRES = builder.comment(
                        "Military Supplement Small Single and Small Dual Cannon calibre (60 mm).")
                .defineInRange("militarySmallCannonCaliberMetres", 0.060, 0.01, 2.0);
        MILITARY_MEDIUM_CANNON_CALIBER_METRES = builder.comment(
                        "Military Supplement Medium Single and Medium Dual Cannon calibre (80 mm).")
                .defineInRange("militaryMediumCannonCaliberMetres", 0.080, 0.01, 2.0);
        MILITARY_LARGE_CANNON_CALIBER_METRES = builder.comment(
                        "Military Supplement Large Cannon calibre (125 mm).")
                .defineInRange("militaryLargeCannonCaliberMetres", 0.125, 0.01, 2.0);
        MILITARY_GIANT_CANNON_CALIBER_METRES = builder.comment(
                        "Military Supplement Giant Cannon calibre (180 mm).")
                .defineInRange("militaryGiantCannonCaliberMetres", 0.180, 0.01, 2.0);
        MILITARY_SMALL_CANNON_MUZZLE_VELOCITY_MPS = builder.comment(
                        "Physical muzzle velocity of Military Supplement Small Single/Dual Cannons.")
                .defineInRange("militarySmallCannonMuzzleVelocityMetresPerSecond", 800.0, 1.0, 5000.0);
        MILITARY_MEDIUM_CANNON_MUZZLE_VELOCITY_MPS = builder.comment(
                        "Physical muzzle velocity of Military Supplement Medium Single/Dual Cannons.")
                .defineInRange("militaryMediumCannonMuzzleVelocityMetresPerSecond", 800.0, 1.0, 5000.0);
        MILITARY_LARGE_CANNON_MUZZLE_VELOCITY_MPS = builder.comment(
                        "Physical muzzle velocity of Military Supplement Large Cannons.")
                .defineInRange("militaryLargeCannonMuzzleVelocityMetresPerSecond", 800.0, 1.0, 5000.0);
        MILITARY_GIANT_CANNON_MUZZLE_VELOCITY_MPS = builder.comment(
                        "Physical muzzle velocity of Military Supplement Giant Cannons.")
                .defineInRange("militaryGiantCannonMuzzleVelocityMetresPerSecond", 800.0, 1.0, 5000.0);
        DROP_MORTAR_CALIBER_METRES = builder.comment(
                        "Create: Big Cannons Drop Mortar calibre (120 mm).")
                .defineInRange("dropMortarCaliberMetres", 0.120, 0.01, 2.0);
        TORPEDO_TUBE_CALIBER_METRES = builder.comment(
                        "Nominal calibre for supported torpedo tubes from every addon (380 mm).")
                .defineInRange("torpedoTubeCaliberMetres", 0.380, 0.01, 2.0);
        ROCKET_POD_CALIBER_METRES = builder.comment(
                        "CBC AT Rocket Pod calibre (70 mm).")
                .defineInRange("rocketPodCaliberMetres", 0.070, 0.01, 2.0);
        MEDIUM_ROCKET_RAIL_CALIBER_METRES = builder.comment(
                        "CBC AT Medium Rocket Rail calibre (122 mm).")
                .defineInRange("mediumRocketRailCaliberMetres", 0.122, 0.01, 2.0);
        ROCKET_DRAG_COEFFICIENT = builder.comment(
                        "Aerodynamic drag coefficient used by CBC AT rockets during powered",
                        "and unpowered flight. Rockets are long fin-stabilised bodies, not short shells.")
                .defineInRange("rocketDragCoefficient", 0.12, 0.01, 2.0);
        ROCKET_LENGTH_CALIBERS = builder.comment(
                        "Effective rocket body length divided by calibre for aerodynamic mass estimation.",
                        "This is separate from the ordinary shell length setting.")
                .defineInRange("rocketLengthInCalibers", 12.0, 1.0, 40.0);
        ROCKET_MAX_SPEED_LOSS_PER_TICK = builder.comment(
                        "Safety limit for aerodynamic rocket speed loss in one tick.",
                        "0.02 means no atmosphere calculation can remove more than 2 percent per tick.")
                .defineInRange("rocketMaximumSpeedLossPerTick", 0.02, 0.0001, 0.25);
        SMALL_ROCKET_THRUST_MULTIPLIER = builder.comment(
                        "Motor multiplier for 70 mm CBC AT Rocket Pod projectiles.",
                        "Calibrated together with the rocket coast model for an approximately 8 km envelope",
                        "with 54 fuel ticks and the default atmosphere.")
                .defineInRange("smallRocketThrustMultiplier", 0.294, 0.0, 2.0);
        MEDIUM_ROCKET_THRUST_MULTIPLIER = builder.comment(
                        "Motor multiplier for 122 mm CBC AT Medium Rocket Rail projectiles.",
                        "Calibrated together with the rocket coast model for an approximately 25 km envelope",
                        "with 54 fuel ticks and the default atmosphere.")
                .defineInRange("mediumRocketThrustMultiplier", 0.3285, 0.0, 2.0);
        builder.pop();

        builder.comment(
                "Config-seeded static wind field. At fixed coordinates and fixed weather, wind never changes.",
                "The configured seed is authoritative, so in-game computers and external calculators can",
                "reproduce the server wind without access to Minecraft's private world seed.")
                .push("wind");
        ENABLE_WIND = builder.comment("Master switch for wind and turbulence.")
                .define("enabled", true);
        WIND_SPEED_METRES_PER_SECOND = builder
                .comment("Prevailing horizontal wind speed during clear weather.")
                .defineInRange("speedMetresPerSecond", 4.0, 0.0, 100.0);
        WIND_DIRECTION_DEGREES = builder
                .comment("Direction the wind moves toward: 0 = +Z, 90 = +X.")
                .defineInRange("directionDegrees", 35.0, -360.0, 360.0);
        GUST_SPEED_METRES_PER_SECOND = builder
                .comment("Amplitude of static seed-derived spatial wind variation.")
                .defineInRange("gustSpeedMetresPerSecond", 3.0, 0.0, 50.0);
        WEATHER_AFFECTS_WIND = builder
                .comment(
                        "Make wind respond to the current Minecraft weather.",
                        "Rain and thunder create additional wind and turbulence even when",
                        "the configured clear-weather speed and gust are both zero.")
                .define("weatherAffectsWind", true);
        WIND_REGION_SIZE_METRES = builder
                .comment("Approximate size of one smoothly interpolated wind region in blocks/metres.",
                        "Larger values make wind more uniform along long trajectories.")
                .defineInRange("regionSizeMetres", 2048.0, 16.0, 1_000_000.0);
        WIND_SEED_SALT = builder
                .comment("Authoritative wind-map seed. Change it to generate a different static wind map.")
                .defineInRange("seedSalt", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        WIND_DIRECTION_VARIATION_DEGREES = builder
                .comment("Maximum seed-derived direction deviation from directionDegrees.")
                .defineInRange("directionVariationDegrees", 45.0, 0.0, 180.0);
        WIND_SPEED_VARIATION_FRACTION = builder
                .comment("Seed-derived regional multiplier applied to prevailing wind speed.",
                        "0.35 means regions vary by up to plus or minus 35 percent.")
                .defineInRange("speedVariationFraction", 0.35, 0.0, 1.0);
        RAIN_WIND_BONUS_METRES_PER_SECOND = builder
                .comment("Horizontal wind added by full rain.")
                .defineInRange("rainWindBonusMetresPerSecond", 5.0, 0.0, 100.0);
        THUNDER_WIND_BONUS_METRES_PER_SECOND = builder
                .comment("Additional horizontal wind added by full thunder.")
                .defineInRange("thunderWindBonusMetresPerSecond", 7.0, 0.0, 100.0);
        RAIN_GUST_BONUS_METRES_PER_SECOND = builder
                .comment("Extra static spatial variation amplitude during rain.")
                .defineInRange("rainGustBonusMetresPerSecond", 2.0, 0.0, 50.0);
        THUNDER_GUST_BONUS_METRES_PER_SECOND = builder
                .comment("Extra static spatial variation amplitude during thunder.")
                .defineInRange("thunderGustBonusMetresPerSecond", 5.0, 0.0, 50.0);
        VERTICAL_TURBULENCE_FRACTION = builder
                .comment("Vertical component as a fraction of the spatial variation amplitude.")
                .defineInRange("verticalTurbulenceFraction", 0.04, 0.0, 1.0);
        ALTITUDE_WIND_MAXIMUM_MULTIPLIER = builder
                .comment("Maximum wind-speed multiplier reached at high altitude. 1 disables altitude strengthening.")
                .defineInRange("altitudeWindMaximumMultiplier", 1.55, 1.0, 10.0);
        builder.pop();

        builder.comment("Small long-range effects").push("earthEffects");
        ENABLE_CORIOLIS = builder.comment("Enable Coriolis deflection.").define("enableCoriolis", true);
        LATITUDE_DEGREES = builder.comment("Latitude used by the Coriolis model.")
                .defineInRange("latitudeDegrees", 45.0, -90.0, 90.0);
        ENABLE_SPIN_DRIFT = builder
                .comment("Enable a small right-hand-rifling spin drift. Disable for smoothbore-only worlds.")
                .define("enableSpinDrift", true);
        SPIN_DRIFT_FACTOR = builder
                .comment("Dimensionless spin-drift strength. The default is deliberately subtle.")
                .defineInRange("spinDriftFactor", 0.02, -0.25, 0.25);
        builder.pop();

        builder.pop();
        SPEC = builder.build();
    }

    private BallisticsConfig() {
    }
}
