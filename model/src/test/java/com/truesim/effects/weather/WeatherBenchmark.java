package com.truesim.effects.weather;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.clouds.CloudNoiseTextures;
import com.truesim.effects.weather.clouds.CloudsConfig;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** CPU costs of the weather per step and quality preset (TRUESIM_BENCHMARK=true). */
@EnabledIfEnvironmentVariable(named = "TRUESIM_BENCHMARK", matches = "true")
class WeatherBenchmark {

    @Test
    void costs() {
        for (Quality quality : Quality.values()) {
            WeatherConfig config = WeatherConfig.forQuality(quality);
            Weather weather = new Weather(config, WeatherDomain.flat(), WeatherPreset.STORM);
            for (int i = 0; i < 6; i++) {
                weather.spawnStorm(i * 8000, 0, 0);
            }
            // Fill the maps first (first frames), then measure steady steps.
            int fill = (config.nearSize() / config.tileTexels()) * (config.nearSize() / config.tileTexels())
                / config.tilesPerStep() + 1;
            for (int i = 0; i < fill + 200; i++) {
                weather.step();
            }
            long[] t = new long[300];
            for (int i = 0; i < t.length; i++) {
                long start = System.nanoTime();
                weather.step();
                t[i] = System.nanoTime() - start;
            }
            Arrays.sort(t);
            int cells = weather.storms() == null ? 0 : weather.storms().activeCount();
            System.out.printf("%s: step %.3f ms median, %.3f ms p99 (%d tiles of %d² near, %d cells)%n", quality,
                t[t.length / 2] / 1e6, t[t.length * 99 / 100] / 1e6, config.tilesPerStep(), config.tileTexels(), cells);
        }
        for (Quality quality : new Quality[] {Quality.MEDIUM, Quality.HIGH}) {
            CloudsConfig clouds = CloudsConfig.forQuality(quality);
            int n = clouds.shapeSize();
            byte[] shape = new byte[4 * n * n * n];
            long start = System.nanoTime();
            CloudNoiseTextures.shape(n, clouds.shapeFrequency(), clouds.perlinOctaves(), clouds.seed(), shape);
            System.out.printf("CPU shape noise %d³: %.0f ms%n", n, (System.nanoTime() - start) / 1e6);
        }
    }
}
