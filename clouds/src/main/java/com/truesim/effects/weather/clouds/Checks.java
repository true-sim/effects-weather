package com.truesim.effects.weather.clouds;

/** Argument checks for configuration records: one place for the branches, so records stay plain. */
final class Checks {

    private Checks() {
    }

    /** {@code value}, if it is within [min, max] (NaN is not). */
    static double range(double value, double min, double max, String name) {
        if (!(value >= min && value <= max)) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "], not " + value);
        }
        return value;
    }

    /** {@code value}, if it is at least {@code min}. */
    static double atLeast(double value, double min, String name) {
        return range(value, min, Double.POSITIVE_INFINITY, name);
    }

    /** {@code value}, if it is above {@code min}. */
    static double above(double value, double min, String name) {
        if (!(value > min)) {
            throw new IllegalArgumentException(name + " must be above " + min + ", not " + value);
        }
        return value;
    }

    /** {@code value}, if it is within [min, max]. */
    static int range(int value, int min, int max, String name) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be in [" + min + ", " + max + "], not " + value);
        }
        return value;
    }
}
