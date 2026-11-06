package com.truesim.effects.weather;

import com.truesim.effects.weather.clouds.CloudType;

/**
 * The weather at one place: what a weather map texel holds. Channels as the renderer's weather texture packs them
 * (design §5.1): R coverage [0, 1], G cloud type ({@link CloudType#channelValue()}), B precipitation [0, 1] (rate over
 * the configured maximum; it also drives darkness and droplet size), A the cloud top in metres (0 = the type's
 * default). Mutable and reused, so sampling allocates nothing.
 */
public final class WeatherSample {

    float coverage;
    float type;
    float precipitation;
    float topHeight;

    public float coverage() {
        return coverage;
    }

    /** The type channel's value; {@link #cloudType()} decodes it. */
    public float type() {
        return type;
    }

    public CloudType cloudType() {
        return CloudType.fromChannel(type);
    }

    /** Precipitation over the configured maximum rate, [0, 1]. */
    public float precipitation() {
        return precipitation;
    }

    /** The precipitation rate in mm/h for a maximum of {@code maxRate} mm/h. */
    public double precipitationRate(double maxRate) {
        return precipitation * maxRate;
    }

    /** The cloud top in metres; 0 for the type's default. */
    public float topHeight() {
        return topHeight;
    }

    /** Sets all four channels. */
    public void set(float coverage, float type, float precipitation, float topHeight) {
        this.coverage = coverage;
        this.type = type;
        this.precipitation = precipitation;
        this.topHeight = topHeight;
    }

    /** Writes the four channels at {@code out[o..o+3]}. */
    void write(float[] out, int o) {
        out[o] = coverage;
        out[o + 1] = type;
        out[o + 2] = precipitation;
        out[o + 3] = topHeight;
    }
}
