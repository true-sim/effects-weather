# true-sim effects-weather

The weather of true-sim, as data and simulation that run without the engine: a weather map over the world (near the
camera, far to the horizon, and the whole planet from orbit), weather presets with smooth transitions, brushes a game
places, wind by altitude, a deterministic thunderstorm life cycle with its lightning, and the cloud types, profiles and
noise every cloud renderer reads. The modules depend only on the JDK and true-sim's own repositories (build-logic,
common-math, scene, [effects-atmosphere](https://github.com/true-sim/effects-atmosphere)), so a server simulates the
same storms as every client.

Render techniques come as plugins over the engine's generic hooks: the first, `weather-snow`, is snow lying on
surfaces as a surface shading hook; the others (2D, layered and fly-through volumetric clouds, cloud shadows, rain
shafts, lightning glow and bolts) follow. The
design, the tiers and the plan: [docs/design.md](docs/design.md).

| Module | Contents |
|---|---|
| `weather-clouds` | Cloud types (WMO genera) and their vertical profiles with physical defaults, art overrides (off by default), the profile atlas, CPU reference noise (Perlin, Worley, Perlin–Worley, curl) defined exactly for the GPU generators to match, cloud tiers. |
| `weather-lightning` | Flash events for renderers and audio, flicker from stroke trains (with exact frame averages), branching bolt geometry from a seed, thunder timing. |
| `weather-snow` | Simple snow as the engine's surface shading hook `weather.snow` ([#4](https://github.com/true-sim/effects-weather/issues/4), part 1): a top-down occlusion map (no snow under roofs), noise thresholded by coverage (0: exactly none), slopes, albedo/roughness/normal blended before lighting in every way of shading, opt-out per material and instance, accumulation and melting over time. Engine 0.192.0. |
| `weather-model` | The weather maps (`WeatherGrid` near and far, `GlobalWeatherGrid` on a planet), `WeatherField` (preset + storms + brushes), presets and transitions, brushes (direct and as scene components), the wind profile, the storm life cycle (`StormSimulation`) and the `Weather` facade. |

```java
Weather weather = new Weather(WeatherConfig.forQuality(Quality.HIGH), WeatherDomain.flat(), WeatherPreset.FAIR);
weather.setPreset(WeatherPreset.STORM, 1800);           // a storm rolls in over half an hour
weather.addBrush(WeatherBrush.clear(5000), 0, 0, 0);    // the airfield stays clear
// every frame:
weather.setFocus(camera.x(), camera.y(), camera.z());
weather.advance(frameSeconds);                           // fixed steps, identical on every machine
lastFlash = weather.lightning().forEachSince(lastFlash, (events, slot, id) -> thunder(events, slot));
```

## Simple snow

```java
SimpleSnow snow = new SimpleSnow(geometry, main);         // per view: the hook, its occlusion map and accumulation
port.add(snow);
scene.components().set(weather, ShadingHookComponent.TYPE,
    SnowContent.component(SnowParameters.DEFAULTS.withCoverage(0.6f).withSnowfall(1), true));
material = material.refuseHooks(SimpleSnow.HOOK);         // no snow on water, screens, interiors
// each frame, before the frame records:
snow.update(frameSeconds);
```

- **Where it lies.** `SimpleSnow` draws the scene's depth from above along the fall direction (`fallDirection`, slanted
  by the wind), orthographic over `weather.snow.area` round the camera, with a render view of its own,
  every frame (what moves shelters at once); a point more than `weather.snow.bias` below the topmost
  surface is sheltered (percentage-closer, 2 x 2). Beyond the map every point counts as reached. Then the slope (full up
  to `slopeStart` degrees, none past `slopeEnd`; vertical and downward faces never) and a world-space value noise
  (`noiseScale`, `noiseSharpness`) thresholded by the coverage: `saturate((n + c (1 + w) - 1) / w)`, exactly 0 at
  coverage 0, 1 everywhere at 1.
- **What it changes.** Albedo, roughness, metallic (to 0), specular (F0 0.02, ice) and clear coat, and the normal turned
  towards the geometric one (`normalBlend`), before lighting: at rasterized pixels, the visibility buffer's, ray and
  probe hits, so reflections and global illumination see the snow. Ordered after `weather.wetness` where there is one.
  Materials refuse it (`Material.refuseHooks("weather.snow")`), instances by an override entry switching it off
  (engine-scene's `ShadingHookOverride.off("weather.snow")`).
- **Accumulation.** A texel per world column of the area (`weather.snow.accumulationResolution`; toroidal, a column
  keeps its texel while it stays in the area): snow fills in while it snows (`snowfall`, `weather.snow.fillSeconds`),
  melts (`melt`, `weather.snow.meltSeconds`), stays on what something drives over, and a surface revealed (a parked car
  leaving) or a cover staying longer than `weather.snow.coverSeconds` starts bare. The effective coverage is the scene's
  times the column's amount. New columns start at `weather.snow.initial`.
- **Not yet** (part 2 of #4, waiting for the engine's per-material displacement): snow depth, deformation by wheels and
  feet. Also not yet: redrawing the map only when static geometry changes (skipping its draw with
  `GraphBuilder.masked` left the main view black once the draw came back, engine 0.192.0), cascades of the map for distance (one map; beyond it every point is reached), and the
  accumulation sampling the map at the column's centre at the origin's height (exact for vertical fall, off by
  tan(slant) times the height difference for slanted snow).
- Tests: `SimpleSnowTest` (GPU: open ground white and ground under a roof bare in forward shading and the visibility
  buffer, the map's bias reaching past the roof snows under it; coverage 0 bit for bit as without the hook; vertical
  faces bare, tops and level ground white; patches growing with coverage; a refusing material and a switched-off
  instance bare; accumulating and melting over time; the scene's component). Pictures: `SnowScreenshots`
  (`TRUESIM_SCREENSHOTS=true`, `-Pscreenshots=<directory>`).

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene` and `effects-atmosphere` are used as included builds when
present (override with `-Ptruesim.<repo>=<path>`, or `false` to use the published packages); otherwise the published
packages are used, so CI needs `effects-atmosphere` 0.1.0 published first. Benchmarks run with
`TRUESIM_BENCHMARK=true`.
