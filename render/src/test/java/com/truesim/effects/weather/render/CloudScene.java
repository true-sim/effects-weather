package com.truesim.effects.weather.render;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_D32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_STORAGE_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherDomain;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.clouds.CloudTypes;
import com.truesim.effects.weather.clouds.CloudsConfig;
import com.truesim.effects.weather.render.layout.GradePush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.common.graph.Id;
import com.truesim.engine.render.DisplayTransform;
import com.truesim.engine.vulkan.DisplayEncoding;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.effects.atmosphere.render.AerialPerspective;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.ClusteredForwardShading;
import com.truesim.engine.render.ClusteredLights;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.GpuScene;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.scene.LightSync;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuBuffer;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import com.truesim.scene.meshbuild.MeshBuilder;
import com.truesim.scene.assets.mesh.MeshData;
import com.truesim.scene.assets.mesh.VertexAttribute;
import com.truesim.scene.assets.mesh.VertexFormat;
import com.truesim.common.math.color.Color;
import java.lang.foreign.ValueLayout;
import java.util.List;
import org.lwjgl.vulkan.VkBufferImageCopy;

/**
 * A weather scene for the tests, screenshots and benchmarks: a flat ground (a large quad of grassland's albedo, 0.2,
 * green-brown), the sun as a scene node's directional light (lux at the ground) placed by {@link LightSync}, a weather
 * of the given quality and preset, its GPU side, a camera, forward shading with the light clusters, the clouds
 * ({@link Clouds}), the clouds' shadow on the ground ({@link CloudShadowMask}), and the scene's colour (RGBA16F,
 * physical or the atmosphere's scene units) read back after each frame; and what a display shows of it: white balanced
 * as a camera set to the daylight ({@link #whiteBalance}), then the engine's display transform (engine 0.193.0:
 * {@code display.toneCurve} AgX, {@code display.exposure}, sRGB, dithered) read back.
 */
final class CloudScene implements AutoCloseable {

    final TestGpu gpu;
    final int width;
    final int height;
    final SceneGraph nodes = new SceneGraph(new ChangeClock());
    final Weather weather;
    final WeatherGpu clouds;
    final Lights lights;
    final LightSync lightSync;
    final GpuScene scene;
    final MeshletRenderer renderer;
    final Camera camera;
    final SceneView view;
    final EngineSettings settings;
    final long sun;
    final Materials materials;
    final LightningBolts bolts;
    final Environment environment;
    final double[] below = new double[3];
    final Textures textures;
    final Atmosphere atmosphere;
    final CloudLighting sky;
    private final double[] at = new double[3];
    final int groundMaterial;
    private final GpuBuffer pixels;
    private final GpuBuffer shown;
    /** The white balance applied before the display transform (per channel). */
    final float[] gains = {1, 1, 1};
    /** The display transform's output, the view's (sRGB-encoded values in [0, 1]). */
    static final Key<Image> DISPLAY = Key.image("test.display");
    /** The ground's albedo: grassland's, about 0.2, green-brown. */
    static final Color GROUND = new Color(0.20f, 0.22f, 0.12f, 1);
    private double time;
    LightningLights flashLights;

    CloudScene(int width, int height, Quality quality, WeatherPreset preset, boolean shadowMask) {
        this(width, height, WeatherConfig.forQuality(quality), quality, preset, shadowMask, false);
    }

