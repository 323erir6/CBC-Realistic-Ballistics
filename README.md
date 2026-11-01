# CBC Realistic Ballistics

## 2.0.4 - altitude-scaled thermal scorch gradient

- Scorched terrain now fades through a deterministic irregular transition instead of ending at a perfect circular edge.
- Ground bursts retain a smaller, dense burned footprint with a fully scorched inner core.
- Higher airbursts expand the thermal footprint while progressively lowering its block density.
- Radius noise and scorch selection are coordinate-stable across saves, and the maximum footprint remains inside the full pressure radius.

## 2.0.3 - underground primary shock cavity

- Buried nuclear and thermonuclear charges excavate a three-dimensional ellipsoidal cavity around their actual detonation point.
- A surface crater is created only when burial depth is within the profile's pressure-breakout depth.
- Deep underground detonations no longer draw a detached bowl on the terrain surface.
- Shallow buried cavities connect naturally into the surface crater instead of being sealed by a replacement floor block.

## 2.0.2 - terrain-following crater geometry

- Every crater column now finds the natural local ground beneath buildings, vegetation and water before changing terrain.
- Bowl depth is measured from that local surface, preventing crater floors from being placed into air above slopes, ravines or low ground.
- Raised rims also follow the local surface and build upward from actual earth instead of the detonation centre's height.
- Columns without natural ground in the search depth are left unfilled, so floating structures cannot acquire artificial terrain beneath them.

## 2.0.1 - closed green band and complete structural columns

- The shallow crater transition is forcibly surfaced with coarse dirt across one full rim width, eliminating the green inner band.
- Loaded chunks inside the thermal radius are prioritised before the distant glass-only area, so scorched terrain appears around the crater immediately.
- Structure destruction walks each exposed column from its highest block down to actual terrain instead of deriving depth from burst altitude.
- Roofs, suspended lamps and other upper remnants therefore remain part of the same demolition pass after their supports disappear.

## 2.0.0 - sealed crater rim and wide structural devastation

- Raised crater rims are rebuilt as a solid foundation-to-surface volume, removing buried structures and shallow cavities.
- The aftermath pass recognises coarse dirt, paths and related surface blocks as terrain and never re-excavates the completed crater profile.
- Scorched grass, farmland and paths become coarse dirt so the former green ring cannot regrow.
- Ordinary structures are fully demolished through half of the shockwave radius and receive heavy damage through 80%; only genuinely massive materials retain meaningful survival odds.
- Nuclear entity scanning remains bounded by the 1.5 ms shared wave budget introduced in 1.9.30.

## 1.9.30 - bounded primary damage wave

- The initial nuclear damage wave no longer walks every loaded entity in one server tick.
- Entity discovery and damage evaluation are incremental and share a strict 1.5 ms global budget across simultaneous detonations.
- Candidate UUIDs are de-duplicated, while a final bounded sweep preserves the complete damage radius and creative/spectator immunity.
- Projectile flight and the completed crater/aftermath model are unchanged.

## 1.9.29 - thermal flash and realistic structural hierarchy

- Foliage and fragile plants are read from the real world-surface heightmap and are easier to remove than trunks.
- Structures within 25% of the pressure radius are demolished above local ground reference; structures out to 45% receive heavy damage.
- Grass, paths, farmland, moss, podzol and similar surfaces become dirt throughout half of the pressure radius.
- The zero-depth crater edge is explicitly scorched, removing the surviving green/path ring.
- Loaded aftermath chunks are queued before crater excavation and up to sixteen are admitted per tick under a four-millisecond budget.
- Distant thermal work remains persistent and runs when chunks load naturally instead of force-generating the entire flash radius.

## 1.9.28 - full shockwave damage radius

- Living-entity damage now extends to each warhead's complete pressure/glass radius instead of stopping at the crater wall.
- The front travels at approximately 343 blocks per second, reaching nearby terrain immediately and the outer H-bomb radius in about five seconds.
- Quadratic attenuation still leaves survival possible only in the thin outer edge of the pressure radius.
- Large waves iterate loaded entities directly instead of scanning millions of empty entity sections.
- Creative and spectator players remain immune.

