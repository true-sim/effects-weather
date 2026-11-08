# effects-weather: design

The design report for sky, atmosphere, clouds and volumetric fog is
[volumetrics-design.md](https://github.com/true-sim/effects-atmosphere/blob/main/docs/volumetrics-design.md) in
effects-atmosphere (engine issues [#418](https://github.com/true-sim/engine/issues/418) and
[#419](https://github.com/true-sim/engine/issues/419)). This repository holds its weather, clouds, rain and lightning
(report §5, §6.5, §7 and the user's decisions: physically fitted defaults with art overrides off by default; ground to
orbit; presets with transitions, brushes and a deterministic storm life cycle; lightning complete). Sky, stars and fog
are in effects-atmosphere, which this repository depends on (`Quality`, `Planet`, time).

## Principles

- **Engine-free data and simulation**; render techniques later as technique-registry plugins (`render.clouds`, cloud
  shadows, lightning), using only generic engine features (below).
- **One weather, every tier.** The weather model drives the 2D, layered and volumetric cloud tiers alike: they all read
  the same weather map channels (coverage, type, precipitation, top height). The cheap tiers never need the expensive
  data: a 2D sky reads coverage and type, not the noise volumes or the voxel cache.
- **Everything is a setting**, with `defaults()`/`forQuality(Quality)` and `with…` overrides.
- **Deterministic** (fdlibm math, xoshiro and PCG randomness, fixed steps, fixed slot order) and **allocation-free per
  step**.
- **Scene data**: the weather's settings and node-placed brushes are D46 components (`truesim.weather`,
  `truesim.weather.brush`).

## What is built (branch `foundation`)

### weather-clouds

- `CloudType`: stratus, stratocumulus, cumulus, towering cumulus, cumulonimbus, altocumulus, altostratus,
  nimbostratus, cirrus; the type channel encodes the ordinal (append only).
- `CloudProfile`: layer base and top, the vertical density profile and anvil widening defined exactly (class doc),
  erosion, detail scale, extinction from water content and droplet size, droplet diameter for the approximate-Mie phase,
  ice. `CloudTypes.physical()` holds typical values (WMO étages, Pruppacher and Klett, Miles et al. 2000) to be replaced
  by values fitted to the path tracer (report §10.3); `bakeProfileAtlas` and `bakeLayers` give the GPU tables.
- `CloudArt`: density, erosion, darkness, powder, silver lining, coverage offset; `enabled = false` by default, and then
  every effective value is the physical one.
- `CloudNoise`/`CloudNoiseTextures`: tileable Perlin (Perlin 2002), Worley F1 (inverted), their fbm, Perlin–Worley and
  curl (Schneider 2015), on the PCG hash (`common-math` `Hash`) so a Slang port draws the same lattice bit for bit;
  floats within 1e-5, RGBA8 textures within one step. Golden fingerprints pin the textures.

### weather-lightning

- `LightningEvents`: a ring of flashes (time, type, origin in the cloud, end on the ground or in the cloud, signed peak
  current, strokes, seed, storm cell); consumers read what is new since their last id.
- `Flicker`: a flash's luminosity from its seed: stroke trains with exponential decay and continuing current for ground
  flashes (Rakov and Uman's statistics), pulse trains for intracloud flashes; `average` integrates a frame exactly, so
  1–2 ms strokes never alias at 60 Hz.
- `BoltGeometry`: midpoint displacement with branches, from the endpoints and the seed, into reusable buffers.
- `Thunder`: first arrival from the nearest point of the channel, rumble until the farthest, at the speed of sound for
  the air temperature.

### weather-model

- `WeatherDomain`: a flat world (x east, y up, z south) or a planet (centred at the origin, pole +y). The near maps are
  flat grids in a plane: the ground itself, or on a planet the tangent plane at an anchor near the camera (gnomonic),
  re-anchored when the camera moves past a set distance.
- `WeatherField`: the weather at a ground point: the preset's noise coverage carried by the wind, then storm cells (core
  and anvil), then brushes in order.
- `WeatherGrid`: the near map (512² over 64 km at HIGH) and the far map (128² over 400 km) on a flat world, toroidal,
  refreshed in tiles with a budget (new tiles first, then in turn), with per-tile versions for uploads.
  `GlobalWeatherGrid`: six cube faces covering a planet, for the far field and orbit.
- `WeatherPreset` (clear, fair, scattered, broken, overcast, altostratus, rain, storm) and `WeatherState` (smoothstep
  transitions from whatever is in effect; types switch halfway).
- `WeatherBrush`/`Brushes`: add, set or clear, with soft edges, drift and lifetime; or on scene nodes.
- `WindProfile`: layers by altitude, meteorological directions, interpolated as vectors.
- `StormSimulation`: cells form at the preset's rate around the focus (or by `spawn`), grow (towering cumulus →
  cumulonimbus), mature (heaviest rain, peak flash rate, anvil spreading downwind), dissipate, and grow daughter cells;
  they drift with the steering-level wind. Durations, heights, sizes, rain and flash rates follow the Thunderstorm
  Project's single cell (Byers and Braham 1949). Flashes: intracloud vs ground ratio, positive fraction, log-normal peak
  currents (Berger et al. 1975), strokes per flash. A golden hash of a two-hour, multi-cell scenario pins determinism
  across machines (CI checks it on Linux).
- `Weather`: the facade (fixed steps, sampling for gameplay: near map, far or global map, else the field).

Measured (`WeatherBenchmark`, desktop CPU, one storm preset with six cells): a step costs 0.14 ms (LOW), 0.9 ms
(MEDIUM), 1.6 ms (HIGH), 2.5 ms (ULTRA) median, almost all of it refreshing map tiles (≈ 0.3 µs per texel); the storm
step itself is microseconds. Steps run once per game second (0.5 s at ULTRA), not per frame. A GPU renderer evaluates
the same field in a compute pass from the small inputs (preset parameters, cells, brushes) instead; the CPU maps serve
gameplay, servers and the cheap tiers. The CPU reference of the 128³ shape noise takes 5 s (the GPU generator replaces
it at startup).

### weather-snow ([#4](https://github.com/true-sim/effects-weather/issues/4), part 1)

The first render plugin: snow lying on surfaces as the engine's surface shading hook `weather.snow` (engine 0.192.0),
so every way of shading (forward, the visibility buffer, transparency, ray and probe hits) sees it.

- `SnowParameters` (the hook's record): the authored fields (coverage, noise scale and sharpness, slope start and end,
  albedo, roughness, normal blend, fall direction, snowfall, melt) come from the scene (`ShadingHookComponent`,
  `SnowContent`); `SimpleSnow` adds the occlusion map's slot and transform and the accumulation's each frame.
- Occlusion: a depth-only `SceneDraw` of a render view of its own, orthographic along the fall direction over a square
  round the camera, snapped to the accumulation's texels (world-anchored), drawn every frame and copied into a
  persistent image the hook and the accumulation read.
- Accumulation: a persistent RGBA32F image, toroidal over world columns: amount, the height of the surface the snow lies
  on, how long it has been covered. Updated by a compute pass each frame (weather/snowmap.slang).
- Part 2 (snow depth deformed by wheels and feet) waits for the engine's per-material displacement.

## Tiers and quality presets

GPU costs are the report's estimates for the RX 9070 XT at 1080p (est.), to be replaced by the plugins' benchmarks.

| Component | Tiers (cheapest last) | Expected cost |
|---|---|---|
| Clouds (`render.clouds`, `CloudsConfig.Technique`) | **volumetric**: layered + voxel cache around the camera (fly-through) · **layered**: ray-marched 2.5D layers (Nubis) · **skybox**: 2D layer on the sky dome or billboards from coverage and type · off | +1–2 ms est. and ≈ 88 MB inside clouds · 0.6–1.5 ms est. (2–4 ms in a storm) · ≈ 0.05 ms est. · 0 |
| Cloud resolution and temporal scheme | quarter + temporal · sparse 1/16 · full (all settings, defaults from benchmarks) | 1× · ≈ 0.25× · ≈ 4× |
| Cirrus | volume shell · 2D layer · off | ≈ 0.1 ms est. · < 0.05 ms est. |
| Cloud shadows | Beer shadow map · 2D coverage projection · off | 0.1–0.2 ms est. · < 0.05 ms est. |
| Weather simulation (`WeatherConfig.Simulation`) | **storms**: life cycle + lightning · **presets**: transitions, wind drift, brushes · **static**: the preset only | CPU per step: see above; the storm part is microseconds |
| Lightning (`LightningConfig.Tier`) | **full**: in-cloud glow, scene light, visible branching bolts, ground strikes · **flashes**: glow and scene light only · off | bolt geometry: a few hundred points per flash on the CPU (not yet measured); glow is a segment distance per cloud sample while a flash is lit |
| Rain | rain shafts as media below cloud bases (V9) · particles only · off | later |

| Preset | Clouds | Noise | Weather | Maps (CPU) | Storm cells | Lightning |
|---|---|---|---|---|---|---|
| LOW | skybox (2D) | 32³ shape, 16³ detail | presets | 128² near over 32 km, 2 tiles/step | 8 | flashes |
| MEDIUM | layered | 64³, 32³ | storms | 256² over 64 km, 2 tiles/step | 24 | full, 6 levels |
| HIGH | layered | 128³, 32³ | storms | 512² over 64 km, 4 tiles/step | 64 | full, 7 levels |
| ULTRA | volumetric | 128³, 32³, 5 octaves | storms, 0.5 s steps | 1024² over 150 km, 4 tiles/step | 128 | full, 8 levels |

## Generic engine features the render plugins need

Per the user's rule, each is a generic engine feature, not a weather special case. Checked against engine
`origin/main` 2d5a2a8 by searching the sources; "unclear" means not found by name, to be confirmed by the engine's
owners.

| Feature | Used for | In the engine today |
|---|---|---|
| Technique slots, kinds, `@Tunable` records | `render.clouds` and its tiers, cloud shadows, lightning | yes (`engine-settings`) |
| CPU → GPU region updates of persistent 2D and 2D-array/cube textures, budgeted per frame | weather map tiles (near, far, the global cube) for the CPU path and the cheap tiers | partial: `graph.Texture` is persistent 2D, filled once from `float[]`; region updates not found |
| CPU → GPU updates of small persistent buffers per frame | storm cells, brushes, preset parameters (the GPU weather compute's inputs), lightning flashes, the profile atlas | unclear |
| Persistent 3D images written by compute, kept across frames, with history | noise volumes (generated once), voxel cache, cloud temporal history | `ImageDesc` has 3D images; persistence and histories per frame graph v2: to confirm |
| Per-light transmittance in the shadow table | cloud shadows on the sun (Beer shadow map) | not present (#120) |
| Short-lived lights with an intensity curve, from data | the lightning flash's scene light | `Lights` exist; a light driven by a curve each frame is ordinary use |
| A ribbon or beam renderer fed with a polyline (or a segment list) from the CPU | visible branching bolts | scene-effects has ribbons (particles in spawn order) and beams (two points, noise subdivided), see below |
| Audio one-shots scheduled at a delay and position | thunder | scene audio (`scene-audio` on main) plays sounds on nodes; a scheduled start is to confirm |
| Fixed-step simulation in the game loop | `Weather.advance` | the app's own loop; scene has fixed steps for animators (A5) |

### Bolt visuals and scene-effects

scene-effects (milestone 7, `docs/effects.md`) plans ribbons, trails and **beams**: "analytic segments between two
points (an emitter's two user parameters or two nodes), subdivided with noise. No simulation." That is the right home
for drawing bolts as emissive strips with bloom, because it already has the renderer kinds, sorting, blending and
lighting. Two gaps to raise there: a beam is one straight segment with noise, while a bolt is a branching polyline
tree with per-segment brightness (`BoltGeometry`), so it needs either a beam per segment (cheap, up to ~500 segments)
or a "polyline beam" fed by points from data; and a bolt lasts one flash (≈ 0.2–0.5 s) with its brightness from
`Flicker`, so the emitter needs a per-frame intensity parameter. The in-cloud glow is a cloud-renderer term, not an
effect.

## Later stages (from the report's plan)

V5 layered clouds (the first render plugin here: weather map on the GPU, profile atlas, noise generators matching
`CloudNoise`, far march, quarter-res temporal, the 2D tier) → V6 cloud lighting, octaves fitted to the path tracer,
Beer shadow map → V7 fly-through voxel cache → V8 storms (anvil tuning, lightning glow, the flash light, bolts) → V9
cirrus, rain shafts → V10 authored voxel clouds. Fluid-simulated hero clouds and visible billowing are user choices to
add as tiers of cloud motion.

## Open points

- CI resolves `com.truesim.effects:atmosphere-ephemeris:0.1.0` from GitHub Packages: effects-atmosphere must be
  released before this repository's CI can pass.
- Per-cell rain rates are a fraction of the configured maximum (100 mm/h); rain shafts, virga and snow are later
  stages.
- On a planet the wind's drift of the coverage pattern uses the focus's tangent frame (exact locally; the far field's
  pattern drifts approximately).
- A late-joining multiplayer peer needs the storm state; `stateHash` checks agreement, but a snapshot/restore API is not
  built yet.
