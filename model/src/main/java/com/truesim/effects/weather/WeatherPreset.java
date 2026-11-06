package com.truesim.effects.weather;

import com.truesim.effects.weather.clouds.CloudType;
import java.util.Objects;

/**
 * A kind of weather: the procedural base of the weather map, the storms' spawn rate and the wind's strength. A game
 * switches presets with a transition ({@link Weather#setPreset}); brushes and storms add to it. The defaults
 * ({@link #CLEAR} … {@link #STORM}) are the design's list; games make their own.
 *
 * @param name for saves and tools
 * @param coverage mean cloud coverage [0, 1]
 * @param variation how much the noise varies coverage around the mean, [0, 1]
 * @param cloudType the clouds' type
 * @param precipitation precipitation where covered, [0, 1] of the maximum rate
 * @param topHeight cloud tops in metres, 0 for the type's default
 * @param noiseScale the size of the coverage pattern, metres per noise lattice cell
 * @param stormRate new storm cells per 10,000 km² per hour (0 for none)
 * @param windScale multiplies the wind profile (storms are windier)
 * @param cirrus the high cirrus layer's coverage, [0, 1]
 */
public record WeatherPreset(String name, double coverage, double variation, CloudType cloudType, double precipitation,
    double topHeight, double noiseScale, double stormRate, double windScale, double cirrus) {

    public static final WeatherPreset CLEAR = new WeatherPreset("clear", 0.0, 0.0, CloudType.CUMULUS, 0, 0, 8000, 0,
        0.7, 0.05);
    public static final WeatherPreset FAIR = new WeatherPreset("fair", 0.15, 0.3, CloudType.CUMULUS, 0, 0, 6000, 0,
        0.8, 0.1);
    public static final WeatherPreset SCATTERED = new WeatherPreset("scattered", 0.35, 0.4, CloudType.CUMULUS, 0, 0,
        8000, 0, 1.0, 0.2);
    public static final WeatherPreset BROKEN = new WeatherPreset("broken", 0.65, 0.35, CloudType.STRATOCUMULUS, 0.05, 0,
        10000, 0, 1.0, 0.2);
    public static final WeatherPreset OVERCAST = new WeatherPreset("overcast", 0.95, 0.1, CloudType.STRATUS, 0.05, 0,
        15000, 0, 0.9, 0.0);
    public static final WeatherPreset ALTOSTRATUS = new WeatherPreset("altostratus", 0.85, 0.2, CloudType.ALTOSTRATUS,
        0, 0, 20000, 0, 1.1, 0.4);
    public static final WeatherPreset RAIN = new WeatherPreset("rain", 0.95, 0.1, CloudType.NIMBOSTRATUS, 0.3, 0,
        15000, 0, 1.2, 0.0);
    public static final WeatherPreset STORM = new WeatherPreset("storm", 0.5, 0.4, CloudType.TOWERING_CUMULUS, 0.1, 0,
        10000, 2.0, 1.5, 0.3);

    private static final WeatherPreset[] BUILT_IN = {CLEAR, FAIR, SCATTERED, BROKEN, OVERCAST, ALTOSTRATUS, RAIN, STORM};

    public WeatherPreset {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(cloudType, "cloudType");
        Checks.range(coverage, 0, 1, "coverage");
        Checks.range(variation, 0, 1, "variation");
        Checks.range(precipitation, 0, 1, "precipitation");
        Checks.atLeast(topHeight, 0, "topHeight");
        Checks.above(noiseScale, 0, "noiseScale");
        Checks.atLeast(stormRate, 0, "stormRate");
        Checks.atLeast(windScale, 0, "windScale");
        Checks.range(cirrus, 0, 1, "cirrus");
    }

    /** The built-in preset named {@code name}, or null. */
    public static WeatherPreset builtIn(String name) {
        for (WeatherPreset preset : BUILT_IN) {
            if (preset.name.equals(name)) {
                return preset;
            }
        }
        return null;
    }
}
