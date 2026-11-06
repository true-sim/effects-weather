package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.clouds.CloudType;

/**
 * The preset in effect and the transition towards the next one: every continuous parameter blends with a smoothstep
 * over the transition's duration; the cloud type switches at its midpoint (types do not blend). Mutable; blending
 * allocates nothing.
 */
public final class WeatherState {

    private WeatherPreset from;
    private WeatherPreset to;
    private double duration;
    private double elapsed;

    // The blended parameters.
    private double coverage;
    private double variation;
    private double precipitation;
    private double topHeight;
    private double noiseScale;
    private double stormRate;
    private double windScale;
    private double cirrus;
    private float type;

    public WeatherState(WeatherPreset initial) {
        from = initial;
        to = initial;
        blend();
    }

    /**
     * Starts a transition to {@code target} over {@code seconds} (0: at once). It starts from the parameters in effect
     * now, so interrupting a transition does not jump.
     */
    @AllowAllocation("a game's call, not per step: the parameters in effect become the transition's start")
    public void transitionTo(WeatherPreset target, double seconds) {
        Checks.atLeast(seconds, 0, "seconds");
        from = progress() >= 1.0 ? to : new WeatherPreset("transition", coverage, variation,
            CloudType.fromChannel(type), precipitation, topHeight, noiseScale,
            stormRate, windScale, cirrus);
        to = target;
        duration = seconds;
        elapsed = 0;
        blend();
    }

    /** Advances the transition by {@code dt} seconds. */
    public void advance(double dt) {
        if (elapsed < duration) {
            elapsed = Math.min(duration, elapsed + dt);
            blend();
        }
    }

    /** The transition's progress [0, 1]; 1 when none is running. */
    public double progress() {
        return duration == 0 ? 1.0 : elapsed / duration;
    }

    /** The preset being left (or in effect, when no transition runs). */
    public WeatherPreset from() {
        return from;
    }

    /** The preset in effect at the end of the transition. */
    public WeatherPreset target() {
        return to;
    }

    private void blend() {
        double t = progress();
        double s = t * t * (3 - 2 * t);
        coverage = mix(from.coverage(), to.coverage(), s);
        variation = mix(from.variation(), to.variation(), s);
        precipitation = mix(from.precipitation(), to.precipitation(), s);
        topHeight = mix(from.topHeight(), to.topHeight(), s);
        noiseScale = mix(from.noiseScale(), to.noiseScale(), s);
        stormRate = mix(from.stormRate(), to.stormRate(), s);
        windScale = mix(from.windScale(), to.windScale(), s);
        cirrus = mix(from.cirrus(), to.cirrus(), s);
        type = (t < 0.5 ? from : to).cloudType().channelValue();
    }

    private static double mix(double a, double b, double s) {
        return a + (b - a) * s;
    }

    public double coverage() {
        return coverage;
    }

    public double variation() {
        return variation;
    }

    public double precipitation() {
        return precipitation;
    }

    public double topHeight() {
        return topHeight;
    }

    public double noiseScale() {
        return noiseScale;
    }

    public double stormRate() {
        return stormRate;
    }

    public double windScale() {
        return windScale;
    }

    public double cirrus() {
        return cirrus;
    }

    /** The type channel's value in effect. */
    public float type() {
        return type;
    }
}
