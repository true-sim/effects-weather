package com.truesim.effects.weather;

import com.truesim.effects.weather.clouds.CloudNoise;
import com.truesim.effects.weather.clouds.CloudType;

/**
 * The weather as a function of place: what every weather map texel is computed from, and what a query outside the
 * maps evaluates directly. Three layers, in order:
 * <ol>
 * <li>the preset in effect (a {@link WeatherState}): coverage = mean + variation·(2n − 1), n the Perlin fbm of
 *     {@link CloudNoise} at the ground point minus the wind's advection offset, over the preset's noise scale;
 *     precipitation scales with the coverage;</li>
 * <li>the storm cells: a core disc (full inside 0.6 of its radius, falling to 0 at the radius) setting cumulonimbus or
 *     towering cumulus, its top and rain; the anvil disc downwind setting a high ice layer where the core does not
 *     reach;</li>
 * <li>the brushes, in order ({@link WeatherBrush.Mode}).</li>
 * </ol>
 * Types do not blend: the strongest contributor's type wins. Precipitation never exceeds coverage. Evaluating
 * allocates nothing.
 */
public final class WeatherField {

    /** The noise's tiling period in lattice cells: large enough never to repeat visibly. */
    static final int NOISE_PERIOD = 1 << 16;

    private final WeatherState state;
    private final Brushes brushes;
    private final StormSimulation storms;
    private final int octaves;
    private final int seed;
    private double offsetX;
    private double offsetY;
    private double offsetZ;

    /** A field over {@code state}, {@code brushes} and {@code storms} (null for none). */
    public WeatherField(WeatherState state, Brushes brushes, StormSimulation storms, int octaves, int seed) {
        this.state = state;
        this.brushes = brushes;
        this.storms = storms;
        this.octaves = Checks.range(octaves, 1, 8, "octaves");
        this.seed = seed;
    }

    /** Sets the advection offset: how far the wind has carried the pattern, a vector in metres. */
    void setOffset(double x, double y, double z) {
        offsetX = x;
        offsetY = y;
        offsetZ = z;
    }

    private static double smooth(double x) {
        double t = Math.clamp(x, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    private static double distance(double ax, double ay, double az, double bx, double by, double bz) {
        double dx = ax - bx;
        double dy = ay - by;
        double dz = az - bz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Evaluates the weather at ground point (x, y, z) into {@code out}. */
    public void evaluate(double x, double y, double z, WeatherSample out) {
        double scale = state.noiseScale();
        float n = CloudNoise.perlinFbm((float) ((x - offsetX) / scale), (float) ((y - offsetY) / scale),
            (float) ((z - offsetZ) / scale), NOISE_PERIOD, octaves, seed);
        double coverage = Math.clamp(state.coverage() + state.variation() * (2.0 * n - 1.0), 0.0, 1.0);
        double precipitation = state.precipitation() * coverage;
        float type = state.type();
        double top = state.topHeight();

        if (storms != null) {
            double strongest = 0;
            for (int s = 0; s < storms.capacity(); s++) {
                if (!storms.active(s)) {
                    continue;
                }
                double r = storms.coreRadius(s);
                double d = distance(x, y, z, storms.position(s, 0), storms.position(s, 1), storms.position(s, 2));
                double w = 1 - smooth((d - 0.6 * r) / (0.4 * r));
                double core = w * storms.coverage(s);
                if (core > 0) {
                    coverage = Math.max(coverage, core);
                    precipitation = Math.max(precipitation, w * storms.precipitation(s));
                    if (core > strongest) {
                        strongest = core;
                        type = storms.cloudType(s).channelValue();
                        top = storms.top(s);
                    }
                }
                if (storms.anvil(s) > 0 && w < 0.5) {
                    double ra = storms.anvilRadius(s) * storms.anvil(s);
                    double da = distance(x, y, z, storms.anvilPosition(s, 0), storms.anvilPosition(s, 1),
                        storms.anvilPosition(s, 2));
                    double wa = (1 - smooth((da - 0.5 * ra) / (0.5 * ra))) * 0.8 * Math.min(1.0,
                        storms.coverage(s) + 0.5);
                    if (wa > coverage && wa > strongest) {
                        coverage = wa;
                        strongest = wa;
                        type = CloudType.CIRRUS.channelValue();
                        top = storms.top(s);
                    }
                }
            }
        }

        for (int i = 0; i < brushes.count(); i++) {
            WeatherBrush b = brushes.brush(i);
            double w = b.weight(distance(x, y, z, brushes.position(i, 0), brushes.position(i, 1),
                brushes.position(i, 2)));
            if (w == 0) {
                continue;
            }
            if (b.mode() == WeatherBrush.Mode.CLEAR) {
                coverage *= 1 - w;
                precipitation *= 1 - w;
                continue;
            }
            if (b.mode() == WeatherBrush.Mode.ADD) {
                coverage = Math.min(1.0, coverage + w * b.coverage());
                precipitation = Math.min(1.0, precipitation + w * b.precipitation());
            } else {
                coverage += (b.coverage() - coverage) * w;
                precipitation += (b.precipitation() - precipitation) * w;
            }
            if (w > 0.5) {
                type = b.cloudType().channelValue();
                if (b.topHeight() > 0) {
                    top = b.topHeight();
                }
            }
        }
        out.set((float) coverage, type, (float) Math.min(precipitation, coverage), (float) top);
    }
}
