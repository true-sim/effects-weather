package com.truesim.effects.weather;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.lightning.LightningConfig;
import java.util.Objects;

/**
 * The weather's tunables: the simulation tier, the fixed step, the maps' sizes and budgets, the wind, storms and
 * lightning.
 *
 * @param simulation the tier
 * @param step the fixed step, seconds of game time
 * @param seed the simulation's and the noise's seed: equal seeds give equal weather on every machine
 * @param nearSize the near map's texels per edge (the design's 512)
 * @param nearExtent the near map's edge, metres (64 km; 150 km for flight)
 * @param farSize the far map's texels per edge on a flat world (128)
 * @param farExtent the far map's edge on a flat world, metres (400 km)
 * @param globalFaceSize the global map's texels per cube face edge on a planet
 * @param tileTexels texels per tile edge (the refresh unit)
 * @param tilesPerStep near-map tiles refreshed per step on the CPU (the far maps get a quarter, at least one); about
 *     0.3 µs per texel, so 4 tiles of 32² cost about 1.5 ms. GPU renderers evaluate the same {@link WeatherField}
 *     in a compute pass instead and need only the small inputs (preset, cells, brushes)
 * @param maxBrushes brush capacity
 * @param lightningCapacity the lightning event ring's capacity
 * @param noiseOctaves the coverage noise's octaves
 * @param maxPrecipitationRate the precipitation channel's 1, mm/h
 * @param advectionAltitude the altitude whose wind carries the coverage pattern, metres
 * @param reanchorDistance on a planet, how far the focus may move from the near map's anchor before it re-anchors
 * @param wind the wind profile
 * @param storms the storm life cycle
 * @param lightning lightning
 */
public record WeatherConfig(Simulation simulation, double step, long seed, int nearSize, double nearExtent,
    int farSize, double farExtent, int globalFaceSize, int tileTexels, int tilesPerStep, int maxBrushes,
    int lightningCapacity, int noiseOctaves, double maxPrecipitationRate, double advectionAltitude,
    double reanchorDistance, WindProfile wind, StormConfig storms, LightningConfig lightning) {

    /** The weather simulation tiers, cheapest first. */
    public enum Simulation {
        /** The preset only: no transitions over time (they apply at once), no wind drift, no storms. */
        STATIC,
        /** Presets with transitions, wind drift and brushes. */
        PRESETS,
        /** Everything: plus the storm life cycle and its lightning. */
        STORMS
    }

    public WeatherConfig {
        Objects.requireNonNull(simulation, "simulation");
        Objects.requireNonNull(wind, "wind");
        Objects.requireNonNull(storms, "storms");
        Objects.requireNonNull(lightning, "lightning");
        Checks.range(step, 1e-3, 3600, "step");
        Checks.range(nearSize, 2, 1 << 14, "nearSize");
        Checks.above(nearExtent, 0, "nearExtent");
        Checks.range(farSize, 2, 1 << 14, "farSize");
        Checks.above(farExtent, 0, "farExtent");
        Checks.range(globalFaceSize, 2, 1 << 13, "globalFaceSize");
        Checks.range(tileTexels, 1, nearSize, "tileTexels");
        Checks.range(tilesPerStep, 1, 1 << 20, "tilesPerStep");
        Checks.range(maxBrushes, 0, 1 << 16, "maxBrushes");
        Checks.range(lightningCapacity, 1, 1 << 20, "lightningCapacity");
        Checks.range(noiseOctaves, 1, 8, "noiseOctaves");
        Checks.above(maxPrecipitationRate, 0, "maxPrecipitationRate");
        Checks.atLeast(advectionAltitude, 0, "advectionAltitude");
        Checks.above(reanchorDistance, 0, "reanchorDistance");
    }

    /**
     * LOW: presets only, small maps; MEDIUM: storms, 256² near map; HIGH: the design's 512² over 64 km; ULTRA: 1024²
     * over 150 km (flight).
     */
    public static WeatherConfig forQuality(Quality quality) {
        StormConfig storms = StormConfig.forQuality(quality);
        LightningConfig lightning = LightningConfig.forQuality(quality);
        WindProfile wind = WindProfile.westerlies();
        return switch (quality) {
            case LOW -> new WeatherConfig(Simulation.PRESETS, 1.0, 1, 128, 32_000, 64, 200_000, 32, 16, 2, 16, 64, 3,
                100, 2000, 100_000, wind, storms, lightning);
            case MEDIUM -> new WeatherConfig(Simulation.STORMS, 1.0, 1, 256, 64_000, 128, 400_000, 64, 32, 2, 32, 128,
                4, 100, 2000, 100_000, wind, storms, lightning);
            case HIGH -> new WeatherConfig(Simulation.STORMS, 1.0, 1, 512, 64_000, 128, 400_000, 128, 32, 4, 64, 256,
                4, 100, 2000, 100_000, wind, storms, lightning);
            case ULTRA -> new WeatherConfig(Simulation.STORMS, 0.5, 1, 1024, 150_000, 256, 800_000, 256, 32, 4, 128,
                512, 5, 100, 2000, 200_000, wind, storms, lightning);
        };
    }

    public WeatherConfig withSimulation(Simulation tier) {
        return new WeatherConfig(tier, step, seed, nearSize, nearExtent, farSize, farExtent, globalFaceSize, tileTexels,
            tilesPerStep, maxBrushes, lightningCapacity, noiseOctaves, maxPrecipitationRate, advectionAltitude,
            reanchorDistance, wind, storms, lightning);
    }

    public WeatherConfig withSeed(long newSeed) {
        return new WeatherConfig(simulation, step, newSeed, nearSize, nearExtent, farSize, farExtent, globalFaceSize,
            tileTexels, tilesPerStep, maxBrushes, lightningCapacity, noiseOctaves, maxPrecipitationRate,
            advectionAltitude, reanchorDistance, wind, storms, lightning);
    }

    public WeatherConfig withMaps(int near, double nearMetres, int far, double farMetres, int globalFace, int tile,
        int tilesEachStep) {
        return new WeatherConfig(simulation, step, seed, near, nearMetres, far, farMetres, globalFace, tile,
            tilesEachStep, maxBrushes, lightningCapacity, noiseOctaves, maxPrecipitationRate, advectionAltitude,
            reanchorDistance, wind, storms, lightning);
    }

    public WeatherConfig withWind(WindProfile profile) {
        return new WeatherConfig(simulation, step, seed, nearSize, nearExtent, farSize, farExtent, globalFaceSize,
            tileTexels, tilesPerStep, maxBrushes, lightningCapacity, noiseOctaves, maxPrecipitationRate,
            advectionAltitude, reanchorDistance, profile, storms, lightning);
    }

    public WeatherConfig withStorms(StormConfig config) {
        return new WeatherConfig(simulation, step, seed, nearSize, nearExtent, farSize, farExtent, globalFaceSize,
            tileTexels, tilesPerStep, maxBrushes, lightningCapacity, noiseOctaves, maxPrecipitationRate,
            advectionAltitude, reanchorDistance, wind, config, lightning);
    }

    public WeatherConfig withLightning(LightningConfig config) {
        return new WeatherConfig(simulation, step, seed, nearSize, nearExtent, farSize, farExtent, globalFaceSize,
            tileTexels, tilesPerStep, maxBrushes, lightningCapacity, noiseOctaves, maxPrecipitationRate,
            advectionAltitude, reanchorDistance, wind, storms, config);
    }
}
