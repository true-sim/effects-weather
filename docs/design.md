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

The render techniques, on engine 0.193.0, using only its generic features (technique registry, frame graph, dynamic
textures, the shadow table's screen-space masks, the scene's lights). Flat worlds; a planet's maps are not drawn yet.

- **`WeatherGpu`**: a world's weather on the GPU. The near and far maps are `WeatherField` evaluated on the GPU every
  frame (field.slang, from the field's small inputs: the preset in effect, the wind's offset, the storm cells, the
  brushes; `WeatherMapGpuTest`: every texel within 0.0044 of the CPU's field at the frame's time). The CPU's maps,
  refreshed a few tiles a step, stay the gameplay's: uploaded to the GPU they showed anything moving (cells, the wind's
  drift) as seams at tile boundaries, and types popping as tiles came in. Each texel holds a mix of two cloud types and
  the second's weight, continuous wherever the weather changes continuously: a preset transition's types blend by its
  smoothstep (the CPU switches halfway), a storm cell becomes a cumulonimbus over 3 minutes and takes over from the
  preset's type as its core outgrows the preset's cover, brushes by their falloff. The clouds add the four texels'
  mixes around a point and blend the two heaviest types' densities, layers and droplets, and the near map hands over
  to the far one across its outer tenth. The profile atlas (`CloudTypes.bakeProfileAtlas`); and the frame's
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
  above), distorted by curl; times the type's extinction at density 1: its physical extinction (`σ = 3·LWC / 2ρr`, the
  layer's mean) over the layer's mean density at full cover (half the profile's mean), so a cloud's column has its
  water's optical depth (before, every cloud had about half of it).
- **Lighting**: the approximate Mie phase of the type's droplet diameter (Jendersie and d'Eon 2023; HG 0.75 for ice);
  a cone march towards the sun (steps doubling); Wrenninge's octaves (a, b, c = 0.5, N = 3; Hillaire 2016); the
  diffuse light deep inside as a two-stream slab (`T = 1 / (1 + ¾(1−g)τ)`, Bohren 1987) of the sun's and sky's light
  on the layer's top and the ground's from below (`σ_s·E/π`: an isotropic field of irradiance E has fluence 4E), the
  optical depths to the top and base at the type's physical extinction (a wisp at a thick cloud's base is as deep below
  its top as the cloud is); after the cone, the rest of the way to the top at the same extinction (Schneider's long
  step); a nimbostratus deck's base from below at noon 1,149 cd/m² against sunlit tops of 14,851 (`aThickRainCloudIs
  DarkFromBelow`);
  Hillaire 2016's energy-conserving step; the sun through the atmosphere per sample (Chapman function after Schüler
  2012) and the aerial perspective at the clouds' transmittance-weighted depth.
- **Tiers** (`render.clouds`, `Clouds`): `volumetric` (fly-through: fine steps with detail from the camera for its first
  4 km, 160 coarse steps, 6 light steps), `layered` (default: two-speed march from outside, 96 steps, 4 light steps),
  `skybox` (2D: the coverage as the fraction of sky covered on each type's layer, lit as a two-stream slab; no noise,
  no march, no history), off. Steps grow with the distance (2 % of it, at least 30 m, at most the slab's share of the
  coarse steps) out to 200 km (the far map). The ray-marched tiers march at half resolution with a jittered start, accumulate
  temporally (history reprojected by the clouds' mean depth, clamped to the 3 × 3 neighbourhood, less history while a
  flash lights them) and upsample joint-bilaterally on depth over the scene's colour (`scene·T + L`), at `Order.SKY`
  after the sky background. Every tunable is a kind config (`render.clouds.<kind>.*`) or a setting (`CloudSettings`).
- **Rain shafts**: below each type's base where the map has rain, Marshall–Palmer drops' extinction (`3.65·10⁻⁴·R^0.63`
  per metre at R mm/h, extinction efficiency 2), forward-scattering, lit by the sun under the clouds' Beer shadow.
- **Cloud shadows**: `CloudShadowMap`, Hillaire 2020's Beer shadow map (front depth, mean extinction, maximum optical
  depth), 512² over 32 km along the sun, texel-snapped, 24 steps a texel on the base shape (the 2D tier's of the
  coverage alone: no noise), and beyond it the coverage projection (the map's cover where the ray to the sun crosses
  2 km); `CloudShadowMask` turns it into the sun's screen-space shadow mask at each pixel, given to
  shading through the shadow table (the engine's mask path; see below).
- **Lightning**: `ActiveFlashes` picks the frame's flashes (intensity: a median first stroke's peak, 2·10¹⁰ cd by
  default (Guo and Krider 1982: about 2·10⁶ W/m of optical power over a 5 km channel), scaled by the peak current, times `Flicker.average` over the frame). The clouds glow from inside by the
  diffusion approximation (fluence `I·e^(−σ_eff·r)/(D·r)`, `D = 1/(3σ(1−g))`, `σ_eff = √(3σ_a'σ(1−g))` with a leak
  `σ_a'` setting; Dobashi et al. 2001's observation that multiple scattering dominates). `LightningLights` puts a point
  `LightSource` per flash on scene nodes (so `LightSync` lights the ground and everything else); `LightningBolts` draws
  the ground flashes' `BoltGeometry` as camera-facing strips of the channel's luminance (additive, at least 1.5 pixels
  wide with the intensity kept, fading above the cloud base).
- **Atmosphere**: `CloudLighting` is the interface the clouds need (sun direction and illuminance outside the
  atmosphere, the sky's light, the horizon's radiance, the medium's coefficients, the scene units).
  `AtmosphereLighting` implements it with effects-atmosphere 0.3.0's `Atmosphere` (Hillaire 2020): its sun, its
  media's coefficients, its exposure (`sceneUnits`: the clouds' physical values, the flashes' candela among them, take
  the same factor), its sky's mean radiance where its fog read it back; the pictures use its `AtmosphereSky`,
  `AerialPerspective` (the media hook on surfaces) and `SkyCapture` (the environment, dimmed by the clouds' diffused
  light: `WeatherGpu.skyBelowClouds`). `SceneSky` stays for scenes without it (a scene sun node over an exponential
  atmosphere). Since the second round the clouds read effects-atmosphere's tables where it lights them (below).
- **Measured** (`CloudBenchmark`, TRUESIM_BENCHMARK=true, RX 9070 XT, 2026-11-08, 1920 x 1080, ULTRA weather maps,
  march at half resolution; medians of 60 frames after 40, ranges of 3 rounds; the frame includes forward shading of
  the ground with the environment's light, the test sky, the GPU weather maps (1024² and 128² a frame), the depth draw
  for the mask and the readback):

  | Scene | Tier | Clouds (march, temporal, composite / 2D) | Shadow map + mask | Frame (GPU) |
  |---|---|---|---|---|
  | fair cumulus, ground | volumetric | 6.36-6.38 ms | 0.15 ms | 8.42-8.43 ms |
  | fair cumulus, ground | layered | 3.32-3.35 ms | 0.15 ms | 5.38-5.42 ms |
  | fair cumulus, ground | skybox (2D) | 0.06 ms | 0.15 ms | 2.11-2.13 ms |
  | overcast, ground | volumetric | 3.27-3.31 ms | 0.14 ms | 5.34-5.38 ms |
  | overcast, ground | layered | 2.31-2.32 ms | 0.14 ms | 4.38-4.39 ms |
  | overcast, ground | skybox (2D) | 0.06 ms | 0.13 ms | 2.08-2.09 ms |
  | thunderstorm, ground | volumetric | 4.91 ms | 0.23 ms | 7.05-7.06 ms |
  | thunderstorm, ground | layered | 2.66 ms | 0.23 ms | 4.81-4.82 ms |
  | thunderstorm, ground | skybox (2D) | 0.06 ms | 0.23 ms | 2.19-2.20 ms |
  | flying through | volumetric | 7.50-7.52 ms | 0.15 ms | 9.63-9.65 ms |
  | flying through | layered | 5.94-5.96 ms | 0.15 ms | 8.02-8.07 ms |
  | flying through | skybox (2D) | 0.04 ms | 0.15 ms | 2.10-2.11 ms |

  More than the first version of this PR (layered fair cumulus 2.0 ms, flying through 4.3): steps now grow with the
  distance from 30 m (fine near, as the stipple of distant thin clouds needed), the march reaches 200 km (the far map:
  decks reach the horizon), each sample blends two types and the light march's tail reads the Beer map. Above the
  report's estimates for the ray-marched tiers (0.6-1.5 ms layered); the next steps are #8's: the voxel cache's sun
  optical-depth volume in place of the cone near the camera, empty-space skipping, `sparse16` reprojection, fewer
  light steps far away.
- **Tests**: the GPU noise against the CPU reference; the GPU's weather map is the field at the frame's time
  everywhere (`WeatherMapGpuTest`, the seams' cause gone); coverage follows the weather map (pixels over a brushed disc
  of altostratus 30,807 cd/m², over the clear map 1,623 through 12 km of air); the shadow darkens the ground under the
  cloud (9,026 → 1,539 cd/m² with the mask: the sky's light remains; without the mask both alike); a thick rain cloud is
  dark from below (a nimbostratus deck's base 1,423 cd/m² at noon, the clear zenith 1,504, sunlit tops 27,318); a change
  of cloud type is continuous (scattered cumulus to broken stratocumulus to overcast stratus, a frame a second, calm:
  the largest frame-to-frame change 2.2 times the median; before the blending, 6 to 13 times, at the halfway switch and
  where a fading type's layer was cut off); a flash lights a nimbostratus from inside at night (0 → 4.0 cd/m²) and the
  ground through the scene's light (1.9 → 64 cd/m²); a ground flash draws its bolt; every tier draws the same weather;
  effects-atmosphere lights the clouds in its scene units; CPU tests of the flash selection, the sky and the atmosphere.
- **Films** (`CloudShots`, TRUESIM_SCREENSHOTS=true): pops per film (`render/tools/popmetric.py`: a 32 × 32 block whose
  frame-to-frame change exceeds 4 times its own median over the 8 frame pairs around it and 0.03): fair to overcast over
  10 minutes 802 before, 33 after; a storm rolling in, largest block change 0.996 before (whole regions switching),
  0.33 after.

### weather-render, second round (branch `clouds-round2`)

Six fixes, each measured (tests that fail on the first round's code: run on its tree with the same test scene).

- **Anvils** ([#6](https://github.com/true-sim/effects-weather/pull/6) drew a mature cell's anvil as cirrus, optical
  depth 1-6): a cumulonimbus's anvil is a thick ice cloud. The GPU field writes each cell's anvil as a channel of its
  own (the types map's blue; its alpha the cover of everything but the anvils; the coverage channel stays
  `WeatherField`'s, discs included, for gameplay): a plume leaving the core along the wind at the top, from the core's
  width to the anvil's, rounded at its far end, thinning downwind. The clouds draw it as ice under the tropopause (the
  cells' tops): `render.clouds.anvil.ice` 0.25 g/m³ of crystals of `render.clouds.anvil.diameter` 60 µm
  (`σ = 3·IWC / 2ρ_ice r`: 13.6 per km; Heymsfield's anvils hold tenths of a gram), `thickness` 2.5 km at full cover,
  a sheet whose base and edges the shape breaks (not puffs), an overshooting dome of `overshoot` 1.2 km over the
  updraft core; the phase mixes ice (HG 0.75) and droplets by their shares of the extinction. The storm model's anvil
  spreads over the first 20 minutes of maturity (`StormSimulation.ANVIL_SPREAD`; before, over the whole mature stage, so
  a cell 40 minutes old had none to speak of; the golden hash changed with it). `StormShapeTest`: a 40-minute cell's
  column from below, sun overhead, in the Beer shadow map: anvil 1.5-3 core radii downwind 56.6 (first round 6.2),
  core 680.
- **Storm cells**: towering cumulus and cumulonimbus fill their core's disc (the cover's square root) and narrow over
  their top fifth into rounded crowns; their shape also reads the base shape's Worley billows at
  `render.clouds.billowScale` (2.5) times its frequency, stretched upwards (turrets); bases stay flat (the profile's
  ramp); the tower's own top widening (`anvilSpread`) is 0.6 instead of 1.5 now that the anvil is drawn on its own.
  Rain shafts: the rain at a point left the base upwind, `render.rain.slant` 0.25 m per metre fallen plus
  `render.rain.curve` 0.35 more per fallen fraction of the base (the shaft slants and curves downwind); a fraction
  `render.rain.evaporation` 0.35 evaporates by the ground (thinner downwards) and the lighter edge first
  (`render.rain.taper` 0.3: narrower downwards); it falls in curtains (the base shape at the base's height, carried
  with the rain). The shot scripts ran storms by step count, half the minutes at ULTRA's half-second steps: now by
  weather time (`CloudShots.run`).
- **The bright line at the horizon below a deck**: the deck was flat, so it ended 0.23 degrees above the horizontal at
  the march's 200 km while effects-atmosphere's sky is a planet's (its horizon 0.45 degrees down from 200 m): rays in
  between met neither cloud nor ground and showed the bright horizon sky. The clouds now lie on the planet: a sample's
  altitude is `y + d²/2R` from the camera's ground point, the march's span the curved shell's; a deck reaches down to
  the horizon. And the upsampling: a march texel straddling the horizon marched past a ground pixel's surface (to the
  sky beside it), so its clouds smeared onto the ground as bright streaks; clouds behind a pixel's surface are now
  dropped for it. `CloudFixesTest.theHorizonUnderADeckHasNoBrightLine` (from 200 m, 0.025 degrees a row, the
  brightest row near the horizon over the brighter of the rows 0.3 degrees above and below it): 1.07 (first round
  2.05).
- **Dusk** ([#9](https://github.com/true-sim/effects-weather/issues/9)): with effects-atmosphere (`CloudLighting.tables`,
  `AtmosphereLighting`) every sample's sunlight is its transmittance table's with the planet's shadow
  (`transmittanceToLight` at the sample's planet position), the ground's too; the air in front of the clouds is its
  aerial perspective volume at their mean depth (as its media hook reads it), continued past the volume's range by
  its own integration (`segment`); and the clouds' sky light is its sky-view table integrated over the hemisphere
  (weather/skylight.slang, read back into the next frames: `WeatherGpu.skyLight`), by day and in twilight. Before, the
  sky light was an estimate that is zero once the sun sets, so clouds were lit by the red sun alone and black
  elsewhere, and AgX/ACES turned the red into saturated pink. The ambient's "no deeper than towards the sun" (a tower's
  side) applies only while the sun is up. `SceneSky` keeps the analytic Chapman path. `CloudFixesTest.cloudsAtDuskHave
  TheAtmospheresColours`: sunlit cirrus at 9 km, sun 2 degrees down, 0.013 in chromaticity from the mix of the table's
  sunlight and the sky light; altocumulus overhead in the planet's shadow (sun 4 degrees down) 0.053 from the sky
  light's colour (first round: no sky light at all). The 2D tier stays analytic.
- **The test scenes**: the ground grassland's albedo (0.20, 0.22, 0.12); the environment the sky's light under the
  clouds, uniform (`WeatherGpu.skyBelowClouds`, its clear part the sky light above, its cloudy part the sunlight on the
  tops through the atmosphere diffused through them: a captured clear sky only dimmed lit the shade under a storm
  navy); the pictures exposed as an auto exposure would (effects-atmosphere's `AtmosphereExposure`: the EV100 of the
  frame's log-average luminance, eased over a second in films), white balanced, tone mapped by the engine's display
  transform with AgX (`display.toneCurve`, engine 0.193.0), the PNGs its output.
- **Time-lapse films**: the history was reprojected by the camera alone while the clouds moved with the wind (tens of
  metres a frame in a film of seconds a frame: streaks). The temporal pass now reads the history where the clouds were
  (`WeatherGpu.motion`: the wind's drift and the noise's evolution over the frame), and the history fades with the
  weather time a frame spans (`historySeconds` 4: `blend · exp(−dt / 4 s)`, nothing at 60 Hz). A frame spanning so much
  weather that the history counts for less than half marches every pixel (no interleave: its pixels not marched
  would show the blocks' spatial estimate as stair-steps).
- **The cumulus to cumulonimbus film** (the first round's `typeTransitions`): the cell did not vanish; it was spawned
  14 km west of a camera looking west and the westerlies (14 m/s) carried it over the camera and out of the top of the
  frame within 18 minutes. `CloudShots.cumulusToCumulonimbus` spawns it 38 km west-northwest and turns the camera to
  keep it in view (its log prints the cell's place, top, anvil and type a frame: from 38 km to 13 km, towering cumulus
  to cumulonimbus at 16 minutes, the anvil spreading from 22). The ground "flipping between grey and navy": the shade
  under the clouds was lit by the captured clear sky only dimmed (navy); now the sky's light under the clouds,
  uniform. In the time-lapse (6 s of weather a frame) the ground's large changes are cloud shadows moving 84 m a
  frame across a view a hundred metres wide from 30 m (`film-transition-ground`); in real time
  (`CloudShots.groundRealTime`, a mature storm and fair cumulus, 30 frames a second) the ground changes 0.0018 a
  frame (of 1, sRGB) everywhere, the display transform's dither, with no block pops, on the first round's code and
  this one alike: no noise or flicker of the shadow mask or the light.
- **Not done, or still wrong**: the voxel cache and empty-space skipping (#8); the interleave of 4 brings little;
  flying through a layer costs 1.59 ms layered (above 1.5); a thin dotted row of pixels right at the horizon from the
  half-resolution march straddling it; the anvil spreads over 20 minutes, so a 30-minute film ends with it half grown,
  and a cell's tower (a 2.7 km core radius) looks slender from 25 km; low-sun daytime clouds look flatter than the
  first round's (the sky light is now the sky's real one, larger than the old estimate at low sun; not compared with
  a path tracer, #10); the 2D tier keeps the analytic atmosphere and draws no anvils.
- **Cost** ([#8](https://github.com/true-sim/effects-weather/issues/8)): a dynamically indexed local array (the nine
  type weights) lived in scratch memory; it is indexed by constants now. And settings of the march's kind
  (`render.clouds.<kind>.*`), each measured against the reference (`CloudFixesTest.theCheaperMarchConvergesToThe
  ReferenceMarch`, converged still, mean difference over the mean): `interleave` 2 (one pixel of each 2 × 2 block
  marched a frame in a Bayer order, the rest the history reprojected and clamped to the new pixels around it: 0.81 %),
  `lightEvery` 2 (the light march every second sample inside a cloud, its optical depth reused between), `lightFar`
  16 km with `lightStepsFar` 2 (the same cone in fewer, longer steps), `fineRatio` 0.5, `opaque` 0.01 (rays stop at
  1 % transmittance), `detailFar` 30 km (detail is sub-pixel there): every saving together 2.8 %. Measured one by one
  (each alone against the reference): light every third sample 2.8 %, two light steps beyond 8 km 2.8 %, fine ratio
  0.5 0.5 %, 1 % cut-off 0.3 %, no detail beyond 30 km 0.5 %. A flash marches every pixel (it must answer within the
  frame). The march's push constants are packed (the engine allows 128 bytes). Not done from #8: the voxel cache and
  empty-space skipping; the interleave of 4 measured no faster than 2 in the storm (2.0 against 1.9 ms: the longest
  rays bound the dispatch), so the defaults stay at 2.

  `CloudBenchmark` (as above: RX 9070 XT, 1920 x 1080, 2026-11-08, ranges of 3 rounds), clouds' passes in ms; the
  first round's code, this round's physics with every saving off (the reference), and the defaults:

  | Scene | Tier | First round | Reference | Defaults |
  |---|---|---|---|---|
  | fair cumulus, ground | volumetric | 6.35-6.41 | 6.92-6.95 | 2.32-2.34 |
  | fair cumulus, ground | layered | 3.31-3.36 | 3.62-3.66 | 1.15-1.16 |
  | overcast, ground | volumetric | 3.28-3.30 | 3.92-3.93 | 1.76-1.78 |
  | overcast, ground | layered | 2.32-2.34 | 2.99-3.01 | 1.16-1.18 |
  | thunderstorm, ground | volumetric | 4.92-4.94 | 8.45-8.49 | 2.84-2.85 |
  | thunderstorm, ground | layered | 2.66-2.67 | 5.65-5.68 | 1.67-1.68 |
  | flying through | volumetric | 7.51-7.52 | 8.45-8.51 | 2.60-2.62 |
  | flying through | layered | 5.92-5.94 | 6.79-6.81 | 1.59 |
  | any | skybox (2D) | 0.04-0.06 | 0.04-0.06 | 0.04-0.06 |

  The storm costs more than the first round's at equal settings: its anvils, wider towers and their billows are cloud
  the first round did not draw. Layered is within the report's 0.6-1.5 ms in fair weather and overcast and its 2-4 ms
  in a storm; flying through a layer 1.59 ms. The shadow map and mask 0.13-0.29 ms, the GPU weather maps 0.20 ms.

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
  Worked around at first by merging runs of tiles and uploading at most 24 regions a frame; the maps are now
  evaluated on the GPU and upload nothing.
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