## 1.9.27 - reliable surface wave and faster crater formation

- Damage-wave distance is measured horizontally, so crater slopes and excavated floors cannot escape through vertical offset.
- Crater chunk processing catches entities that load after the travelling front has already passed them.
- Already-loaded crater chunks are processed continuously instead of being limited to one chunk per tick.
- The global crater budget is raised to 3,000 changes and 24,000 inspections with a strict seven-millisecond wall-clock cap.
- New chunk generation remains limited to one crater chunk per tick to avoid generation and autosave spikes.

## 1.9.26 - clean crater rim and travelling damage wave

- Blocks whose footprint intersects the crater edge are cleared instead of surviving between circular columns.
- Existing structures are removed from the complete raised-rim area before ejecta material is placed.
- Nuclear damage now travels with the original shockwave timing instead of applying everywhere instantly.
- Damage falls quadratically from 15,000 at ground zero to zero at the crater radius.
- Creative and spectator players are excluded from nuclear wave damage.

## 1.9.25 - clean crater volume and lethal core

- Crater floors now use an absolute blast profile instead of inheriting the height of destroyed surface structures.
- Crater columns start at the highest non-air block, removing leaves, flowers, ladders, fluids and other non-solid remnants.
- Every encountered block in the excavated crater volume is removed without drops except bedrock.
- Living entities inside the spherical crater radius receive 15,000 immediate damage.
- Projectile flight remains unchanged.

## 1.9.24 - H-bomb crater and explosion-load fix

- Mini H-bomb and H-bomb delayed TNT rings are replaced reliably by the continuous crater model.
- Nuclear terrain destruction remains drop-free, preventing item-entity storms after large detonations.
- The addon's duplicate scorch scanner is disabled while its immediate entity thermal pulse remains active.
- Crater, Sable and delayed-aftermath work now share stricter change, inspection and wall-clock budgets.
- Projectile flight, drag, lifetime and guidance are unchanged.

## 1.9.23 - persistent nuclear aftermath

- Nuclear detonations are saved per dimension with their type, centre, game time and physical radii.
- Chunks apply missed glass and pressure-wave damage when they load naturally, without force-loading the blast area.
- Each affected chunk stores one processed-history sequence instead of expanding the global save with chunk lists.
- Pressure follows inverse-square decay and loses additional energy when local terrain forces the wave to turn.
- Exposed structures receive deterministic partial damage based on pressure, shielding, hardness and blast resistance.
- Vanilla glass, glass-filled Copycats and glass-filled FramedBlocks remain eligible for delayed shattering.
- Delayed work is capped at 12,000 inspections and 700 block changes per server tick, with per-column shielding caches.
- Nuclear terrain and aftermath removals suppress item drops.

## 1.9.19 - persistence and model consistency

- Virtual projectiles in unloaded chunks are serialized into dimension SavedData and restored by UUID after a
  restart, including their remaining lifetime, velocity, fuze data and correction state.
- Temporary Sable plot/deserialization conflicts keep their holding record and retry later instead of dropping a
  construction from the current session.
- Supported projectile drag/calibre classification now prioritizes stable registry IDs over Java class names.
- The artificial `0.02 kg/m3` high-altitude density floor was removed, and physical impact speed is now the default.
- Programmed targets supply their real target Y to terminal planning. The static wind field now uses the public
  config seed, allowing Lua and web calculators to reproduce it without the private world seed.

## 1.9.15 - save-pause allocation fix

- Re-entrant transit registration now uses a deferred identity set instead of allocating a complete
  array of every tracked projectile on every level tick. This retains the `1.9.14` crash fix while
  removing the sustained allocation pressure that could turn automatic world-save garbage collection
  into a long visible pause.
- Projectiles leaving a level are removed from both transit registries immediately, reducing stale
  runtime references before a save. Flight physics, collision behaviour and projectile lifetime rules
  are unchanged.

## 1.9.14 - re-entrant transit registration fix

