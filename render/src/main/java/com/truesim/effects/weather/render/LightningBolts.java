package com.truesim.effects.weather.render;

import com.truesim.common.math.Maths;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherSample;
import com.truesim.effects.weather.lightning.BoltGeometry;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.effects.weather.lightning.LightningEvents;
import com.truesim.effects.weather.render.layout.BoltPush;
import com.truesim.effects.weather.render.layout.BoltSegmentLayout;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Load;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Store;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Transparency;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.ShaderModule;
import com.truesim.engine.vulkan.VulkanDevice;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Visible lightning bolts: the channels of the ground flashes lit this frame ({@link WeatherGpu#flashes()}), each made
 * by weather-lightning's {@link BoltGeometry} from its endpoints and seed (the same bolt on every machine), drawn as
 * camera-facing strips of the channel's luminance (bolts.slang) over the scene after the transparents. A strip's
 * luminance is the flash's intensity this frame over the main channel's length times the strip's width, times the
 * segment's brightness (branches dimmer); above the cloud base under it a segment fades over {@code fade} metres (the
 * cloud hides it; the glow inside is the clouds'). Only where the lightning tier is {@code FULL}.
 *
 * <pre>{@code
 * LightningBolts bolts = new LightningBolts(device, slots, gpu, 3, 300);
 * view.port().add(bolts.view(view));
 * // every frame, after gpu.update:
 * bolts.update();
 * }</pre>
 */
public final class LightningBolts implements AutoCloseable {

    /** The id of a view's bolts. */
    public static final Id<Technique> ID = Id.of("weather.lightningBolts", Technique.class);

    private final VulkanDevice device;
    private final WeatherGpu gpu;
    private final BoltGeometry bolt;
    private final FrameBuffer segments;
    private final int capacity;
    private final float width;
    private final float fade;
    private final WeatherSample sample = new WeatherSample();
    private final Map<Integer, Pipeline> pipelines = new HashMap<>();
    private int count;

    /**
     * Bolts of {@code gpu}'s weather: strips {@code width} metres wide, fading over {@code fade} metres above the cloud
     * base; room for every flash {@code gpu} lights at once.
     */
    @AllowAllocation("made once")
    public LightningBolts(VulkanDevice device, FrameSlots slots, WeatherGpu gpu, float width, float fade) {
        this.device = Objects.requireNonNull(device, "device");
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.width = width;
        this.fade = fade;
        LightningConfig config = gpu.weather().config().lightning();
        bolt = new BoltGeometry(config.maxBoltPoints());
        capacity = config.maxBoltPoints() * com.truesim.effects.weather.render.layout.CloudsConstants.MAX_FLASHES;
        segments = FrameBuffer.create(device, (long) BoltSegmentLayout.BYTES * capacity,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
    }

    /** The segments drawn this frame. */
    public int segments() {
        return count;
    }

    /** Makes the frame's bolts: once a frame, after {@link WeatherGpu#update}. Allocates nothing. */
    public void update() {
        count = 0;
        Weather weather = gpu.weather();
        LightningConfig config = weather.config().lightning();
        if (config.tier() != LightningConfig.Tier.FULL) {
            return;
        }
        LightningEvents events = weather.lightning();
        ActiveFlashes flashes = gpu.flashes();
        for (int i = 0; i < flashes.count(); i++) {
            int slot = flashes.slot(i);
            if (events.type(slot) == FlashType.INTRACLOUD) {
                continue;
            }
            bolt.generate(events.origin(slot, 0), events.origin(slot, 1), events.origin(slot, 2), events.end(slot, 0),
                events.end(slot, 1), events.end(slot, 2), events.seed(slot), config);
            double main = Math.max(mainLength(), 1);
            double luminance = flashes.intensity(i) / (main * width);
            for (int s = 0; s < bolt.segmentCount() && count < capacity; s++) {
                int p = bolt.segmentStart(s);
                int q = bolt.segmentEnd(s);
                double mx = 0.5 * (bolt.x(p) + bolt.x(q));
                double my = 0.5 * (bolt.y(p) + bolt.y(q));
                double mz = 0.5 * (bolt.z(p) + bolt.z(q));
                weather.sample(mx, my, mz, sample);
                double base = sample.coverage() > 0.05
                    ? gpu.types().profile(sample.cloudType()).base() : Double.POSITIVE_INFINITY;
                double up = my - gpu.anchorY(); // the altitude, as the profiles' base
                double hidden = up > base ? Maths.exp(-(up - base) / fade) : 1;
                long o = (long) count * BoltSegmentLayout.BYTES;
                segments.putFloat(o + BoltSegmentLayout.A, (float) (bolt.x(p) - gpu.anchorX()));
                segments.putFloat(o + BoltSegmentLayout.A + 4, (float) (bolt.y(p) - gpu.anchorY()));
                segments.putFloat(o + BoltSegmentLayout.A + 8, (float) (bolt.z(p) - gpu.anchorZ()));
                segments.putFloat(o + BoltSegmentLayout.LUMINANCE, (float) (luminance * bolt.brightness(s) * hidden));
                segments.putFloat(o + BoltSegmentLayout.B, (float) (bolt.x(q) - gpu.anchorX()));
                segments.putFloat(o + BoltSegmentLayout.B + 4, (float) (bolt.y(q) - gpu.anchorY()));
                segments.putFloat(o + BoltSegmentLayout.B + 8, (float) (bolt.z(q) - gpu.anchorZ()));
                segments.putFloat(o + BoltSegmentLayout.WIDTH, width * (bolt.depth(s) == 0 ? 1 : 0.6f));
                count++;
            }
        }
    }

    /** The main channel's length: its segments of depth 0. */
    private double mainLength() {
        double sum = 0;
        for (int s = 0; s < bolt.segmentCount(); s++) {
            if (bolt.depth(s) == 0) {
                int p = bolt.segmentStart(s);
                int q = bolt.segmentEnd(s);
                double dx = bolt.x(q) - bolt.x(p);
                double dy = bolt.y(q) - bolt.y(p);
                double dz = bolt.z(q) - bolt.z(p);
                sum += Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }
        return sum;
    }

    /** The pipeline drawing into colour format {@code vk}: made once per format. */
    @AllowAllocation("a pipeline made once per colour format")
    synchronized Pipeline pipeline(int vk) {
        return pipelines.computeIfAbsent(vk, format -> {
            try (ShaderModule shaders = ShaderModule.load(device, "shaders/weather/bolts.spv")) {
                return Pipeline.graphics(device).stage(VK_SHADER_STAGE_VERTEX_BIT, shaders, "boltVertexMain")
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT, shaders, "boltFragmentMain").color(format)
                    .blend(0, Pipeline.Blend.ADDITIVE).pushConstants(BoltPush.BYTES).doubleSided().build();
            }
        });
    }

    /** The bolts over {@code view}'s scene: a technique of its port. */
    public Technique view(SceneView view) {
        return new View(this, view);
    }

    /** A view's bolts. */
    private record View(LightningBolts bolts, SceneView view) implements Technique {

        @Override
        public Id<? extends Technique> id() {
            return ID;
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(Keys.SCENE_COLOR);
        }

        @Override
        public List<Key<?>> reads() {
            return List.of(Keys.SCENE_DEPTH);
        }

        @Override
        public Order order() {
            return Order.at(Anchor.TRANSPARENT).after(Transparency.ID);
        }

        @Override
        @AllowAllocation("building the graph, when it changes")
        public void build(GraphBuilder g) {
            Checks.inView(g, view, this);
            Image color = g.modify(Keys.SCENE_COLOR);
            Image depth = g.read(Keys.SCENE_DEPTH);
            Pipeline pipeline = bolts.pipeline(color.format().vk());
            BoltPush push = new BoltPush();
            WeatherGpu gpu = bolts.gpu;
            g.pass("lightning bolts").read(depth, Access.SAMPLED_FRAGMENT).attach(color, Load.LOAD, Store.STORE)
                .region().record(ctx -> {
                    int n = bolts.count;
                    if (n == 0) {
                        return;
                    }
                    Camera camera = view.camera();
                    ctx.draw(pipeline, push.view(view.view().address()).segments(bolts.segments.deviceAddress())
                        .depth(ctx.sampled(depth)).size(ctx.regionWidth(color) | ctx.regionHeight(color) << 16)
                        .camera((float) (camera.position(0) - gpu.anchorX()), (float) (camera.position(1) - gpu.anchorY()),
                            (float) (camera.position(2) - gpu.anchorZ())).minPixels(1.5f), 6 * n, 1);
                });
        }
    }

    @Override
    public void close() {
        for (Pipeline pipeline : pipelines.values()) {
            pipeline.close();
        }
        segments.close();
    }
}
