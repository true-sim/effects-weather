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
  round the camera, its origin snapped to whole map texels across the fall (the texels on a grid fixed in the world,
  for any fall direction), drawn every frame and copied into a persistent image the hook and the accumulation read.
- Columns: each frame a compute pass takes every map texel's surface point to the world column under it, the highest
  per column by an atomic max into a transient buffer: the topmost surface in the column the snow reaches, the same
  whatever the camera does.
- Accumulation: a persistent RGBA32F image, toroidal over world columns: amount, the height of the surface the snow lies
  on, how long it has been covered. Updated by a compute pass each frame from the columns (weather/snowmap.slang).
- Part 2 (snow depth deformed by wheels and feet) waits for the engine's per-material displacement.

### weather-render (branch `clouds-render`)

The render techniques, on engine 0.192.0, using only its generic features (technique registry, frame graph, dynamic
textures, the shadow table's screen-space masks, the scene's lights). Flat worlds; a planet's maps are not drawn yet.

- **`WeatherGpu`**: a world's weather on the GPU. The near and far maps are dynamic RGBA16F textures of the scene's
  `Textures`, a tile uploaded when its version changes (runs of changed tiles along a row as one region, at most 24
  regions a frame: see the engine findings below); the profile atlas (`CloudTypes.bakeProfileAtlas`); and the frame's
  `CloudFrame` record (clouds.slang): the maps' placement around an anchor (the near map's centre on the ground, so GPU
  positions stay small), the types' layers and microphysics (art applied), the noise's placement carried by the same
  wind as the coverage, the sun and sky, the atmosphere, the lighting tunables and the flashes lit this frame.
- **`CloudNoise`**: the noise volumes generated once by compute (generate.slang), a port of `CloudNoise` operation for
  operation; `CloudNoiseGpuTest` compares every texel with `CloudNoiseTextures`: within one step of 255 (shape 4
  channels of 131,072 off by one step, detail 1 of 16,384, curl none).
- **Density** (clouds.slang, Schneider and Vos 2015, Schneider 2017): the weather map's coverage, type, rain and top;
  the type's profile from the atlas, the anvil widening the coverage near the top (`coverage^(1/(1+anvil))`), the
  upper part sheared downwind; the base shape (Perlin–Worley remapped by the low-frequency Worley fbm) stretched from
  its 5–95 % quantiles of the CPU reference (0.697–0.854) to [0, 1], so that the coverage remap's threshold
  `1 - coverage` leaves a cloud fraction about the coverage; eroded by the detail noise (wispy at the base, billowy
  above), distorted by curl; times the type's physical extinction (`σ = 3·LWC / 2ρr`).
- **Lighting**: the approximate Mie phase of the type's droplet diameter (Jendersie and d'Eon 2023; HG 0.75 for ice);
  a cone march towards the sun (steps doubling); Wrenninge's octaves (a, b, c = 0.5, N = 3; Hillaire 2016); the
  diffuse light deep inside as a two-stream slab (`T = 1 / (1 + ¾(1−g)τ)`, Bohren 1987) of the sun's and sky's light
  on the layer's top and the ground's from below (`σ_s·E/π`: an isotropic field of irradiance E has fluence 4E);
  Hillaire 2016's energy-conserving step; the sun through the atmosphere per sample (Chapman function after Schüler
  2012) and the aerial perspective at the clouds' transmittance-weighted depth.
- **Tiers** (`render.clouds`, `Clouds`): `volumetric` (fly-through: fine steps with detail from the camera for its first
  4 km, 160 coarse steps, 6 light steps), `layered` (default: two-speed march from outside, 96 steps, 4 light steps),
  `skybox` (2D: the coverage as the fraction of sky covered on each type's layer, lit as a two-stream slab; no noise,
  no march, no history), off. The ray-marched tiers march at half resolution with a jittered start, accumulate
  temporally (history reprojected by the clouds' mean depth, clamped to the 3 × 3 neighbourhood, less history while a
  flash lights them) and upsample joint-bilaterally on depth over the scene's colour (`scene·T + L`), at `Order.SKY`
  after the sky background. Every tunable is a kind config (`render.clouds.<kind>.*`) or a setting (`CloudSettings`).
- **Rain shafts**: below each type's base where the map has rain, Marshall–Palmer drops' extinction (`3.65·10⁻⁴·R^0.63`
  per metre at R mm/h, extinction efficiency 2), forward-scattering, lit by the sun under the clouds' Beer shadow.
