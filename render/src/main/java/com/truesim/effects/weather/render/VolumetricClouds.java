package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.render.layout.CompositePush;
import com.truesim.effects.weather.render.layout.MarchPush;
import com.truesim.effects.weather.render.layout.TemporalPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.History;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.function.Supplier;

/**
 * The ray-marched tiers of {@link Clouds} ({@link Clouds#VOLUMETRIC}, {@link Clouds#LAYERED}): three compute passes
 * over a view.
 * <ol>
 * <li><b>March</b> (march.slang), at {@code 1 / resolution} of the view's pixels: per pixel, the ray through the
 *     cloud slab to the scene's farthest depth under the pixel or the range; Schneider's two-speed march with detail
 *     where it meets cloud (and for the first {@code near} metres: fly-through), a cone march towards the sun,
 *     Wrenninge's octaves, ambient by height, the flashes' diffusion glow, rain shafts, Hillaire 2016's step
 *     integration and the aerial perspective at the clouds' mean depth. A start jittered per pixel and frame.</li>
 * <li><b>Temporal</b> (temporal.slang): blended with the history reprojected by the clouds' mean depth, clamped to the
 *     neighbourhood; less history while a flash lights the clouds, none when {@code temporal} is off.</li>
 * <li><b>Composite</b> (composite.slang): upsampled joint-bilaterally on depth over the scene's colour, scene · T +
 *     L.</li>
 * </ol>
 * The noise volumes and the Beer shadow map are shared requests ({@link CloudNoise#generate}, {@link CloudShadowMap}).
 * Its tunables are read when frames record, except the resolution (a rebuild).
 */
final class VolumetricClouds implements Technique {

    /** The march's light is this much smaller than the radiance in its images (march.slang's OUTPUT_SCALE). */
    static final float OUTPUT_SCALE = 1024;

    private static final int DETAIL = 1;
    private static final int GROUND = 2;
    private static final int RAIN = 4;

    private final WeatherGpu gpu;
    private final SceneView view;
    private final Supplier<Clouds.March> config;

