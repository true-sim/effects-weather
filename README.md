# true-sim effects-weather

The weather of true-sim, as data and simulation that run without the engine: a weather map over the world (near the
camera, far to the horizon, and the whole planet from orbit), weather presets with smooth transitions, brushes a game
places, wind by altitude, a deterministic thunderstorm life cycle with its lightning, and the cloud types, profiles and
noise every cloud renderer reads. The modules depend only on the JDK and true-sim's own repositories (build-logic,
common-math, scene, [effects-atmosphere](https://github.com/true-sim/effects-atmosphere)), so a server simulates the
same storms as every client.

Render techniques (2D, layered and fly-through volumetric clouds, cloud shadows, rain shafts, lightning glow and bolts)
come later as technique-registry plugins in this repository, after the engine has the generic hooks they need. The
design, the tiers and the plan: [docs/design.md](docs/design.md).

| Module | Contents |
|---|---|
| `weather-clouds` | Cloud types (WMO genera) and their vertical profiles with physical defaults, art overrides (off by default), the profile atlas, CPU reference noise (Perlin, Worley, Perlin–Worley, curl) defined exactly for the GPU generators to match, cloud tiers. |
| `weather-lightning` | Flash events for renderers and audio, flicker from stroke trains (with exact frame averages), branching bolt geometry from a seed, thunder timing. |
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

## Building

```
./gradlew build
```

Sibling checkouts of `build-logic`, `common-*`, `scene` and `effects-atmosphere` are used as included builds when
present (override with `-Ptruesim.<repo>=<path>`, or `false` to use the published packages); otherwise the published
packages are used, so CI needs `effects-atmosphere` 0.1.0 published first. Benchmarks run with
`TRUESIM_BENCHMARK=true`.
