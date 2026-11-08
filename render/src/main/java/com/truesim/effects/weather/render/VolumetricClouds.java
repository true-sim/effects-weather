package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereConfig;
import com.truesim.effects.atmosphere.render.SkyViewLut;
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
 *     integration and the aerial perspective at the clouds' mean depth (effects-atmosphere's tables where it lights
 *     the clouds: {@link CloudLighting#tables}). A start jittered per pixel and frame; one pixel of each
 *     {@code interleave}² block a frame (Bayer order), every pixel while a flash lights the clouds.</li>
 * <li><b>Temporal</b> (temporal.slang), depth-aware (only the pixels and history taps that march to the same depth):
 *     blended with the history reprojected by the clouds' mean depth where the wind
 *     carried them from ({@link WeatherGpu#motion}), clamped to the neighbourhood, fading with the weather time a frame
 *     spans ({@code historySeconds}); the pixels not marched this frame their history; less history while a flash
 *     lights the clouds, none when {@code temporal} is off.</li>
 * <li><b>Composite</b> (composite.slang): upsampled joint-bilaterally on depth over the scene's colour, scene · T +
 *     L.</li>
 * </ol>
 * The noise volumes and the Beer shadow map are shared requests ({@link CloudNoise#generate}, {@link CloudShadowMap}).
 * Its tunables are read when frames record, except the resolution (a rebuild).
 */
final class VolumetricClouds implements Technique {

    /** The march's light is this much smaller than the physical radiance (cd/m²) in its images: half floats' range. */
    static final float OUTPUT_SCALE = 1024;

    private static final int DETAIL = 1;
    private static final int GROUND = 2;
    private static final int RAIN = 4;
    private static final int TABLES = 8;
    private static final int SKIP = 16;
    private static final int NO_AERIAL = 0xffff;

    private final WeatherGpu gpu;
    private final SceneView view;
    private final Supplier<Clouds.March> config;

    VolumetricClouds(Clouds.Context context, Supplier<Clouds.March> config) {
        this.gpu = context.gpu();
        this.view = context.view();
        this.config = config;
    }

    /**
     * The interleave this frame: the setting's, but every pixel while a flash lights the clouds (they answer within the
     * frame) and when a frame spans so much weather time that the history counts for less than half (a time-lapse:
     * the pixels not marched would show the blocks' spatial estimate).
     */
    private int interleave(Clouds.March c) {
        if (!c.temporal() || gpu.flashLit()) {
            return 1;
        }
        if (c.historySeconds() > 0 && Maths.exp(-gpu.frameSeconds() / c.historySeconds()) < 0.5) {
            return 1;
        }
        return Math.max(1, c.interleave());
    }

    /** Bayer orders of the interleave's blocks: each pixel of a 2 x 2 or 4 x 4 block once, spread out in time. */
    private static final int[] BAYER2 = {0, 3, 1, 2};
    private static final int[] BAYER4 = {0, 10, 2, 8, 5, 15, 7, 13, 1, 11, 3, 9, 4, 14, 6, 12};

    /** The pixel of each {@code s} x {@code s} block marched in frame {@code frame}: x << 8 | y << 16. */
    static int phase(int s, long frame) {
        if (s <= 1) {
            return 0;
        }
        int n = s * s;
        int k = (int) Math.floorMod(frame, (long) n);
        int index = s == 2 ? BAYER2[k] : s == 4 ? BAYER4[k] : (k * 5) % n;
        return (index % s) << 8 | (index / s) << 16;
    }

    /**
     * The aerial perspective volume's range this frame, km, as effects-atmosphere's {@code SkyViewLut} makes it: its
     * config's, or from above the atmosphere as far as the planet's far horizon.
     */
    static float aerialRange(Atmosphere atmosphere) {
        AtmosphereConfig c = atmosphere.config();
        double r = c.planetRadius() + atmosphere.cameraAltitudeKm();
        double horizon = Math.sqrt(Math.max(r * r - (double) c.planetRadius() * c.planetRadius(), 0));
        return (float) Math.max(c.aerialRange(), Math.min(horizon * 1.2, 20_000));
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
        Buffer maps = g.request(gpu.maps());
        Image shadow = g.request(gpu.shadowMap(ready));
        // effects-atmosphere's tables and aerial perspective volume, where it lights the clouds.
        Atmosphere atmosphere = gpu.tables();
        SkyViewLut.Images sky = atmosphere != null ? g.request(new SkyViewLut(atmosphere)) : null;
        if (atmosphere != null) {
            g.request(gpu.skyLight(atmosphere)); // the clouds' ambient from its sky, for the frames after
        }
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

        GraphBuilder.PassBuilder marchPass = g.pass("cloud march").read(depth, Access.SAMPLED_COMPUTE)
            .read(ready, BufferAccess.STORAGE_READ).read(maps, BufferAccess.STORAGE_READ)
            .read(shadow, Access.SAMPLED_COMPUTE).write(march, Access.STORAGE_WRITE)
            .write(marchDepth, Access.STORAGE_WRITE);
        if (sky != null) {
            marchPass.read(sky.tables().transmittance(), Access.SAMPLED_COMPUTE)
                .read(sky.tables().multipleScattering(), Access.SAMPLED_COMPUTE);
            if (sky.scattered() != null) {
                marchPass.read(sky.scattered(), Access.SAMPLED_COMPUTE).read(sky.transmitted(), Access.SAMPLED_COMPUTE);
            }
        }
        marchPass.record(ctx -> {
                Clouds.March c = config.get();
                Camera camera = view.camera();
                int fullWidth = ctx.regionWidth(color);
                int fullHeight = ctx.regionHeight(color);
                int w = Math.min(width, (fullWidth + resolution - 1) / resolution);
                int h = Math.min(height, (fullHeight + resolution - 1) / resolution);
                int flags = (c.detail() ? DETAIL : 0) | (c.ground() ? GROUND : 0) | (c.rain() ? RAIN : 0)
                    | (sky != null ? TABLES : 0) | (c.skip() ? SKIP : 0) | Math.clamp(c.lightEvery(), 1, 15) << 8;
                if (sky != null && sky.scattered() != null) {
                    marchPush.atmosphere(atmosphere.frameAddress()).aerial(ctx.sampled(sky.scattered())
                        | ctx.sampled(sky.transmitted()) << 16).apFar(aerialRange(atmosphere));
                } else {
                    marchPush.atmosphere(sky != null ? atmosphere.frameAddress() : 0)
                        .aerial(NO_AERIAL | NO_AERIAL << 16).apFar(1);
                }
                int s = interleave(c);
                int sparse = s | phase(s, ctx.frame());
                ctx.dispatch(marchProgram, marchPush.view(view.view().address()).frame(gpu.address())
                    .outputs(ctx.storage(march) | ctx.storage(marchDepth) << 16).depth(ctx.sampled(depth))
                    .noise(noise.shape() | noise.detail() << 16).maps(noise.curl() | ctx.sampled(shadow) << 16)
                    .size(w | h << 16)
                    .fullSize(fullWidth | fullHeight << 16).frameIndex(c.temporal() ? (int) ctx.frame() : 0)
                    .camera((float) (camera.position(0) - gpu.anchorX()), (float) camera.position(1),
                        (float) (camera.position(2) - gpu.anchorZ()))
                    .counts(c.steps() | c.lightSteps() << 16 | Math.min(c.lightStepsFar(), c.lightSteps()) << 24)
                    .range(c.range()).near(c.near()).lightFar(c.lightFar()).lightStep(c.lightStep()).flags(flags)
                    .outputScale((float) (OUTPUT_SCALE * gpu.units())).sparse(sparse)
                    .fineRatio(c.fineRatio()).opaque(c.opaque()).detailFar(c.detailFar()),
                    ((w + s - 1) / s + 7) / 8, ((h + s - 1) / s + 7) / 8, 1);
            });

        g.pass("cloud temporal").read(march, Access.SAMPLED_COMPUTE).read(marchDepth, Access.SAMPLED_COMPUTE)
            .read(depth, Access.SAMPLED_COMPUTE)
            .read(history.previous(), Access.SAMPLED_COMPUTE).read(historyDepth.previous(), Access.SAMPLED_COMPUTE)
            .write(history.current(), Access.STORAGE_WRITE).write(historyDepth.current(), Access.STORAGE_WRITE)
            .record(ctx -> {
                Clouds.March c = config.get();
                int w = Math.min(width, (ctx.regionWidth(color) + resolution - 1) / resolution);
                int h = Math.min(height, (ctx.regionHeight(color) + resolution - 1) / resolution);
                boolean valid = c.temporal() && ctx.historyValid(history);
                int s = interleave(c);
                int sparse = s | phase(s, ctx.frame());
                // The history counts for less the more weather time the frame spans (the shapes evolve): a
                // time-lapse keeps sharp clouds instead of streaks.
                double blend = gpu.flashLit() ? c.flashBlend() : c.blend();
                if (c.historySeconds() > 0) {
                    blend *= Maths.exp(-gpu.frameSeconds() / c.historySeconds());
                }
                ctx.dispatch(temporalProgram, temporalPush.view(view.view().address()).current(ctx.sampled(march))
                    .currentDepth(ctx.sampled(marchDepth)).history(ctx.sampled(history.previous()))
                    .historyDepth(ctx.sampled(historyDepth.previous())).output(ctx.storage(history.current()))
                    .outputDepth(ctx.storage(historyDepth.current())).size(w | h << 16)
                    .blend((float) blend).valid(valid ? 1 : 0).motion((float) gpu.motion(0),
                        (float) gpu.motion(1), (float) gpu.motion(2)).sparse(sparse).depth(ctx.sampled(depth))
                    .fullSize(ctx.regionWidth(color) | ctx.regionHeight(color) << 16).range(c.range()), (w + 7) / 8,
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
                    .scale((float) (OUTPUT_SCALE * gpu.units())), (fullWidth + 7) / 8, (fullHeight + 7) / 8, 1);
            });
    }
}
