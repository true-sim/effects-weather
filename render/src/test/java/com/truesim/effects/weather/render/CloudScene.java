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
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Anchor;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.graph.ViewPort;
import com.truesim.effects.atmosphere.render.AerialPerspective;
import com.truesim.effects.atmosphere.render.Atmosphere;
import com.truesim.effects.atmosphere.render.AtmosphereSky;
import com.truesim.effects.atmosphere.render.SkyCapture;
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
 * A weather scene for the tests, screenshots and benchmarks: a flat ground (a large grey quad), the sun as a scene
 * node's directional light (lux at the ground) placed by {@link LightSync}, a weather of the given quality and preset,
 * its GPU side, a camera, forward shading with the light clusters, the clouds ({@link Clouds}), the clouds' shadow on
 * the ground ({@link CloudShadowMask}), and the scene's colour (RGBA16F, physical units) read back after each frame.
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
    private final double[] clear = new double[3];
    final int groundMaterial;
    private final GpuBuffer pixels;
    private double time;
    LightningLights flashLights;

    CloudScene(int width, int height, Quality quality, WeatherPreset preset, boolean shadowMask) {
        this(width, height, WeatherConfig.forQuality(quality), quality, preset, shadowMask, false);
    }

    /**
     * With {@code atmosphere}, effects-atmosphere's sky, aerial perspective and sky capture (the environment, dimmed
     * by the clouds), its exposure in its scene units (EV100: {@link #ev100}); else the analytic test sky in physical
     * units.
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
            .withBaseColor(new Color(0.25f, 0.25f, 0.25f, 1)).withRoughness(1)));
        int grey = groundMaterial;
        // The ground: the quad (z = 0, facing +z) laid flat, 400 km across.
        float s = (float) Math.sqrt(0.5);
        scene.set(0, ground, grey, 0, 0, 0, -s, 0, 0, s, 200_000, 200_000, 1);

        int usage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_STORAGE_BIT;
        GpuImage target = gpu.own(new GpuImage(gpu.device, width, height, VK_FORMAT_R16G16B16A16_SFLOAT, usage,
            VK_IMAGE_ASPECT_COLOR_BIT));
        ExternalImage output = new ExternalImage("target", width, height, Format.R16G16B16A16_SFLOAT, usage,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(target.handle(), target.view());
        pixels = gpu.own(GpuBuffer.hostVisible(gpu.device, 8L * width * height, VK_BUFFER_USAGE_TRANSFER_DST_BIT));
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
            port.add(new SkyCapture(this.atmosphere, environment, textures)).add(new AtmosphereSky(this.atmosphere, view))
                .add(new AerialPerspective(this.atmosphere, view));
        } else {
            port.add(new TestSky(clouds, view, below));
        }
        port.add(new Clouds(clouds, view)).add(bolts.view(view))
            .add(new Capture(pixels, width, height)).output(Keys.SCENE_COLOR, output);
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
            if (atmosphere != null) {
                // The captured clear sky, dimmed by the clouds as much as their diffused light is below it.
                sky.sky(clear);
                double ratio = (below[0] + below[1] + below[2]) / Math.max(clear[0] + clear[1] + clear[2], 1e-30);
                environment.intensity((float) Math.clamp(ratio, 0, 4));
            } else {
                environment.setColor((float) (below[0] / Math.PI), (float) (below[1] / Math.PI),
                    (float) (below[2] / Math.PI));
            }
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

    /** The luminance of pixel (x, y) of {@code rgb} (cd/m²). */
    float luminance(float[] rgb, int x, int y) {
        int o = 3 * (y * width + x);
        return 0.2126f * rgb[o] + 0.7152f * rgb[o + 1] + 0.0722f * rgb[o + 2];
    }

    @Override
    public void close() {
        gpu.close();
    }

    /** A 2 x 2 quad in z = 0 facing +z. */
    private static MeshData quad() {
        float[] positions = {-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0};
        float[] normals = {0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0, 1};
        return new MeshBuilder(VertexFormat.of(VertexAttribute.NORMAL), 4).positions(positions)
            .attribute(VertexAttribute.NORMAL, normals).submesh(new int[] {0, 1, 2, 0, 2, 3}).buildFlat();
    }

    /** Copies the scene's colour into {@code pixels} after everything drawn over it. */
    private record Capture(GpuBuffer pixels, int width, int height) implements Technique {
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
            g.pass("capture").read(color, Access.TRANSFER_SRC).sideEffects().record(ctx -> {
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
            g.provide(Keys.SCENE_COLOR, color);
        }
    }
}