    VolumetricClouds(Clouds.Context context, Supplier<Clouds.March> config) {
        this.gpu = context.gpu();
        this.view = context.view();
        this.config = config;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        CloudNoise noise = gpu.noise();
        Buffer ready = g.request(noise.generate());
        Image shadow = g.request(gpu.shadowMap(ready));
        int resolution = Math.max(1, config.get().resolution());
        int width = Math.max(1, (view.width() + resolution - 1) / resolution);
        int height = Math.max(1, (view.height() + resolution - 1) / resolution);
        ImageDesc light = ImageDesc.of(Format.R16G16B16A16_SFLOAT, width, height);
        ImageDesc depths = ImageDesc.of(Format.R32G32_SFLOAT, width, height);
        Image march = g.create("cloud march", light);
        Image marchDepth = g.create("cloud march depth", depths);
        History history = g.history("cloud history", light);
        History historyDepth = g.history("cloud history depth", depths);
        Pipeline marchProgram = g.computeProgram("shaders/weather/march.spv", "marchMain", MarchPush.BYTES);
        Pipeline temporalProgram = g.computeProgram("shaders/weather/temporal.spv", "temporalMain",
            TemporalPush.BYTES);
        Pipeline compositeProgram = g.computeProgram("shaders/weather/composite.spv", "compositeMain",
            CompositePush.BYTES);
        MarchPush marchPush = new MarchPush();
        TemporalPush temporalPush = new TemporalPush();
        CompositePush compositePush = new CompositePush();

        g.pass("cloud march").read(depth, Access.SAMPLED_COMPUTE).read(ready, BufferAccess.STORAGE_READ)
            .read(shadow, Access.SAMPLED_COMPUTE).write(march, Access.STORAGE_WRITE)
            .write(marchDepth, Access.STORAGE_WRITE).record(ctx -> {
                Clouds.March c = config.get();
                Camera camera = view.camera();
                int fullWidth = ctx.regionWidth(color);
                int fullHeight = ctx.regionHeight(color);
                int w = Math.min(width, (fullWidth + resolution - 1) / resolution);
                int h = Math.min(height, (fullHeight + resolution - 1) / resolution);
                int flags = (c.detail() ? DETAIL : 0) | (c.ground() ? GROUND : 0) | (c.rain() ? RAIN : 0);
                ctx.dispatch(marchProgram, marchPush.view(view.view().address()).frame(gpu.address())
                    .output(ctx.storage(march)).depthOutput(ctx.storage(marchDepth)).depth(ctx.sampled(depth))
                    .shape(noise.shape()).detail(noise.detail())
                    .curl(noise.curl()).shadow(ctx.sampled(shadow)).size(w | h << 16)
                    .fullSize(fullWidth | fullHeight << 16).frameIndex(c.temporal() ? (int) ctx.frame() : 0)
                    .camera((float) (camera.position(0) - gpu.anchorX()), (float) camera.position(1),
                        (float) (camera.position(2) - gpu.anchorZ()))
                    .steps(c.steps()).range(c.range()).near(c.near()).lightSteps(c.lightSteps())
                    .lightStep(c.lightStep()).flags(flags), (w + 7) / 8, (h + 7) / 8, 1);
            });

        g.pass("cloud temporal").read(march, Access.SAMPLED_COMPUTE).read(marchDepth, Access.SAMPLED_COMPUTE)
            .read(history.previous(), Access.SAMPLED_COMPUTE).read(historyDepth.previous(), Access.SAMPLED_COMPUTE)
            .write(history.current(), Access.STORAGE_WRITE).write(historyDepth.current(), Access.STORAGE_WRITE)
            .record(ctx -> {
                Clouds.March c = config.get();
                int w = Math.min(width, (ctx.regionWidth(color) + resolution - 1) / resolution);
                int h = Math.min(height, (ctx.regionHeight(color) + resolution - 1) / resolution);
                boolean valid = c.temporal() && ctx.historyValid(history);
                ctx.dispatch(temporalProgram, temporalPush.view(view.view().address()).current(ctx.sampled(march))
                    .currentDepth(ctx.sampled(marchDepth)).history(ctx.sampled(history.previous()))
                    .historyDepth(ctx.sampled(historyDepth.previous())).output(ctx.storage(history.current()))
                    .outputDepth(ctx.storage(historyDepth.current())).size(w | h << 16)
                    .blend(gpu.flashLit() ? c.flashBlend() : c.blend()).valid(valid ? 1 : 0), (w + 7) / 8,
                    (h + 7) / 8, 1);
            });

        g.pass("cloud composite").read(depth, Access.SAMPLED_COMPUTE)
            .read(history.current(), Access.SAMPLED_COMPUTE).read(historyDepth.current(), Access.SAMPLED_COMPUTE)
            .write(color, Access.STORAGE_WRITE).record(ctx -> {
                Camera camera = view.camera();
                int fullWidth = ctx.regionWidth(color);
                int fullHeight = ctx.regionHeight(color);
                int w = Math.min(width, (fullWidth + resolution - 1) / resolution);
                int h = Math.min(height, (fullHeight + resolution - 1) / resolution);
                ctx.dispatch(compositeProgram, compositePush.view(view.view().address()).frame(gpu.address())
                    .color(ctx.storage(color)).depth(ctx.sampled(depth)).clouds(ctx.sampled(history.current()))
                    .cloudDepth(ctx.sampled(historyDepth.current())).size(fullWidth | fullHeight << 16)
                    .marchSize(w | h << 16)
                    .camera((float) (camera.position(0) - gpu.anchorX()), (float) camera.position(1),
                        (float) (camera.position(2) - gpu.anchorZ()))
                    .scale(OUTPUT_SCALE), (fullWidth + 7) / 8, (fullHeight + 7) / 8, 1);
            });
    }
}