- **Cloud shadows**: `CloudShadowMap`, Hillaire 2020's Beer shadow map (front depth, mean extinction, maximum optical
  depth), 512² over 32 km along the sun, texel-snapped, 24 steps a texel on the base shape (the 2D tier's of the
  coverage alone: no noise); `CloudShadowMask` turns it into the sun's screen-space shadow mask at each pixel, given to
  shading through the shadow table (the engine's mask path; see below).
- **Lightning**: `ActiveFlashes` picks the frame's flashes (intensity: a median first stroke's peak, 2·10⁹ cd by
  default, scaled by the peak current, times `Flicker.average` over the frame). The clouds glow from inside by the
  diffusion approximation (fluence `I·e^(−σ_eff·r)/(D·r)`, `D = 1/(3σ(1−g))`, `σ_eff = √(3σ_a'σ(1−g))` with a leak
  `σ_a'` setting; Dobashi et al. 2001's observation that multiple scattering dominates). `LightningLights` puts a point
  `LightSource` per flash on scene nodes (so `LightSync` lights the ground and everything else); `LightningBolts` draws
  the ground flashes' `BoltGeometry` as camera-facing strips of the channel's luminance (additive, at least 1.5 pixels
  wide with the intensity kept, fading above the cloud base).
- **Atmosphere**: `CloudLighting` is the interface the clouds need (sun direction and illuminance outside the
  atmosphere, the sky's light, the horizon's radiance, the medium's coefficients); `SceneSky` implements it from the
  scene's sun node over an exponential Earth atmosphere (Hillaire 2020's coefficients). effects-atmosphere's
  `atmosphere-render` branch had no commits when this was built: once its transmittance table, sky-view and aerial
  perspective volume land, an implementation reading them replaces `SceneSky`, and clouds.slang's analytic
  `sunTransmittance` / `aerialTransmittance` take their tables (follow-up issue).
- **Measured** (`CloudBenchmark`, TRUESIM_BENCHMARK=true, RX 9070 XT, 2026-11-08, 1920 x 1080, ULTRA weather maps,
  march at half resolution; medians of 60 frames after 40, ranges of 3 rounds; the frame includes forward shading of
  the ground, the test sky, the depth draw for the mask and the readback):

  | Scene | Tier | Clouds (march, temporal, composite / 2D) | Shadow map + mask | Frame (GPU) |
  |---|---|---|---|---|
  | fair cumulus, ground | volumetric | 4.12-4.15 ms | 0.08 ms | 4.84-4.87 ms |
  | fair cumulus, ground | layered | 2.00-2.02 ms | 0.08 ms | 2.71-2.75 ms |
  | fair cumulus, ground | skybox (2D) | 0.04 ms | 0.07-0.08 ms | 0.71-0.77 ms |
  | overcast, ground | volumetric | 2.14-2.16 ms | 0.07 ms | 2.81-2.85 ms |
  | overcast, ground | layered | 1.61-1.63 ms | 0.07 ms | 2.30-2.32 ms |
  | overcast, ground | skybox (2D) | 0.04 ms | 0.05 ms | 0.68-0.69 ms |
  | thunderstorm, ground | volumetric | 1.19-1.20 ms | 0.11 ms | 1.93-1.95 ms |
  | thunderstorm, ground | layered | 0.68 ms | 0.11 ms | 1.42-1.49 ms |
  | thunderstorm, ground | skybox (2D) | 0.04 ms | 0.09-0.10 ms | 0.75-0.77 ms |
  | flying through | volumetric | 7.32-7.39 ms | 0.08 ms | 8.02-8.07 ms |
  | flying through | layered | 4.30-4.33 ms | 0.08 ms | 4.98-5.00 ms |
  | flying through | skybox (2D) | 0.02-0.03 ms | 0.07-0.08 ms | 0.66-0.74 ms |

  The ray-marched tiers cost more than the report's estimates (0.6-1.5 ms layered, +1-2 ms fly-through): long marches
  of broken cloud (fair cumulus at the horizon, flying through a layer) take the fine steps with detail and a full
  light march at every sample. Not done yet, and the obvious next steps: the voxel cache's sun optical-depth volume in
  place of the light march near the camera, empty-space skipping, `sparse16` reprojection (a sixteenth of the pixels a
  frame), and fewer light steps far away. The storm is cheap here because the dense cell ends the march early.
- **Tests**: the GPU noise against the CPU reference; coverage follows the weather map (pixels over a brushed disc of
  altostratus 27,951 cd/m², over the clear map 461); the shadow darkens the ground under the cloud (7,774 → 0 cd/m² with
  the mask, the open ground unchanged; without the mask both alike); a flash lights a nimbostratus from inside at night
  (0 → 0.5 cd/m²) and the ground through the scene's light (0.21 → 6.3 cd/m²); a ground flash draws its bolt; every tier
  draws the same weather; CPU tests of the flash selection, the sky and the atmosphere.

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

### Engine features missing or limiting (found building weather-render on engine 0.192.0)

- **A per-light transmittance term in shading** (lights.slang's `lightIrradiance`: one shadow entry per light and
  point, `shadowFor` picking it). Cloud shadows go through the screen-space mask path instead (`ShadowTable.shadowMask`
  on the table's last entry), so while the scene's own shadows of the sun are on (`ShadowSpec` other than `NONE`) the
  clouds' shadow yields to them; fog, transparents, particles and GI hits are not on the screen grid and get none. The
  hook wanted: a light's transmittance multiplied into its irradiance wherever any path lights a point (a map or a
  function per light, e.g. `ILightTransmittance` in the shadow table), as volumetrics-design §7.2 proposes; the
  atmosphere's sun transmittance needs the same.
- **Persistent graph images are 2D only** (`GraphBuilder.persistent`: "A persistent image is 2D, one sample"): the noise
  volumes are dynamic 3D textures of `Textures` written by compute with a barrier of their own, ordered by a token buffer.
- **`TextureUpdates.record` lays every region's barriers, copy region and copy info on one `MemoryStack` frame**: about
  270 regions in a frame (the near and far maps' first upload, one region a tile) overflowed LWJGL's 64 KiB stack
  ("Out of stack space", the test JVM died) — the pitfall "thousands of barriers overflowed the memory stack" again.
  Worked around here by merging runs of tiles and uploading at most 24 regions a frame.
- **`GraphicsProgram` has no blend state**: additive and premultiplied draws (bolts, particles) make their pipelines
  with `Pipeline.graphics` outside the graph's program cache.
- **Readbacks of 3D images copy one slice** (`PassContext` copies with depth 1): the tests copy volumes with a shader.

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
