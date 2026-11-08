package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.engine.graph.GpuTimings;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * GPU cost of the clouds per tier (TRUESIM_BENCHMARK=true), 1920 x 1080: the march, temporal and composite passes (or
 * the 2D pass), the Beer shadow map and the surfaces' shadow mask, in four scenes (fair cumulus and overcast from the
 * ground, a thunderstorm, flying through a stratocumulus layer). Medians of 60 timed frames after 40 untimed ones, in 3
 * rounds taking turns; ranges of the rounds' medians.
 */
class CloudBenchmark {

    private static final int W = 1920;
    private static final int H = 1080;

    private record Scene(String name, WeatherPreset preset, double[] camera, boolean storm) {
    }

    @Test
    void costPerTier() {
        assumeThat(System.getenv("TRUESIM_BENCHMARK")).isEqualTo("true");
        Scene[] scenes = {
            new Scene("fair cumulus, ground", WeatherPreset.SCATTERED, new double[] {0, 2, 0, -0.3, 0.22, -1}, false),
            new Scene("overcast, ground", WeatherPreset.OVERCAST, new double[] {0, 2, 0, 0.4, 0.18, -1}, false),
            new Scene("thunderstorm, ground", WeatherPreset.STORM, new double[] {0, 2, 0, -1, 0.28, 0.2}, true),
            new Scene("flying through", WeatherPreset.BROKEN, new double[] {0, 1300, 0, -1, 0.02, -0.3}, false)};
        String[] tiers = {"volumetric", "layered", "skybox"};
        int rounds = System.getenv("TRUESIM_BENCH_ROUNDS") != null
            ? Integer.parseInt(System.getenv("TRUESIM_BENCH_ROUNDS")) : 3;
        double[][][] march = new double[scenes.length][tiers.length][rounds];
        double[][][] shadows = new double[scenes.length][tiers.length][rounds];
        double[][][] frame = new double[scenes.length][tiers.length][rounds];
        double[][][][] passes = new double[scenes.length][tiers.length][PASSES.length][rounds];
        for (int round = 0; round < rounds; round++) {
            for (int i = 0; i < scenes.length; i++) {
                for (int t = 0; t < tiers.length; t++) {
                    double[] r = measure(scenes[i], tiers[t]);
                    march[i][t][round] = r[0];
                    shadows[i][t][round] = r[1];
                    frame[i][t][round] = r[2];
                    for (int p = 0; p < PASSES.length; p++) {
                        passes[i][t][p][round] = r[3 + p];
                    }
                }
            }
        }
        System.out.println("| Scene | Tier | Clouds (march, temporal, composite / 2D) | Shadow map + mask | Frame (GPU) |");
        System.out.println("|---|---|---|---|---|");
        for (int i = 0; i < scenes.length; i++) {
            for (int t = 0; t < tiers.length; t++) {
                System.out.printf("| %s | %s | %s ms | %s ms | %s ms |%n", scenes[i].name(), tiers[t],
                    range(march[i][t]), range(shadows[i][t]), range(frame[i][t]));
            }
        }
        System.out.println();
        System.out.println("| Scene | Tier | " + String.join(" | ", PASSES) + " |");
        System.out.println("|---|---|" + "---|".repeat(PASSES.length));
        for (int i = 0; i < scenes.length; i++) {
            for (int t = 0; t < tiers.length; t++) {
                StringBuilder row = new StringBuilder("| " + scenes[i].name() + " | " + tiers[t] + " |");
                for (int p = 0; p < PASSES.length; p++) {
                    row.append(' ').append(range(passes[i][t][p])).append(" |");
                }
                System.out.println(row);
            }
        }
    }

    /** The passes timed one by one (ms, medians), for profiling. */
    private static final String[] PASSES = {"cloud march", "cloud light", "cloud temporal", "cloud composite",
        "clouds 2d", "cloud shadow map", "cloud shadow mask", "weather maps"};

    private static String range(double[] values) {
        double lo = Arrays.stream(values).min().orElse(0);
        double hi = Arrays.stream(values).max().orElse(0);
        return String.format("%.3f", lo).equals(String.format("%.3f", hi)) ? String.format("%.3f", lo)
            : String.format("%.3f-%.3f", lo, hi);
    }

    /** The median milliseconds of the clouds' passes, the shadow passes and the whole frame. */
    private static double[] measure(Scene scene, String tier) {
        try (CloudScene s = new CloudScene(W, H, Quality.ULTRA, scene.preset(), true)) {
            // Settings to compare (TRUESIM_BENCH_SET: name=value;name=value, e.g. render.clouds.layered.interleave=2).
            String set = System.getenv("TRUESIM_BENCH_SET");
            if (set != null && !set.isBlank()) {
                java.util.Map<String, String> values = new java.util.HashMap<>();
                for (String pair : set.split(";")) {
                    String[] kv = pair.split("=", 2);
                    values.put(kv[0].trim(), kv[1].trim());
                }
                s.settings.load(values);
            }
                        Clouds.SLOT.choose(s.settings, tier.equals("volumetric") ? Clouds.VOLUMETRIC
                : tier.equals("layered") ? Clouds.LAYERED : Clouds.SKYBOX);
            if (scene.storm()) {
                s.weather.spawnStorm(-13_000, 0, 4000);
                s.weather.spawnStorm(-17_000, 0, -3000);
                for (int k = 0; k < 25 * 60; k++) {
                    s.weather.setFocus(0, 2, 0);
                    s.weather.step();
                }
                s.weather.refreshAll();
            }
            double[] c = scene.camera();
            s.sun(35, 200).look(c[0], c[1], c[2], c[3], c[4], c[5], Math.toRadians(70));
            s.gpu.graph.timings(true);
            s.frames(40, 1.0 / 60, false);
            int n = 60;
            double[] clouds = new double[n];
            double[] shadow = new double[n];
            double[] total = new double[n];
            double[][] each = new double[PASSES.length][n];
            for (int f = 0; f < n; f++) {
                s.frames(1, 1.0 / 60, false);
                GpuTimings timings = s.gpu.graph.timings();
                for (int p = 0; p < timings.count(); p++) {
                    String pass = timings.pass(p);
                    for (int q = 0; q < PASSES.length; q++) {
                        if (pass.equals(PASSES[q])) {
                            each[q][f] += timings.millis(p);
                        }
                    }
                    if (pass.equals("cloud shadow map") || pass.equals("cloud shadow mask")) {
                        shadow[f] += timings.millis(p);
                    } else if (pass.startsWith("cloud")) {
                        clouds[f] += timings.millis(p); // march, temporal, composite, 2D, caches, noise
                    }
                }
                total[f] = timings.spanMillis();
            }
            double[] result = new double[3 + PASSES.length];
            result[0] = median(clouds);
            result[1] = median(shadow);
            result[2] = median(total);
            for (int q = 0; q < PASSES.length; q++) {
                result[3 + q] = median(each[q]);
            }
            return result;
        }
    }

    private static double median(double[] v) {
        double[] sorted = v.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }
}
