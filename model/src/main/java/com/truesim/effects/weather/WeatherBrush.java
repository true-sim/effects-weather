package com.truesim.effects.weather;

import com.truesim.effects.weather.clouds.CloudType;
import java.util.Objects;

/**
 * A game's local change to the weather: a disc where the weather map is pushed towards given values ("a storm rolling
 * in", "clear sky over the airfield"). Placed directly ({@link Brushes#add}) or as a component on a scene node
 * ({@link WeatherComponents#BRUSH}), which then gives its position. Brushes apply in the order they were added, after
 * the preset and the storms. Inside {@code radius·(1 − falloff)} the weight is {@code strength}; it falls to 0 at
 * {@code radius} with a smoothstep.
 *
 * @param mode how it combines
 * @param radius metres
 * @param falloff the soft edge's share of the radius [0, 1]
 * @param strength [0, 1]
 * @param coverage the coverage it adds or sets
 * @param cloudType the type it sets where its weight exceeds 0.5 (ignored by {@link Mode#CLEAR})
 * @param precipitation the precipitation it adds or sets [0, 1]
 * @param topHeight the cloud top it sets, metres (0 keeps the type's)
 * @param velocityEast its drift, m/s (direct brushes; node brushes move with their node)
 * @param velocityNorth its drift, m/s
 * @param lifetime seconds until it is removed (direct brushes), infinite for ever
 */
public record WeatherBrush(Mode mode, double radius, double falloff, double strength, double coverage,
    CloudType cloudType, double precipitation, double topHeight, double velocityEast, double velocityNorth,
    double lifetime) {

    /** How a brush combines with the weather under it. */
    public enum Mode {
        /** Adds coverage and precipitation. */
        ADD,
        /** Blends every channel towards its values. */
        SET,
        /** Removes clouds and precipitation. */
        CLEAR
    }

    public WeatherBrush {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(cloudType, "cloudType");
        Checks.above(radius, 0, "radius");
        Checks.range(falloff, 0, 1, "falloff");
        Checks.range(strength, 0, 1, "strength");
        Checks.range(coverage, 0, 1, "coverage");
        Checks.range(precipitation, 0, 1, "precipitation");
        Checks.atLeast(topHeight, 0, "topHeight");
        Checks.range(velocityEast, -1000, 1000, "velocityEast");
        Checks.range(velocityNorth, -1000, 1000, "velocityNorth");
        Checks.above(lifetime, 0, "lifetime");
    }

    /** A stationary brush setting {@code type} clouds of {@code coverage} over {@code radius}, for ever. */
    public static WeatherBrush set(double radius, CloudType type, double coverage, double precipitation) {
        return new WeatherBrush(Mode.SET, radius, 0.3, 1, coverage, type, precipitation, 0, 0, 0,
            Double.POSITIVE_INFINITY);
    }

    /** A stationary brush clearing the sky over {@code radius}. */
    public static WeatherBrush clear(double radius) {
        return new WeatherBrush(Mode.CLEAR, radius, 0.3, 1, 0, CloudType.CUMULUS, 0, 0, 0, 0, Double.POSITIVE_INFINITY);
    }

    public WeatherBrush withVelocity(double east, double north) {
        return new WeatherBrush(mode, radius, falloff, strength, coverage, cloudType, precipitation, topHeight, east,
            north, lifetime);
    }

    public WeatherBrush withLifetime(double seconds) {
        return new WeatherBrush(mode, radius, falloff, strength, coverage, cloudType, precipitation, topHeight,
            velocityEast, velocityNorth, seconds);
    }

    /** Its weight at {@code distance} metres from its centre. */
    public double weight(double distance) {
        double inner = radius * (1 - falloff);
        if (distance <= inner) {
            return strength;
        }
        if (distance >= radius) {
            return 0.0;
        }
        double t = (radius - distance) / (radius - inner);
        return strength * t * t * (3 - 2 * t);
    }
}
