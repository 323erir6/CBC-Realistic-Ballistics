# CBC Realistic Ballistics

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

`preserveOriginalImpactEnergy` selects whether penetration and impact force use CBC's saved original speed (`true`,
the default) or the physical speed remaining after the addon's aerodynamic flight (`false`).

Settings are stored in `serverconfig/cbc-realistic-ballistics-server.toml` for each world and are synchronized to
clients by NeoForge. The Create-style in-game configuration screen exposes every setting. Wind changes smoothly
with position but never with time: a fixed world seed, dimension, location and weather always produce the exact
same vector. Clear weather defaults to a 4 m/s prevailing wind with 3 m/s spatial variation; rain and thunder
add configurable static wind and turbulence. `seedSalt` can create a different reproducible wind map.

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

CBC projectile chunks are kept entity-ticking for the whole flight. The loader traces the imminent trajectory,
loads new corridor chunks before a fast shell can enter them, and immediately releases obsolete tickets or all
tickets when the projectile is removed. This works independently of CBC's delayed current-chunk loading option.
