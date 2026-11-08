package com.truesim.effects.weather.render;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.WeatherPreset;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CloudsSmokeTest {

    @ParameterizedTest
    @ValueSource(strings = {"clear", "fair", "overcast", "storm"})
    void draws(String name) {
        WeatherPreset preset = WeatherPreset.builtIn(name);
        try (CloudScene s = new CloudScene(320, 180, Quality.HIGH, preset, true)) {
            s.look(0, 2, 0, 0, 0.25, -1, Math.toRadians(70));
            float[] rgb = s.frames(8, 1.0 / 60);
            report(s, rgb, "smoke-" + name);
            s.look(0, 2, 0, 0.01, 1, -0.01, Math.toRadians(90));
            rgb = s.frames(8, 1.0 / 60);
            report(s, rgb, "smoke-up-" + name);
        }
    }

    private static void report(CloudScene s, float[] rgb, String name) {
        double max = 0;
        double sum = 0;
        for (int y = 0; y < s.height; y++) {
            for (int x = 0; x < s.width; x++) {
                double l = s.luminance(rgb, x, y);
                max = Math.max(max, l);
                sum += l;
            }
        }
        System.out.printf("%s: mean %.1f max %.1f cd/m2 -> %s%n", name, sum / (s.width * s.height), max,
            Images.write(name, rgb, s.width, s.height, 1.0 / 10000));
    }
}