- High-altitude transit now iterates a stable per-tick projectile snapshot. Projectiles spawned or
  registered while another transit projectile is ticking are processed on the following tick instead
  of invalidating the live identity-set iterator and crashing the server with
  `ConcurrentModificationException`.
- Flight physics, collision behaviour and projectile lifetime rules are unchanged.

## 1.9.13 - stationary projectile cleanup

- Every CBC projectile and addon subclass is removed after remaining practically motionless for
  30 seconds, including APFSDS, 20 mm ammunition and compatibility projectiles excluded from the
  realistic-flight model.
- Movement immediately resets the cleanup timer. The timer is persisted across chunk/world saves
  and remains authoritative even when ordinary despawn or programmable-fuze persistence is enabled.
- `stationaryProjectileTimeoutSeconds` controls the delay and defaults to `30`.

## 1.9.12 - authoritative projectile lifetime

- `projectileDespawnTimeoutSeconds` is now the sole ordinary lifetime limit for every CBC/addon projectile family affected by the mod.
- CBC autocannon expiry and Military Supplement's shell-less, fuzed, dual-cannon, racked and torpedo expiry callbacks are neutralised without suppressing impacts, penetration, fuzes, overcharge failure or detonation.
- Cached addon lifetime fields are refreshed once per second and ordinary in-ground cleanup is deferred to the same configured hard timeout.

## 1.9.5 - direct-fire target residency

- A projectile carrying a programmed trajectory-correction target keeps only that exact target chunk resident. Fast APFSDS can therefore collide and detonate at an unloaded destination without restoring route-wide chunk generation.
- Addon lifetime counters are refreshed as a safety net for projectiles which rewrite their own lifetime after launch.

## 1.9.2 - conservative swept collision optimization

- Block/Sable candidate checks and CBC entity queries follow consecutive <=16-block axis steps,
  instead of scanning the full enclosing diagonal volume. Exact CBC penetration, entity damage,
  fluid impacts and fuze `onClip` callbacks are retained, including during empty high flight.
  Transit fuzes are allowed to run even when the terrain chunk under the projectile is absent.
- Broad-phase endpoints use CBC's actual `velocity + acceleration` ray. No stale pre-tick probe
  or tick-wide negative entity/Sable cache is reused. Other cannon projectiles are not globally excluded.
- The empty-ray fast path also works when `cbc_aero_velocity_fix` redirects CBC's clip calls;
  launch protection remains owned by that mod. Missing/incompatible Sable APIs fall back conservatively.
- Repeated force queries with identical tick/position/velocity reuse the existing result before
  vanilla drag work. The flight equations, launch speeds and calculator model are unchanged.
- Arrival-time re-prediction now shares the 1024-step per-level/per-tick prediction budget;
  it no longer runs up to 1024 extra steps for each arriving shell. Dimension ticking no longer
  removes other dimensions' flight plans.
- Unknown low-altitude chunks are NOT treated as air. If asynchronous warm-up has not supplied
  their data in time, the normal CBC terrain ray obtains it. This prioritizes hitting real obstacles
  over flying through them. Loading/generation, explosions and Sable simulation can still cause
  slow ticks: this release does not promise constant 20 TPS in ungenerated low-altitude terrain.

Build and geometry regression tests: `gradlew build`. The deterministic test checks 20,000 swept
paths, including world-edge coordinates, large hitboxes, zero motion and segment boundaries.
It is not an in-game benchmark or a substitute for testing the complete modpack.

NeoForge 21.1.235 / Minecraft 1.21.1 addon for Create 6.0.10 and Create: Big Cannons 5.11.7.

The mod applies a real SI-derived trajectory using Earth gravity, calibre/volume-derived projectile mass, atmospheric
density, temperature-dependent speed of sound, a transonic drag rise, seed-derived static wind, Coriolis
deflection and subtle right-hand spin drift. CBC's original scalar energy speed is tracked independently and can be
restored while its impact code runs. The addon never rewrites CBC's projectile property values; the optional physical
impact mode only lets CBC calculate velocity-dependent penetration from the actual remaining flight speed.

