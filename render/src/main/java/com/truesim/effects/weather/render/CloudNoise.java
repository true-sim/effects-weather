package com.truesim.effects.weather.render;

import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.clouds.CloudsConfig;
import com.truesim.effects.weather.render.layout.NoisePush;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Pipeline;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The cloud noise textures on the GPU, generated once by compute from weather-clouds' exact definitions
 * ({@code CloudNoise}, {@code CloudNoiseTextures}) instead of the CPU's seconds: the base shape volume ({@code size}³
 * RGBA8: R Perlin–Worley, G B A Worley fbm at 1×, 2×, 4×), the detail volume (RGB Worley fbm) and the curl texture
 * (RG: 0.5 + 0.5·c / {@code curlRange}; RGBA8, as two-channel storage images are optional in Vulkan). Dynamic
 * textures of the scene's {@link Textures} written by compute (generate.slang) in the first frame that needs them
 * ({@link #generate}, a shared request whose buffer orders the readers after it), sampled with repeat (they tile).
 * They match the CPU reference within one step of 255 per texel ({@code CloudNoiseGpuTest}).
 */
public final class CloudNoise implements AutoCloseable {

    /** The curl's range of the default noise: about the largest curl of a 4-octave fbm, in lattice units. */
    public static final float CURL_RANGE = 4f;

    private final Textures textures;
    private final int shapeSize;
    private final int shapeFrequency;
    private final int perlinOctaves;
    private final int detailSize;
    private final int detailFrequency;
    private final int curlSize;
    private final int curlFrequency;
    private final float curlRange;
    private final int seed;
    private final int shape;
    private final int detail;
    private final int curl;
    private final AtomicBoolean generated = new AtomicBoolean();

    /**
     * The noise of {@code config} in {@code textures}: its shape, detail and curl sizes, frequencies (the curl's the
     * shape's), octaves and seed.
     */
    public CloudNoise(Textures textures, CloudsConfig config) {
        this(textures, config.shapeSize(), config.shapeFrequency(), config.perlinOctaves(), config.detailSize(),
            config.detailFrequency(), config.curlSize(), config.shapeFrequency(), CURL_RANGE, config.seed());
    }

    /** The noise of these sizes, frequencies, octaves, curl range and seed in {@code textures}. */
    @AllowAllocation("made once")
    public CloudNoise(Textures textures, int shapeSize, int shapeFrequency, int perlinOctaves, int detailSize,
        int detailFrequency, int curlSize, int curlFrequency, float curlRange, int seed) {
        this.textures = Objects.requireNonNull(textures, "textures");
        this.shapeSize = shapeSize;
        this.shapeFrequency = shapeFrequency;
        this.perlinOctaves = perlinOctaves;
        this.detailSize = detailSize;
        this.detailFrequency = detailFrequency;
        this.curlSize = curlSize;
        this.curlFrequency = curlFrequency;
        this.curlRange = curlRange;
        this.seed = seed;
        shape = textures.dynamic(TextureFormat.RGBA8, shapeSize, shapeSize, shapeSize, true);
        detail = textures.dynamic(TextureFormat.RGBA8, detailSize, detailSize, detailSize, true);
        curl = textures.dynamic(TextureFormat.RGBA8, curlSize, curlSize, 1, true);
    }

    /** The base shape volume's sampled slot (bindless {@code volumes[]}). */
    public int shape() {
        return shape & 0xffff;
    }

    /** The detail volume's sampled slot. */
    public int detail() {
        return detail & 0xffff;
    }

    /** The curl texture's sampled slot (bindless {@code textures[]}). */
    public int curl() {
        return curl & 0xffff;
    }

    public int shapeSize() {
        return shapeSize;
    }

    public int detailSize() {
        return detailSize;
    }

    public int curlSize() {
        return curlSize;
    }

    /** Whether the textures were generated (recorded in a frame). */
    public boolean generated() {
        return generated.get();
    }

    /** The request generating them once; its buffer is what passes reading them declare reading, after it. */
    public Request<Buffer> generate() {
        return new Generate(this);
    }

    /** {@link #generate}'s request: one per noise, shared by every pass reading it. */
    private record Generate(CloudNoise noise) implements Request<Buffer> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Buffer build(GraphBuilder g) {
            CloudNoise n = noise;
            String shader = "shaders/weather/generate.spv";
            Pipeline shapeProgram = g.computeProgram(shader, "shapeMain", NoisePush.BYTES);
            Pipeline detailProgram = g.computeProgram(shader, "detailMain", NoisePush.BYTES);
            Pipeline curlProgram = g.computeProgram(shader, "curlMain", NoisePush.BYTES);
            NoisePush push = new NoisePush();
            Buffer ready = g.createBuffer("cloud noise ready", 16);
            g.pass("cloud noise").write(ready, BufferAccess.TRANSFER_DST).record(ctx -> {
                ctx.fill(ready, 1);
                if (!n.generated.compareAndSet(false, true)) {
                    return;
                }
                ctx.dispatch(shapeProgram, push.image(n.textures.storageSlot(n.shape)).size(n.shapeSize)
                    .frequency(n.shapeFrequency).octaves(n.perlinOctaves).seed(n.seed).range(n.curlRange),
                    (n.shapeSize + 3) / 4, (n.shapeSize + 3) / 4, (n.shapeSize + 3) / 4);
                ctx.dispatch(detailProgram, push.image(n.textures.storageSlot(n.detail)).size(n.detailSize)
                    .frequency(n.detailFrequency).octaves(n.perlinOctaves).seed(n.seed).range(n.curlRange),
                    (n.detailSize + 3) / 4, (n.detailSize + 3) / 4, (n.detailSize + 3) / 4);
                ctx.dispatch(curlProgram, push.image(n.textures.storageSlot(n.curl)).size(n.curlSize)
                    .frequency(n.curlFrequency).octaves(n.perlinOctaves).seed(n.seed).range(n.curlRange),
                    (n.curlSize + 7) / 8, (n.curlSize + 7) / 8, 1);
                // Outside the graph: the writes made visible to every later read, as dynamic textures ask.
                Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            });
            return ready;
        }
    }

    @Override
    public void close() {
        textures.remove(shape);
        textures.remove(detail);
        textures.remove(curl);
    }
}
