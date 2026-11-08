package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.WeatherBrush;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.WeatherSample;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.render.layout.CopyFloatPush;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Pipeline;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The GPU's weather map is the weather model's field at the frame's time, everywhere: no texel lags behind (the CPU
 * map refreshed tile by tile showed moving storm cells and the wind's drift as seams at tile boundaries).
 */
class WeatherMapGpuTest {

    /** Copies the near map into a buffer read back each frame. */
    private record Copy(WeatherGpu gpu, int size, float[][] out, boolean types) implements Technique {

        Copy(WeatherGpu gpu, int size, float[][] out) {
            this(gpu, size, out, false);
        }

        @Override
        public List<Key<?>> provides() {
            return List.of(TestGpu.SINK);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer maps = g.request(gpu.maps());
            Pipeline copy = g.computeProgram("shaders/test/copy.spv", "copyFloatMain", CopyFloatPush.BYTES);
            Buffer buffer = g.createBuffer("map copy", 16L * size * size);
            CopyFloatPush push = new CopyFloatPush();
            g.pass("map copy").read(maps, BufferAccess.STORAGE_READ).write(buffer, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(copy, push.output(ctx.address(buffer)).image(types ? gpu.nearTypes() : gpu.nearMap()).size(size),
                    (size + 7) / 8, (size + 7) / 8, 1));
            g.readback("map", buffer, 0, 16L * size * size, (MemorySegment data, long frame) ->
                out[0] = data.toArray(ValueLayout.JAVA_FLOAT));
            TestGpu.provideSink(g);
        }
    }

    @Test
    void theMapIsTheFieldAtTheFramesTimeEverywhere() {
        try (TestGpu gpu = TestGpu.open()) {
            var textures = gpu.own(new com.truesim.engine.render.Textures(gpu.device, gpu.graph.frameSlots()));
            WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM).withMaps(64, 32_000, 32, 200_000, 64, 16,
                4);
            var weather = new com.truesim.effects.weather.Weather(config,
                com.truesim.effects.weather.WeatherDomain.flat(), WeatherPreset.SCATTERED);
            weather.addBrush(WeatherBrush.set(5000, CloudType.STRATOCUMULUS, 0.9, 0.2), 3000, 0, -2000);
            weather.spawnStorm(-6000, 0, 4000);
            WeatherGpu w = gpu.own(new WeatherGpu(gpu.device, gpu.graph.frameSlots(), textures, weather,
                com.truesim.effects.weather.clouds.CloudTypes.physical(),
                com.truesim.effects.weather.clouds.CloudsConfig.forQuality(Quality.LOW), gpu.graph.settings()));
            SceneSky sky = new SceneSky(new com.truesim.scene.core.SceneGraph(new com.truesim.common.state.ChangeClock()),
                CloudLighting.Atmosphere.EARTH);
            float[][] read = new float[1][];
            gpu.sink(new Copy(w, 64, read));
            // Twenty minutes of weather: the cell grows and drifts, the wind carries the pattern.
            for (int i = 0; i < 1200; i++) {
                weather.step();
            }
            gpu.frames(1, () -> w.update(weather.time(), 1.0 / 60, sky, gpu.graph.settings()));
            gpu.frames(2, () -> { });
            WeatherSample sample = new WeatherSample();
            double texel = 32_000.0 / 64;
            int worse = 0;
            double worst = 0;
            for (int j = 0; j < 64; j++) {
                for (int i = 0; i < 64; i++) {
                    double x = w.anchorX() + (i + 0.5 - 32) * texel;
                    double z = w.anchorZ() + (j + 0.5 - 32) * texel;
                    weather.field().evaluate(x, 0, z, sample);
                    float coverage = read[0][4 * (j * 64 + i)];
                    double d = Math.abs(coverage - sample.coverage());
                    worst = Math.max(worst, d);
                    worse += d > 0.02 ? 1 : 0;
                }
            }
            System.out.printf("GPU weather map against the field: worst coverage difference %.4f, %d of 4096 over 0.02%n",
                worst, worse);
            assertThat(weather.storms().activeCount()).isPositive();
            assertThat(worse).as("texels off the field").isLessThanOrEqualTo(8);
            assertThat(worst).isLessThan(0.1);
        }
    }

    /**
     * A mature cell's anvil is its own channel (the types map's blue): a plume of ice leaving the core downwind, not the
     * core's type nor cirrus, and the cover without it (alpha) is the field's cover of everything else.
     */
    @Test
    void anAnvilSpreadsDownwindOfAMatureCell() {
        try (TestGpu gpu = TestGpu.open()) {
            var textures = gpu.own(new com.truesim.engine.render.Textures(gpu.device, gpu.graph.frameSlots()));
            WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM).withMaps(64, 64_000, 32, 200_000, 64, 16,
                4);
            var weather = new com.truesim.effects.weather.Weather(config,
                com.truesim.effects.weather.WeatherDomain.flat(), WeatherPreset.CLEAR);
            weather.spawnStorm(-10_000, 0, 0);
            WeatherGpu w = gpu.own(new WeatherGpu(gpu.device, gpu.graph.frameSlots(), textures, weather,
                com.truesim.effects.weather.clouds.CloudTypes.physical(),
                com.truesim.effects.weather.clouds.CloudsConfig.forQuality(Quality.LOW), gpu.graph.settings()));
            SceneSky sky = new SceneSky(new com.truesim.scene.core.SceneGraph(new com.truesim.common.state.ChangeClock()),
                CloudLighting.Atmosphere.EARTH);
            float[][] read = new float[1][];
            gpu.sink(new Copy(w, 64, read, true));
            for (int i = 0; i < 40 * 60; i++) {
                weather.setFocus(0, 0, 0);
                weather.step();
            }
            gpu.frames(1, () -> w.update(weather.time(), 1.0 / 60, sky, gpu.graph.settings()));
            gpu.frames(2, () -> { });
            var storms = weather.storms();
            int cell = 0;
            while (!storms.active(cell)) {
                cell++;
            }
            double texel = 64_000.0 / 64;
            double best = 0;
            double sum = 0;
            double weighted = 0;
            for (int j = 0; j < 64; j++) {
                for (int i = 0; i < 64; i++) {
                    float anvil = read[0][4 * (j * 64 + i) + 2];
                    best = Math.max(best, anvil);
                    sum += anvil;
                    weighted += anvil * (w.anchorX() + (i + 0.5 - 32) * texel);
                }
            }
            double bestX = weighted / sum; // the anvil's centroid
            System.out.printf("Anvil: growth %.2f, cover at most %.2f, centroid x %.0f (core %.0f, anvil centre %.0f)%n",
                storms.anvil(cell), best, bestX, storms.position(cell, 0), storms.anvilPosition(cell, 0));
            assertThat(storms.anvil(cell)).isGreaterThan(0.9);
            assertThat(best).isGreaterThan(0.5);
            assertThat(bestX).isGreaterThan(storms.position(cell, 0)); // downwind (the westerlies blow east)
        }
    }
}
