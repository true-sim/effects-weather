package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.StormSimulation;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherDomain;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.clouds.CloudTypes;
import com.truesim.effects.weather.clouds.CloudsConfig;
import com.truesim.effects.weather.render.layout.CopyFloatPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Pipeline;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A thunderstorm's shape, measured in the clouds' Beer shadow map with the sun overhead (each texel's maximum optical
 * depth is then its column's, from the ground up): a mature cumulonimbus's anvil is a thick ice cloud, optical depths
 * of tens (Heymsfield's ice water contents of tenths of a gram per cubic metre over two kilometres and more), not a
 * cirrus veil of one or two; it lies downwind of the core; the core's column is thicker still.
 */
class StormShapeTest {

    private static final int SIZE = 128;

    /** Copies the frame's Beer shadow map into a buffer read back each frame. */
    private record Probe(WeatherGpu gpu, float[][] out) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(TestGpu.SINK);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer ready = g.request(gpu.noise().generate());
            Image map = g.request(gpu.shadowMap(ready));
            Pipeline copy = g.computeProgram("shaders/test/copy.spv", "copyFloatMain", CopyFloatPush.BYTES);
            Buffer buffer = g.createBuffer("shadow map copy", 16L * SIZE * SIZE);
            CopyFloatPush push = new CopyFloatPush();
            g.pass("shadow map copy").read(map, Access.SAMPLED_COMPUTE).write(buffer, BufferAccess.STORAGE_WRITE)
                .record(ctx -> ctx.dispatch(copy, push.output(ctx.address(buffer)).image(ctx.sampled(map)).size(SIZE),
                    (SIZE + 7) / 8, (SIZE + 7) / 8, 1));
            g.readback("shadow map", buffer, 0, 16L * SIZE * SIZE, (MemorySegment data, long frame) ->
                out[0] = data.toArray(ValueLayout.JAVA_FLOAT));
            TestGpu.provideSink(g);
        }
    }

    /** Optical depths of a storm 40 minutes old: [core's column, anvil's column 1.5-3 core radii downwind]. */
    static double[] columns() {
        try (TestGpu gpu = TestGpu.open()) {
            var textures = gpu.own(new com.truesim.engine.render.Textures(gpu.device, gpu.graph.frameSlots()));
            var settings = gpu.graph.settings();
            settings.set(CloudSettings.SHADOW_SIZE, SIZE).set(CloudSettings.SHADOW_EXTENT, 64_000f)
                .set(CloudSettings.SHADOW_STEPS, 96);
            WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM);
            Weather weather = new Weather(config, WeatherDomain.flat(), WeatherPreset.CLEAR);
            weather.spawnStorm(-30_000, 0, 0);
            StormSimulation storms = weather.storms();
            for (int i = 0; i < 40 * 60; i++) {
                weather.setFocus(storms.position(0, 0), 0, 0);
                weather.step();
            }
            WeatherGpu w = gpu.own(new WeatherGpu(gpu.device, gpu.graph.frameSlots(), textures, weather,
                CloudTypes.physical(), CloudsConfig.forQuality(Quality.MEDIUM), settings));
            SceneSky sky = new SceneSky(new com.truesim.scene.core.SceneGraph(new com.truesim.common.state.ChangeClock()),
                CloudLighting.Atmosphere.EARTH);
            // No sun node: the sun overhead, so the map's optical depths are the columns'.
            float[][] read = new float[1][];
            gpu.sink(new Probe(w, read));
            gpu.frames(1, () -> w.update(weather.time(), 1.0 / 60, sky, settings));
            gpu.frames(3, () -> w.update(weather.time(), 1.0 / 60, sky, settings));
            double r = storms.coreRadius(0);
            double cx = storms.position(0, 0) - w.anchorX();
            double cz = storms.position(0, 2) - w.anchorZ();
            double texel = 64_000.0 / SIZE;
            double core = 0;
            double anvil = 0;
            for (int j = 0; j < SIZE; j++) {
                for (int i = 0; i < SIZE; i++) {
                    double x = (i + 0.5 - SIZE / 2.0) * texel - cx;
                    double z = -(j + 0.5 - SIZE / 2.0) * texel - cz; // the map's v runs north (-z)
                    double depth = read[0][4 * (j * SIZE + i) + 2];
                    if (Math.hypot(x, z) < 0.3 * r) {
                        core = Math.max(core, depth);
                    }
                    if (x > 1.5 * r && x < 3 * r && Math.abs(z) < 0.5 * r) {
                        anvil = Math.max(anvil, depth);
                    }
                }
            }
            System.out.printf("Storm columns: core %.1f, anvil %.1f (core radius %.0f m, anvil %.2f)%n",
                core, anvil, r, storms.anvil(0));
            return new double[] {core, anvil};
        }
    }

    @Test
    void aCumulonimbusAnvilIsAThickIceCloud() {
        double[] with = columns();
        assertThat(with[1]).as("the anvil's optical depth from below").isGreaterThan(10);
        assertThat(with[0]).as("the core's column").isGreaterThan(with[1]);
    }
}
