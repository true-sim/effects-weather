package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.StormSimulation;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherPreset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * A storm cell's whole life seen from 25 km, frame after frame (ten seconds of weather each, the camera keeping its
 * place beside the cell as the wind carries it): the clouds above the horizon never change from one frame to the next
 * much more than the cell's steady growth changes them in the frames around it. Before, the top popped in: as the cell matured its anvil
 * appeared at once as a slab of the core's width at full strength (the plume's width did not grow from nothing, and
 * every anvil's altitude and the march's top were one value jumping from 0 to the cell's top), and as the cell formed
 * a tower of the type's default top stood for the first frames (the map's top channel counts only above the base).
 */
class StormContinuityTest {

    private static final int W = 256;
    private static final int H = 144;

    /**
     * Per frame pair, the clouds' change above the horizon: the sum of the luminance changes over the sum of the
     * luminances.
     */
    static double[] changes(int frames, double seconds) {
        WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM);
        try (CloudScene s = new CloudScene(W, H, config, Quality.MEDIUM, WeatherPreset.CLEAR, true, true)) {
            s.ev100(13); // a fixed exposure: the frames' changes are the clouds'
            s.settings.set(Clouds.LAYERED.setting("temporal"), false); // every frame its own, converged
            s.settings.set(CloudSettings.EVOLUTION, 0f); // what changes is the cell
            s.weather.spawnStorm(0, 0, -25_000);
            StormSimulation storms = s.weather.storms();
            int cell = StormShots.first(storms);
            s.sun(35, 80);
            double pitch = Math.toRadians(14);
            double fov = Math.toRadians(50);
            float[] last = null;
            double[] changed = new double[frames];
            int horizon = H / 2 + (int) Math.round(Math.tan(pitch) / Math.tan(fov / 2) * H / 2);
            for (int f = 0; f < frames; f++) {
                if (storms.active(cell)) {
                    s.look(storms.position(cell, 0), 2, storms.position(cell, 2) + 25_000, 0, Math.tan(pitch), -1,
                        fov);
                }
                float[] rgb = s.frames(1, seconds);
                if ("true".equals(System.getenv("TRUESIM_SCREENSHOTS"))) {
                    // The frames, for looking at what changed (film-continuity/fNNNN.png).
                    java.nio.file.Path dir = Images.directory().resolve("film-continuity");
                    try {
                        java.nio.file.Files.createDirectories(dir);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                    Images.writeShown(dir.resolve(String.format("f%04d", f)).toString(), s.shown(), W, H);
                }
                if (last != null) {
                    double moved = 0;
                    double total = 0;
                    for (int y = 0; y < Math.min(horizon, H); y++) { // rows from the top: above the horizon
                        for (int i = 0; i < W; i++) {
                            double a = s.luminance(last, i, y);
                            double b = s.luminance(rgb, i, y);
                            moved += Math.abs(b - a);
                            total += 0.5 * (a + b);
                        }
                    }
                    changed[f] = moved / Math.max(total, 1e-9);
                }
                last = rgb;
            }
            return changed;
        }
    }

    /**
     * Each frame's change over the steady change around it (the median of the five frames before and after it, at
     * least a quarter of the 90th percentile of all: the cell barely changing before it forms is no bound).
     */
    static double[] spikes(double[] changed) {
        double[] sorted = changed.clone();
        Arrays.sort(sorted);
        double floor = 0.25 * sorted[(int) (0.9 * sorted.length)];
        double[] spike = new double[changed.length];
        for (int f = 1; f < changed.length; f++) {
            double[] around = new double[10];
            int n = 0;
            for (int k = f - 5; k <= f + 5; k++) {
                if (k != f && k >= 1 && k < changed.length) {
                    around[n++] = changed[k];
                }
            }
            double[] near = Arrays.copyOf(around, n);
            Arrays.sort(near);
            spike[f] = changed[f] / Math.max(near[n / 2], floor);
        }
        return spike;
    }

    @Test
    void aStormCellGrowsAndDecaysWithoutPops() {
        double seconds = 10;
        double[] changed = changes(540, seconds); // 90 minutes: the cell's whole life
        double[] spike = spikes(changed);
        int at = 0;
        for (int f = 0; f < spike.length; f++) {
            if (spike[f] > spike[at]) {
                at = f;
            }
        }
        System.out.printf("Storm cell over its life: largest change %.2f times the steady change around it, at frame "
            + "%d (%.1f min; change %.4f); changes: %s%n", spike[at], at, at * seconds / 60, changed[at],
            Arrays.toString(Arrays.stream(changed).map(v -> Math.round(v * 10000) / 10000.0).toArray()));
        assertThat(spike[at]).as("no frame changes much more than the cell's steady growth around it").isLessThan(3);
    }
}