The standard CBC Big Cannon uses a nominal 875 mm calibre. CBC autocannons, rotary cannons and twin autocannons
use 20 mm, CBC AT's Heavy Autocannon uses 45 mm, and CBC Modern Warfare's Medium Cannon uses 155 mm. These
overrides replace visually enlarged projectile hitboxes and can be adjusted or disabled with
`useNominalCaliberOverrides`, `cbcBigCannonCaliberMetres`, `autocannonCaliberMetres`,
`heavyAutocannonCaliberMetres` and `mediumCannonCaliberMetres` in the Create config screen.

`preserveOriginalImpactEnergy` selects whether penetration and impact force use CBC's saved original speed (`true`)
or the physical speed remaining after the addon's aerodynamic flight (`false`, the default).

Settings are stored in `serverconfig/cbc-realistic-ballistics-server.toml` for each world and are synchronized to
clients by NeoForge. The Create-style in-game configuration screen exposes every setting. Wind changes smoothly
with position but never with time: a fixed `seedSalt`, dimension, location and weather always produce the exact
same vector. The config seed is authoritative so Lua and web calculators do not need the private Minecraft world
seed. Clear weather defaults to a 4 m/s prevailing wind with 3 m/s spatial variation; rain and thunder add
configurable static wind and turbulence.

`disableProjectileDespawn` is enabled by default and prevents lifetime and ordinary in-ground despawn for CBC projectiles and
addon subclasses. It does not cancel impact, detonation or explicit removal and does not replace the independent
persistence flag from CBC Programmable Fuzes, so both mods remain compatible.

The bundled browser-side model in `Lua/Artillery_all/BALLISTIC_CALC_WEB` uses the same 64-bit seed hash, static
wind field, atmosphere, Mach drag, Coriolis and spin drift. Its projectile selector is generated from all CBC,
CBC Military Supplement, CBC Modern Warfare, CBC AT and CBC AT Fix projectiles present in modpack 6.8.3.
Selecting a cannon filters this catalogue to ammunition that the chosen cannon family can actually load.

When CBC Advanced Technology is installed, this addon automatically disables CBC AT's alternative projectile
physics rewrite at server startup. CBC AT's cannons and content remain available, but standard CBC muzzle velocity
and CBC Realistic Ballistics trajectory control are used, preventing both flight models from modifying the same shot.

With `highAltitudeTransitEnabled` enabled, every projectile keeps its ballistic simulation running through unloaded
and ungenerated chunks from its first tick. Empty flight creates no route tickets. An incremental, budgeted planner
discovers at most the final ten trajectory chunks for a genuine arcing flight. They are warmed ahead of impact through one global request stream,
one chunk at a time, then released; loaded terrain, entities and Sable constructs therefore retain collision without
generating, unloading and synchronously saving a moving window of route chunks. Short direct-fire trajectories do
not create terrain tickets around their synthetic fall plane. The terminal window for arcing shells reloads
already generated terrain instead of causing a generation burst. Prediction never runs a complete multi-thousand-
tick trajectory inside one server tick. Planner tickets use Minecraft's asynchronous scheduler;
an actual terrain collision ray can still synchronously obtain data when the prediction is late.

During unloaded high-altitude transit, only the main world's block raycast is suppressed. Sable's transformed
voxel-shape raycast and CBC's entity sweep remain active. Projectiles therefore hit real construction blocks rather
than a construction bounding box, use ordinary CBC fuze arming/detonation and penetration, and damage entities as
usual without loading the surrounding main-world chunks. In loaded terrain a cheap section test is followed by an
exact loaded-only block/fluid ray. Proven-empty block rays are skipped, but CBC's collision pipeline still runs
its fuze callbacks and live entity sweep every tick. It owns all actual impact and trajectory-change behavior.

The release jar also contains a low-overhead slow-tick profiler. It begins stack sampling only after a server tick
has exceeded 40 ms and writes reports for ticks of 50 ms or longer to
`logs/cbc-realistic-ballistics-performance.log`. Reports separate projectile ticking, force calculation, collision
checks, virtual transit, trajectory planning and chunk-ticket work, and include sampled server-thread hot stacks.
File output runs on a background daemon thread.
