package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.weather.clouds.CloudNoiseTextures;
import com.truesim.effects.weather.render.layout.CopyPush;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Textures;
import com.truesim.engine.vulkan.Pipeline;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The GPU's cloud noise ({@link CloudNoise}, generate.slang) against weather-clouds' CPU reference
 * ({@link CloudNoiseTextures}): every texel of the shape volume, the detail volume and the curl texture within one step
 * of 255, generated once (later frames hold the same texels).
 */
class CloudNoiseGpuTest {

    /** Copies the noise's textures into buffers read back each frame. */
    private record Copy(CloudNoise noise, int[][] out) implements Technique {
        @Override
        public List<Key<?>> provides() {
            return List.of(TestGpu.SINK);
        }

        @Override
        public void build(GraphBuilder g) {
            Buffer ready = g.request(noise.generate());
            Pipeline copy = g.computeProgram("shaders/test/copy.spv", "copyMain", CopyPush.BYTES);
            int[] slots = {noise.shape(), noise.detail(), noise.curl()};
            int[] sizes = {noise.shapeSize(), noise.detailSize(), noise.curlSize()};
            for (int i = 0; i < 3; i++) {
                int k = i;
                int size = sizes[i];
                long texels = (long) size * size * (i == 2 ? 1 : size);
                Buffer buffer = g.createBuffer("copy " + i, 4 * texels);
                CopyPush push = new CopyPush();
                g.pass("copy " + i).read(ready, BufferAccess.STORAGE_READ).write(buffer, BufferAccess.STORAGE_WRITE)
                    .record(ctx -> ctx.dispatch(copy, push.output(ctx.address(buffer)).image(slots[k]).size(size)
                        .flat(k == 2 ? 1 : 0), (size + 3) / 4, (size + 3) / 4, k == 2 ? 1 : (size + 3) / 4));
                g.readback("noise " + i, buffer, 0, 4 * texels, (MemorySegment data, long frame) ->
                    out[k] = data.toArray(ValueLayout.JAVA_INT));
            }
            TestGpu.provideSink(g);
        }
    }

    @Test
    void theGpuNoiseMatchesTheCpuReference() {
        try (TestGpu gpu = TestGpu.open()) {
            Textures textures = gpu.own(new Textures(gpu.device, gpu.graph.frameSlots()));
            CloudNoise noise = new CloudNoise(textures, 32, 4, 3, 16, 2, 32, 4, CloudNoise.CURL_RANGE, 7);
            int[][] read = new int[3][];
            gpu.sink(new Copy(noise, read));
            gpu.frames(3, () -> { }); // the first frame's readback arrives as the third begins
            assertThat(noise.generated()).isTrue();
            int[][] first = {read[0].clone(), read[1].clone(), read[2].clone()};
            gpu.frames(3, () -> { });
            noise.close();

            byte[] shape = new byte[4 * 32 * 32 * 32];
            CloudNoiseTextures.shape(32, 4, 3, 7, shape);
            byte[] detail = new byte[4 * 16 * 16 * 16];
            CloudNoiseTextures.detail(16, 2, 7, detail);
            byte[] curl = new byte[2 * 32 * 32];
            CloudNoiseTextures.curl(32, 4, 3, CloudNoise.CURL_RANGE, 7, curl);

            assertWithinAStep("shape", read[0], shape, 4);
            assertWithinAStep("detail", read[1], detail, 4);
            assertWithinAStep("curl", read[2], curl, 2);
            for (int i = 0; i < 3; i++) {
                assertThat(read[i]).as("generated once: later frames hold the first frames' texels")
                    .isEqualTo(first[i]);
            }
        }
    }

    /** Every channel of {@code gpu} (RGBA8 words) within one step of the CPU's {@code channels}-channel bytes. */
    private static void assertWithinAStep(String name, int[] gpu, byte[] cpu, int channels) {
        int texels = cpu.length / channels;
        assertThat(gpu).hasSize(texels);
        int worst = 0;
        long off = 0;
        for (int t = 0; t < texels; t++) {
            for (int c = 0; c < channels; c++) {
                int g = (gpu[t] >>> (8 * c)) & 0xff;
                int r = cpu[channels * t + c] & 0xff;
                int d = Math.abs(g - r);
                worst = Math.max(worst, d);
                off += d > 0 ? 1 : 0;
            }
        }
        System.out.printf("Cloud noise %s: %d texels, %d channels off by a step, worst %d%n", name, texels, off,
            worst);
        assertThat(worst).as(name + ": the largest difference, in steps of 255").isLessThanOrEqualTo(1);
    }
}
