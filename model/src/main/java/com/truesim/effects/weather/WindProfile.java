package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Objects;

/**
 * The wind by altitude: layers of speed and direction, interpolated linearly between them (as vectors) and held
 * constant above the highest and below the lowest. Directions are meteorological: where the wind comes <i>from</i>,
 * degrees clockwise from north (270 = a westerly, blowing east). Immutable.
 */
public final class WindProfile {

    /**
     * One layer.
     *
     * @param altitude metres above the ground
     * @param speed m/s
     * @param direction where it blows from, degrees from north
     * @param turbulence gustiness [0, 1], for renderers and particles
     */
    public record Layer(double altitude, double speed, double direction, double turbulence) {

        public Layer {
            Checks.atLeast(speed, 0, "speed");
            Checks.range(turbulence, 0, 1, "turbulence");
            Checks.range(direction, -360, 720, "direction");
            Checks.range(altitude, -1000, 100_000, "altitude");
        }

        /** The wind's eastward component, m/s. */
        public double east() {
            return -speed * Maths.sin(direction * Maths.DEGREES_TO_RADIANS);
        }

        /** The wind's northward component, m/s. */
        public double north() {
            return -speed * Maths.cos(direction * Maths.DEGREES_TO_RADIANS);
        }
    }

    private final Layer[] layers;
    private final double[] altitudes;
    private final double[] east;
    private final double[] north;

    /** A profile of {@code layers} (any order; at least one). */
    @AllowAllocation("made once")
    public WindProfile(Layer... layers) {
        if (layers.length == 0) {
            throw new IllegalArgumentException("A wind profile needs a layer");
        }
        this.layers = layers.clone();
        for (Layer layer : this.layers) {
            Objects.requireNonNull(layer, "layer");
        }
        Arrays.sort(this.layers, Comparator.comparingDouble(Layer::altitude));
        altitudes = new double[layers.length];
        east = new double[layers.length];
        north = new double[layers.length];
        for (int i = 0; i < layers.length; i++) {
            altitudes[i] = this.layers[i].altitude();
            east[i] = this.layers[i].east();
            north[i] = this.layers[i].north();
        }
    }

    /**
     * Mid-latitude westerlies: 5 m/s at 10 m, 10 m/s at 1.5 km, 20 m/s at 5 km, a 35 m/s jet near 10 km, veering with
     * height.
     */
    public static WindProfile westerlies() {
        return new WindProfile(new Layer(10, 5, 240, 0.4), new Layer(1500, 10, 255, 0.3), new Layer(5000, 20, 265, 0.2),
            new Layer(10000, 35, 270, 0.1));
    }

    /** No wind. */
    public static WindProfile calm() {
        return new WindProfile(new Layer(0, 0, 0, 0));
    }

    public int layerCount() {
        return layers.length;
    }

    /** Layer {@code i}, lowest first. */
    public Layer layer(int i) {
        return layers[i];
    }

    private int below(double altitude) {
        int i = 0;
        while (i + 1 < altitudes.length && altitudes[i + 1] <= altitude) {
            i++;
        }
        return i;
    }

    private double fraction(int i, double altitude) {
        if (i + 1 >= altitudes.length || altitude <= altitudes[i]) {
            return 0.0;
        }
        return (altitude - altitudes[i]) / (altitudes[i + 1] - altitudes[i]);
    }

    /** The eastward wind at {@code altitude}, m/s. */
    public double east(double altitude) {
        int i = below(altitude);
        double f = fraction(i, altitude);
        return f == 0 ? east[i] : east[i] + f * (east[i + 1] - east[i]);
    }

    /** The northward wind at {@code altitude}, m/s. */
    public double north(double altitude) {
        int i = below(altitude);
        double f = fraction(i, altitude);
        return f == 0 ? north[i] : north[i] + f * (north[i + 1] - north[i]);
    }
}