    /**
     * With {@code atmosphere}, effects-atmosphere's sky and aerial perspective, its exposure in its scene units
     * (EV100: {@link #ev100}); else the analytic test sky in physical units. The environment either way: the sky's
     * light under the clouds ({@link WeatherGpu#skyBelowClouds}), uniform.
     */
    CloudScene(int width, int height, WeatherConfig config, Quality quality, WeatherPreset preset,
        boolean shadowMask, boolean atmosphere) {
        this.width = width;
        this.height = height;
        gpu = TestGpu.open();
        settings = gpu.graph.settings();
        CloudSettings.forQuality(settings, quality);
        weather = new Weather(config, WeatherDomain.flat(), preset);
        GeometryPool pool = gpu.own(new GeometryPool(gpu.device, GeometryPool.Capacity.of(64, 4, 4),
            gpu.graph.frameSlots()));
        scene = gpu.own(new GpuScene(gpu.device, pool, 16, gpu.graph.frameSlots()));
        renderer = gpu.own(new MeshletRenderer(gpu.device, scene, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT,
            gpu.graph.frameSlots()));
        materials = gpu.own(new Materials(gpu.device, 8, gpu.graph.frameSlots()));
        textures = gpu.own(new Textures(gpu.device, gpu.graph.frameSlots()));
        scene.setMaterials(materials);
        lights = gpu.own(new Lights(gpu.device, 16, 16, gpu.graph.frameSlots()));
        lightSync = new LightSync(nodes, lights);
        clouds = gpu.own(new WeatherGpu(gpu.device, gpu.graph.frameSlots(), textures, weather,
            CloudTypes.physical(), CloudsConfig.forQuality(quality), settings));
        this.atmosphere = atmosphere ? gpu.own(new Atmosphere(gpu.device, gpu.graph.frameSlots(), settings)) : null;
        sky = atmosphere ? new AtmosphereLighting(this.atmosphere) : new SceneSky(nodes, CloudLighting.Atmosphere.EARTH);
        sun = nodes.create();
        nodes.components().set(sun, LightSource.TYPE, LightSource.directional(1, 0.98f, 0.95f, 100_000));
        if (sky instanceof SceneSky scene) {
            scene.sun(sun);
        }
        sun(35, 120);

        int ground = pool.add(quad());
        groundMaterial = materials.add(new Material(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(GROUND).withRoughness(1)));
        int grey = groundMaterial;
        // The ground: the grid (facing +z) laid flat, 400 km across, curving down with the planet.
        float s = (float) Math.sqrt(0.5);
        scene.set(0, ground, grey, 0, 0, 0, -s, 0, 0, s, 200_000, 200_000, 1);

        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        GpuImage target = gpu.own(new GpuImage(gpu.device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage,
            VK_IMAGE_ASPECT_COLOR_BIT));
        ExternalImage output = new ExternalImage("target", width, height, Format.R16G16B16A16_SFLOAT, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = gpu.own(GpuBuffer.hostVisible(gpu.device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
        shown = gpu.own(GpuBuffer.hostVisible(gpu.device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
        DisplayTransform.curve(settings, DisplayTransform.Curve.AGX);
        camera = new Camera();
        look(0, 2, 0, 0, 0.3, -1, Math.toRadians(70));
        SceneGeometry geometry = SceneGeometry.meshlets(renderer);
        ViewPort port = gpu.graph.addView("main").size(width, height);
        view = geometry.createView(port, camera, ViewOptions.of(256, 1 << 12));
        port.add(new ClusteredForwardShading(geometry, view, VK_FORMAT_R16G16B16A16_SFLOAT, VK_FORMAT_D32_SFLOAT)
            .clearColor(0, 0, 0)).add(new ClusteredLights(lights, view));
        if (shadowMask) {
            port.add(gpu.own(new CloudShadowMask(clouds, view, renderer, lights, 0)));
        }
        bolts = gpu.own(new LightningBolts(gpu.device, gpu.graph.frameSlots(), clouds, 3, 400));
        environment = Environment.uniform("sky under the clouds", 0, 0, 0);
        port.add(new EnvironmentLighting(environment, view));
        if (atmosphere) {
            port.add(new AtmosphereSky(this.atmosphere, view))
                .add(new AerialPerspective(this.atmosphere, view));
        } else {
            port.add(new TestSky(clouds, view, below));
        }
        port.add(new Clouds(clouds, view)).add(bolts.view(view)).add(new Capture(pixels, width, height))
            .add(new WhiteBalance(gains)).add(new Last(new DisplayTransform().transform(Keys.SCENE_COLOR, DISPLAY,
                DisplayEncoding.SDR_UNORM))).add(new CaptureDisplay(shown, width, height)).output(DISPLAY, output);
        weather.refreshAll();
    }

    /**
     * A camera's white balance for daylight here: gains making the light on a horizontal surface (the sun at the camera
     * and the sky) grey; none (null) when the sun is down.
     */
    double[] whiteBalance() {
        double[] direction = new double[3];
        double[] top = new double[3];
        double[] skyLight = new double[3];
        sky.sun(direction, top);
        if (atmosphere == null || direction[1] <= 0.02) {
            return null;
        }
        atmosphere.lightAtCamera(0, top);
        sky.sky(skyLight);
        double[] white = new double[3];
        for (int c = 0; c < 3; c++) {
            white[c] = top[c] * direction[1] + skyLight[c];
        }
        double y = 0.2126 * white[0] + 0.7152 * white[1] + 0.0722 * white[2];
        return new double[] {y / white[0], y / white[1], y / white[2]};
    }

    /** Sets the atmosphere's exposure (EV100: its scene units): pictures then need no exposure of their own. */
    CloudScene ev100(double ev) {
        settings.set(AtmosphereSky.ATMOSPHERE.setting("ev100"), (float) ev);
        return this;
    }

    /** The time the frames have reached, seconds. */
    double time() {
        return time;
    }

    /** The sun's illuminance at the ground, lux (0: night). */
    CloudScene sunIlluminance(double lux) {
        nodes.components().get(sun, LightSource.TYPE).intensity(lux);
        return this;
    }

    /** Flash lights in the scene ({@link LightningLights}), {@code count} of them. */
    CloudScene flashLights(int count) {
        flashLights = new LightningLights(nodes, com.truesim.scene.core.NodeHandle.NONE, count, 30_000);
        return this;
    }

    /** The ground's material: grey of {@code albedo}. */
    CloudScene ground(float albedo) {
        materials.set(groundMaterial, new Material(MetallicRoughnessParameters.DEFAULTS
            .withBaseColor(new Color(albedo, albedo, albedo, 1)).withRoughness(1)));
        return this;
    }

    /** The sun at {@code elevation} degrees above the horizon, from azimuth {@code azimuth} (degrees from north, east 90). */
    CloudScene sun(double elevation, double azimuth) {
        double e = Math.toRadians(elevation);
        double a = Math.toRadians(azimuth);
        // Towards the sun: x east, y up, z south.
        double tx = Math.cos(e) * Math.sin(a);
        double ty = Math.sin(e);
        double tz = -Math.cos(e) * Math.cos(a);
        // The node's -Z shines along -towards: +Z is towards the sun. A rotation taking +Z to t.
        double cx = -ty;
        double cy = tx;
        double dot = tz;
        double qw = Math.sqrt((1 + dot) / 2);
        double k = qw > 1e-9 ? 1 / (2 * qw) : 0;
        nodes.setRotation(sun, cx * k, cy * k, 0, qw);
        if (atmosphere != null) {
            atmosphere.fixedSun(tx, ty, tz, 128_000); // the solar illuminance outside the atmosphere
        }
        return this;
    }

    /** The camera at (x, y, z) looking along (fx, fy, fz), {@code fovY} radians. */
    CloudScene look(double x, double y, double z, double fx, double fy, double fz, double fovY) {
        camera.position(x, y, z).set(fx, fy, fz, 0, 1, 0, fovY, width, height, 0.5);
        return this;
    }

    /** {@code n} frames of {@code seconds} of weather each; the last frame's colour, RGB floats row by row. */
    float[] frames(int n, double seconds) {
        return frames(n, seconds, true);
    }

    /** Sets the frames' clock (the flashes' and the noise's time) to {@code seconds} of the weather's time. */
    CloudScene at(double seconds) {
        time = seconds;
        return this;
    }

    /** {@code n} frames; the last frame's colour where {@code read}, else null. */
    float[] frames(int n, double seconds, boolean read) {
        if (weather.time() > time + 2) {
            time = weather.time(); // the weather was run on its own: the frames follow its clock
        }
        for (int f = 0; f < n; f++) {
            weather.setFocus(camera.position(0), camera.position(1), camera.position(2));
            weather.advance(seconds);
            time += seconds;
            nodes.update(); // the sun's node moved: the sky reads its world transform
            if (atmosphere != null) {
                atmosphere.update(null, camera.position(0), camera.position(1), camera.position(2));
                // The scene's sun: the atmosphere's at the camera, scene units.
                atmosphere.lightAtCamera(0, at);
                double m = Math.max(at[0], Math.max(at[1], Math.max(at[2], 1e-30)));
                nodes.components().get(sun, LightSource.TYPE).color((float) (at[0] / m), (float) (at[1] / m),
                    (float) (at[2] / m)).intensity(m);
            }
            clouds.update(time, seconds, sky, settings); // before the scene syncs: the flashes' lights go through it
            bolts.update();
            // The ground's light from the sky under the clouds (the environment's), and the air's under them.
            clouds.skyBelowClouds(camera.position(0), camera.position(2), 10_000, below);
            // A uniform sky of that irradiance: the clear sky's colour where it is clear, the clouds' grey light diffused
            // through them where they cover (a captured clear sky only dimmed would light shade under clouds blue).
            environment.setColor((float) (below[0] / Math.PI), (float) (below[1] / Math.PI),
                (float) (below[2] / Math.PI));
            if (flashLights != null) {
                flashLights.update(clouds);
                nodes.update();
            }
            lightSync.update();
            gpu.graph.beginFrame();
            view.set(1, MeshletRenderer.MODE_LIT);
            gpu.graph.submit(0, 0);
        }
        gpu.device.waitIdle();
        if (!read) {
            return null;
        }
        float[] rgb = new float[3 * width * height];
        var memory = pixels.memory();
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < 3; c++) {
                rgb[3 * i + c] = Float.float16ToFloat(memory.getAtIndex(ValueLayout.JAVA_SHORT, 4L * i + c));
            }
        }
        return rgb;
    }

    /**
     * What the display shows of the last frame: sRGB-encoded values in [0, 1], RGB floats row by row (the display
     * transform's, after the white balance in {@link #gains} and {@code display.exposure}).
     */
    float[] shown() {
        float[] rgb = new float[3 * width * height];
        var memory = shown.memory();
        for (int i = 0; i < width * height; i++) {
            for (int c = 0; c < 3; c++) {
                rgb[3 * i + c] = Float.float16ToFloat(memory.getAtIndex(ValueLayout.JAVA_SHORT, 4L * i + c));
            }
        }
        return rgb;
    }

    /** Sets the white balance before the display transform to {@link #whiteBalance()} (none when it is null). */
    CloudScene balance() {
        double[] g = whiteBalance();
        for (int c = 0; c < 3; c++) {
            gains[c] = g == null || !(g[c] > 0 && g[c] < 100) ? 1 : (float) g[c];
        }
        return this;
    }

    /** The luminance of pixel (x, y) of {@code rgb} (cd/m²). */
    float luminance(float[] rgb, int x, int y) {
        int o = 3 * (y * width + x);
        return 0.2126f * rgb[o] + 0.7152f * rgb[o + 1] + 0.0722f * rgb[o + 2];
    }

    @Override
    public void close() {
        gpu.close();
    }

    /**
     * The ground: a 2 x 2 grid in x, y (scaled to 400 km by the instance) facing +z, its z the planet's surface below
     * the plane tangent at the origin, in metres (the instance leaves z unscaled): -d^2 / 2R at d metres out. The clouds
     * and effects-atmosphere's sky lie on the planet; a flat ground showed beyond the true horizon, under the decks
     * curving down to it.
     */
    private static MeshData quad() {
        int n = 64;
        double radius = 6.36e6;
        float[] positions = new float[3 * (n + 1) * (n + 1)];
        float[] normals = new float[positions.length];
        for (int j = 0; j <= n; j++) {
            for (int i = 0; i <= n; i++) {
                int o = 3 * (j * (n + 1) + i);
                double x = 2.0 * i / n - 1;
                double y = 2.0 * j / n - 1;
                double d = 200_000 * Math.hypot(x, y);
                positions[o] = (float) x;
                positions[o + 1] = (float) y;
                positions[o + 2] = (float) (-d * d / (2 * radius));
                normals[o + 2] = 1;
            }
        }
        int[] indices = new int[6 * n * n];
        int k = 0;
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int a = j * (n + 1) + i;
                int b = a + 1;
                int c = a + n + 1;
                int e = c + 1;
                indices[k++] = a;
                indices[k++] = b;
                indices[k++] = e;
                indices[k++] = a;
                indices[k++] = e;
                indices[k++] = c;
            }
        }
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), (n + 1) * (n + 1)).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(indices).buildFlat();
    }

    /** The id of {@link Capture}. */
    private static final Id<Capture> CAPTURE = Id.of("test.capture", Capture.class);

    /** A camera's white balance: the scene's colour times {@code gains}, after the capture of its physical values. */
    private record WhiteBalance(float[] gains) implements Technique {
        @Override
        public Id<WhiteBalance> id() {
            return BALANCE;
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
        public Order order() {
            return Order.at(Anchor.POST).after(CAPTURE);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            Pipeline program = g.computeProgram("shaders/test/grade.spv", "gradeMain", GradePush.BYTES);
            GradePush push = new GradePush();
            g.pass("white balance").write(color, Access.STORAGE_WRITE).record(ctx -> {
                int w = ctx.regionWidth(color);
                int h = ctx.regionHeight(color);
                ctx.dispatch(program, push.color(ctx.storage(color)).size(w | h << 16).gains(gains[0], gains[1],
                    gains[2]), (w + 7) / 8, (h + 7) / 8, 1);
            });
            g.provide(Keys.SCENE_COLOR, color);
        }
    }

    /** The id of {@link WhiteBalance}. */
    private static final Id<WhiteBalance> BALANCE = Id.of("test.whiteBalance", WhiteBalance.class);

    /** The id of {@link Last}. */
    private static final Id<Last> LAST = Id.of("test.display", Last.class);

    /** {@code inner} (the display transform) after everything else in the view: after the white balance. */
    private record Last(Technique inner) implements Technique {
        @Override
        public Id<Last> id() {
            return LAST;
        }

        @Override
        public List<Key<?>> provides() {
            return inner.provides();
        }

        @Override
        public List<Key<?>> reads() {
            return inner.reads();
        }

        @Override
        public List<Key<?>> modifies() {
            return inner.modifies();
        }

        @Override
        public Order order() {
            return Order.at(Anchor.POST).after(BALANCE);
        }

        @Override
        public void build(GraphBuilder g) {
            inner.build(g);
        }
    }

    /** Copies what the display transform made into {@code pixels}. */
    private record CaptureDisplay(GpuBuffer pixels, int width, int height) implements Technique {
        @Override
        public Order order() {
            return Order.at(Anchor.POST).after(LAST);
        }

        @Override
        public List<Key<?>> provides() {
            return List.of();
        }

        @Override
        public List<Key<?>> modifies() {
            return List.of(DISPLAY);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(DISPLAY);
            copy(g, "capture display", color, pixels, width, height);
            g.provide(DISPLAY, color);
        }
    }

    /** A pass copying {@code color} into {@code pixels} (after the frame before's copy). */
    private static void copy(GraphBuilder g, String name, Image color, GpuBuffer pixels, int width, int height) {
        g.pass(name).read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
            // The frame before copied into the same host buffer: its write first.
            com.truesim.engine.vulkan.Barriers.memory(ctx.unsafe().commands(),
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT,
                org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_TRANSFER_BIT,
                org.lwjgl.vulkan.VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT);
            try (var stack = stackPush()) {
                VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
                region.bufferRowLength(width).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .layerCount(1);
                region.imageExtent().set(width, height, 1);
                org.lwjgl.vulkan.VK10.vkCmdCopyImageToBuffer(ctx.unsafe().commands(), ctx.unsafe().image(color),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, pixels.handle(), region);
            }
        });
    }

    /** Copies the scene's colour into {@code pixels} after everything drawn over it. */
    private record Capture(GpuBuffer pixels, int width, int height) implements Technique {
        @Override
        public Id<Capture> id() {
            return CAPTURE;
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
        public Order order() {
            return Order.at(Anchor.POST);
        }

        @Override
        public void build(GraphBuilder g) {
            Image color = g.modify(Keys.SCENE_COLOR);
            copy(g, "capture", color, pixels, width, height);
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
