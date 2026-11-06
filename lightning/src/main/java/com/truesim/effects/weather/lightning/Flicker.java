package com.truesim.effects.weather.lightning;

import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Hash;

/**
 * A flash's luminosity over time, relative to its first stroke's peak, the same for the renderer (the in-cloud glow
 * and the scene light) and anything else that follows it. A ground flash is a train of strokes: stroke k starts at
 * t_k (the first at 0; then intervals drawn exponentially around the configured mean), peaks at 1 for the first and
 * 0.3–0.7 for later ones, decays exponentially, and may hold a continuing-current glow. An intracloud flash is a
 * train of weaker pulses over its duration. Everything is a function of the flash's seed (via {@code Hash}), so the
 * GPU can evaluate it too. {@link #average} integrates over a frame exactly, so short strokes never alias.
 */
public final class Flicker {

    private Flicker() {
    }

    private static int seed32(long seed) {
        return (int) (seed ^ (seed >>> 32));
    }

    /** The start of stroke {@code k} in seconds after the flash begins. */
    public static double strokeTime(FlashType type, long seed, int k, LightningConfig config) {
        if (k == 0) {
            return 0.0;
        }
        int s = seed32(seed);
        if (type == FlashType.INTRACLOUD) {
            // Pulses spread over the duration: evenly spaced slots, jittered within each.
            double slot = config.intracloudDurationMs() / 1000.0 / config.intracloudPulses();
            return slot * (k + Hash.random(s, k) - 0.5);
        }
        double t = 0;
        for (int i = 1; i <= k; i++) {
            double u = Hash.random(s, i);
            t += -Maths.log(1.0 - u) * config.interstrokeMs() / 1000.0;
        }
        return t;
    }

    /** The peak of stroke {@code k} relative to the first stroke's. */
    public static double strokePeak(FlashType type, long seed, int k) {
        double jitter = Hash.random(seed32(seed), 1000 + k);
        if (type == FlashType.INTRACLOUD) {
            return 0.2 + 0.3 * jitter;
        }
        return k == 0 ? 1.0 : 0.3 + 0.4 * jitter;
    }

    /** Whether stroke {@code k} has continuing current. */
    public static boolean continuing(long seed, int k, LightningConfig config) {
        return Hash.random(seed32(seed), 2000 + k) < config.continuingCurrentChance();
    }

    /** How many strokes or pulses the flash has, given the strokes of its event. */
    private static int count(FlashType type, int strokes, LightningConfig config) {
        return type == FlashType.INTRACLOUD ? config.intracloudPulses() : strokes;
    }

    /** The luminosity at {@code t} seconds after the flash began. */
    public static double intensity(FlashType type, int strokes, long seed, double t, LightningConfig config) {
        double tau = config.strokeDecayMs() / 1000.0;
        double sum = 0;
        int n = count(type, strokes, config);
        for (int k = 0; k < n; k++) {
            double dt = t - strokeTime(type, seed, k, config);
            if (dt < 0) {
                continue;
            }
            double peak = strokePeak(type, seed, k);
            sum += peak * Maths.exp(-dt / tau);
            if (continuing(seed, k, config) && dt < config.continuingCurrentMs() / 1000.0) {
                sum += peak * config.continuingCurrentLevel();
            }
        }
        return sum;
    }

    /** The mean luminosity over [t0, t1] seconds after the flash began, integrated exactly. */
    public static double average(FlashType type, int strokes, long seed, double t0, double t1, LightningConfig config) {
        if (!(t1 > t0)) {
            return intensity(type, strokes, seed, t0, config);
        }
        double tau = config.strokeDecayMs() / 1000.0;
        double cc = config.continuingCurrentMs() / 1000.0;
        double sum = 0;
        int n = count(type, strokes, config);
        for (int k = 0; k < n; k++) {
            double start = strokeTime(type, seed, k, config);
            double peak = strokePeak(type, seed, k);
            double a = Math.max(t0, start);
            if (t1 > a) {
                sum += peak * tau * (Maths.exp(-(a - start) / tau) - Maths.exp(-(t1 - start) / tau));
            }
            if (continuing(seed, k, config)) {
                double b = Math.min(t1, start + cc);
                if (b > a) {
                    sum += peak * config.continuingCurrentLevel() * (b - a);
                }
            }
        }
        return sum / (t1 - t0);
    }

    /** When the flash is over: its last stroke's start plus its decay to 1 % or its continuing current. */
    public static double duration(FlashType type, int strokes, long seed, LightningConfig config) {
        int n = count(type, strokes, config);
        double last = strokeTime(type, seed, n - 1, config);
        return last + Math.max(config.strokeDecayMs() / 1000.0 * 4.6, config.continuingCurrentMs() / 1000.0);
    }
}
