package com.truesim.effects.weather.snow;

import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.EnvironmentMaps;
import com.truesim.engine.render.GeometryPool;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.vulkan.testing.TestGpu;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Films of simple snow as frame sequences, 30 frames a second, 1280 x 720 (TRUESIM_SCREENSHOTS=true;
 * {@code -Pscreenshots=<directory>}, else build/screenshots; a directory of PNGs per film, f0000.png on, for ffmpeg):
 * <ul>
 *   <li>{@code snow-coverage}: the coverage rising from 0 to 1 over bumpy ground with a carport, a shed with a pitched
 *   roof, ramps of 20, 40 and 60 degrees, a wall and a patio whose material refuses the hook: the patches grow, the
 *   ground under the carport, the walls, the steep ramp and the patio stay bare;</li>
 *   <li>{@code snow-accumulation}: snow falling slanted by the wind fills in from bare, the lee of a wall staying
 *   sheltered, then it stops and the snow melts; {@code snow-accumulation-straight} the same falling straight down;</li>
 *   <li>{@code snow-moving-camera}: the camera moving down a lane of carports and walls, the shelter map following
 *   it; {@code snow-moving-camera-slanted} the same in slanted snow.</li>
 * </ul>
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class SnowVideos {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int FPS = 30;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;

    @Test
    void coverageRisingFromNoneToFull() throws IOException {
        Path out = directory("snow-coverage");
        try (TestGpu gpu = TestGpu.open()) {
            Stage stage = new Stage(gpu);
            stage.yard();
            Camera camera = new Camera().position(-2, 5, 6).set(0.25, -0.42, -1, 0, 1, 0, Math.toRadians(60), WIDTH,
                HEIGHT, 0.05);
            SimpleSnow snow = stage.view(camera);
            int frames = 13 * FPS;
            for (int f = 0; f < frames; f++) {
                double t = f / (double) FPS;
                // Bare for a second, rising over 10 s, full for the last two
                float coverage = (float) Math.clamp((t - 1) / 10, 0, 1);
                stage.values(SnowParameters.DEFAULTS.withCoverage(coverage));
                snow.update(1.0 / FPS);
                stage.rig.frames(1);
                write(stage.rig.target().shot(), out.resolve(frame(f)));
            }
            stage.close();
        }
    }

    @Test
    void slantedSnowFillsInThenMelts() throws IOException {
        accumulation("snow-accumulation", new Vec3(0.9, -1, 0));
    }

    @Test
    void snowFallingStraightDownFillsInThenMelts() throws IOException {
        accumulation("snow-accumulation-straight", new Vec3(0, -1, 0));
    }

    private static void accumulation(String name, Vec3 wind) throws IOException {
        Path out = directory(name);
        try (TestGpu gpu = TestGpu.open()) {
            Stage stage = new Stage(gpu);
            stage.yard();
            stage.rig.graph().settings().set(SnowSettings.INITIAL, 0f);
            stage.rig.graph().settings().set(SnowSettings.FILL_SECONDS, 5f);
            stage.rig.graph().settings().set(SnowSettings.MELT_SECONDS, 4f);
            // Looking along the wall from its windward end, the wind from the left (towards +x)
            Camera camera = new Camera().position(-3, 6, 8).set(0.05, -0.5, -1, 0, 1, 0, Math.toRadians(60), WIDTH,
                HEIGHT, 0.05);
            SimpleSnow snow = stage.view(camera);
            int frames = 14 * FPS;
            for (int f = 0; f < frames; f++) {
                double t = f / (double) FPS;
                // Snowing from 1 s to 7.5 s, melting from 8.5 s
                SnowParameters values = SnowParameters.DEFAULTS.withCoverage(1).withFallDirection(wind);
                if (t >= 1 && t < 7.5) {
                    values = values.withSnowfall(1);
                } else if (t >= 8.5) {
                    values = values.withMelt(1);
                }
                stage.values(values);
                snow.update(1.0 / FPS);
                stage.rig.frames(1);
                write(stage.rig.target().shot(), out.resolve(frame(f)));
            }
            stage.close();
        }
    }

    @Test
    void theShelterMapFollowsAMovingCamera() throws IOException {
        movingCamera("snow-moving-camera", new Vec3(0, -1, 0));
    }

    @Test
    void theShelterMapFollowsAMovingCameraInSlantedSnow() throws IOException {
        movingCamera("snow-moving-camera-slanted", new Vec3(0.5, -1, 0));
    }

    private static void movingCamera(String name, Vec3 fall) throws IOException {
        Path out = directory(name);
        try (TestGpu gpu = TestGpu.open()) {
            Stage stage = new Stage(gpu);
            stage.lane();
            Camera camera = new Camera();
            camera.position(0, 3, 30).set(0, -0.3, -1, 0, 1, 0, Math.toRadians(60), WIDTH, HEIGHT, 0.05);
            SimpleSnow snow = stage.view(camera);
            stage.values(SnowParameters.DEFAULTS.withCoverage(0.85f).withFallDirection(fall));
            int frames = 14 * FPS;
            for (int f = 0; f < frames; f++) {
                double t = f / (double) FPS;
                // Down the lane at about 4 m/s, weaving, rising and falling, looking round
                double z = 30 - 56 * smooth(t / 14);
                double x = 1.8 * Math.sin(2 * Math.PI * t / 7);
                double y = 3 + 1.2 * Math.sin(2 * Math.PI * t / 9);
                double yaw = 0.45 * Math.sin(2 * Math.PI * t / 6);
                camera.position(x, y, z).set(-Math.sin(yaw), -0.28, -Math.cos(yaw), 0, 1, 0, Math.toRadians(60),
                    WIDTH, HEIGHT, 0.05);
                snow.update(1.0 / FPS);
                stage.rig.frames(1);
                write(stage.rig.target().shot(), out.resolve(frame(f)));
            }
            stage.close();
        }
    }

    private static double smooth(double s) {
        return s * s * (3 - 2 * s);
    }

    /** The renderer, the materials and the sky; the scenes. */
    private static final class Stage {

        final TestGpu gpu;
        final TestRenderer rig;
        final Materials materials;
        final Environment environment;
        final EnvironmentMaps.Quality quality;
        final int box;
        final int terrain;
        final int earth;
        final int roof;
        final int wood;
        final int stone;
        final int patio;
        private int id;

        Stage(TestGpu gpu) {
            this.gpu = gpu;
            rig = gpu.closing(TestRenderer.of(gpu.device(), WIDTH, HEIGHT, GeometryPool.Capacity.of(1 << 17, 4096, 8),
                1024, ViewOptions.of(4096, 1 << 18).withOcclusion(true)));
            Textures textures = gpu.closing(new Textures(gpu.device()));
            materials = gpu.closing(new Materials(gpu.device(), 16));
            rig.scene().setMaterials(materials);
            box = rig.add(TestMeshes.box());
            terrain = rig.add(TestMeshes.terrain(64));
            earth = materials.add(matte(0.20f, 0.17f, 0.12f));
            roof = materials.add(matte(0.45f, 0.12f, 0.08f));
            wood = materials.add(matte(0.35f, 0.24f, 0.14f));
            stone = materials.add(matte(0.42f, 0.42f, 0.44f));
            // Opted out: a patio of blue-grey tiles that refuses the snow hook (heated, say)
            patio = materials.add(matte(0.22f, 0.30f, 0.40f).refuseHooks(SimpleSnow.HOOK));
            int sky = textures.add(TextureFormat.RGBA16F, 64, 32, Textures.GENERATE, sky(64, 32));
            environment = Environment.panorama("winter sky", sky, 64);
            quality = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
        }

        void place(int material, double x, double y, double z, double angleZ, double hx, double hy, double hz) {
            rig.scene().set(id++, box, material, x, y, z, 0, 0, (float) Math.sin(angleZ / 2),
                (float) Math.cos(angleZ / 2), (float) hx, (float) hy, (float) hz);
        }

        /**
         * SnowScreenshots' yard with ramps, a long wall across the wind and a patio: bumpy ground, a carport (a flat
         * roof on posts), a shed with a pitched roof, crates.
         */
        void yard() {
            rig.scene().set(id++, terrain, earth, 0, -0.3, -10, 0, 0, 0, 1, 1, 1, 1);
            for (int i = 0; i < 4; i++) {
                place(wood, -4 + (i % 2) * 5, 1.2, -8 + (i / 2) * 4, 0, 0.12, 1.5, 0.12);
            }
            place(roof, -1.5, 2.75, -6, 0, 3, 0.08, 2.4);
            place(stone, 5, 1.2, -12, 0, 2.5, 1.2, 2);
            place(roof, 5 - 1.25, 3.05, -12, Math.toRadians(35), 1.6, 0.08, 2.2);
            place(roof, 5 + 1.25, 3.05, -12, Math.toRadians(-35), 1.6, 0.08, 2.2);
            place(wood, 1.5, 0.4, -3.5, 0.3, 0.45, 0.45, 0.45);
            place(wood, 2.4, 0.35, -2.8, -0.2, 0.35, 0.35, 0.35);
            // A long wall along z, about 2.5 m high: with the wind towards +x its lee is the ground at x above it
            place(stone, -8, ground(-8, -9) + 1.0, -9, 0, 0.25, 2.2, 6);
            // Ramps tilting about z: 20, 40 and 60 degrees, standing on the ground
            for (int i = 0; i < 3; i++) {
                double angle = Math.toRadians(20 + 20 * i);
                double x = 4.5 + 2.6 * i;
                place(wood, x, ground(x, -6) + 0.3 + Math.sin(angle), -6, angle, 1.0, 0.06, 1.0);
            }
            // The patio, a slab just above the ground in front
            place(patio, -3, ground(-3, -1) + 0.25, -1, 0, 1.6, 0.3, 1.2);
        }

        /** The yard's terrain height at x, z: TestMeshes.terrain(64) placed at (0, -0.3, -10). */
        static double ground(double x, double z) {
            double gx = x + 32;
            double gz = z + 10 + 32;
            return -0.3 + 3 * Math.sin(gx / 9.0) * Math.cos(gz / 13.0) + Math.sin(gx * gz / 4000.0);
        }

        /** A lane along z from 30 to -40: carports and walls either side, on flat ground. */
        void lane() {
            place(earth, 0, -0.1, -5, 0, 60, 0.1, 80);
            for (int i = 0; i < 8; i++) {
                double z = 24 - 9 * i;
                double side = i % 2 == 0 ? -1 : 1;
                // A carport on four posts, its roof 2.6 m up
                for (int p = 0; p < 4; p++) {
                    place(wood, side * (5 + (p % 2) * 4), 1.3, z - 2 + (p / 2) * 4, 0, 0.12, 1.3, 0.12);
                }
                place(roof, side * 7, 2.65, z, 0, 2.6, 0.06, 2.6);
                // A wall on the other side, a patio in front of it
                place(stone, -side * 7, 1, z + 2, 0, 0.25, 1, 3);
                place(patio, -side * 4.5, 0.03, z - 3, 0, 1.2, 0.03, 1.2);
            }
        }

        void values(SnowParameters parameters) {
            ShadingHookValues.of(gpu.device()).set(SimpleSnow.HOOK, parameters, true);
        }

        SimpleSnow view(Camera camera) {
            SceneView main = rig.toTarget(rig.main(camera));
            SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
            SimpleSnow snow = main.port().own(new SimpleSnow(geometry, main));
            main.port().add(rig.forward(main)).add(new EnvironmentLighting(environment, main, quality))
                .add(new SkyBackground(environment, main, quality)).add(snow);
            // Built on the first frame: the map draws from the next
            values(SnowParameters.DEFAULTS);
            rig.frames(1);
            return snow;
        }

        void close() {
            rig.scene().setMaterials(null);
        }
    }

    // ---------------------------------------------------------------- helpers

    private static Material matte(float r, float g, float b) {
        return new Material(MR.withBaseColor(new Color(r, g, b, 1)).withRoughness(0.9f).withMetallic(0));
    }

    private static Path directory(String name) throws IOException {
        Path out = Path.of(System.getProperty("truesim.screenshots", "build/screenshots")).resolve(name);
        Files.createDirectories(out);
        return out;
    }

    private static String frame(int f) {
        return String.format(Locale.ROOT, "f%04d.png", f);
    }

    /** A pale winter sky: white-grey at the horizon, a cold blue overhead, the ground below dim. */
    private static MemorySegment sky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int y = 0; y < height; y++) {
            double up = Math.cos(Math.PI * (y + 0.5) / height);
            double[] c = up >= 0
                ? new double[] {0.75 - 0.35 * up, 0.8 - 0.3 * up, 0.88 - 0.12 * up}
                : new double[] {0.25, 0.25, 0.27};
            for (int x = 0; x < width; x++) {
                long at = 8L * (y * width + x);
                for (int k = 0; k < 3; k++) {
                    texels.set(half, at + 2L * k, Float.floatToFloat16((float) c[k]));
                }
                texels.set(half, at + 6, Float.floatToFloat16(1));
            }
        }
        return texels;
    }

    private static final int[] SRGB = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            double v = i / 255.0;
            double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
            SRGB[i] = (int) Math.round(Math.clamp(s, 0, 1) * 255);
        }
    }

    /** The linear frame as sRGB, a PNG. */
    private static void write(BufferedImage linear, Path file) throws IOException {
        int w = linear.getWidth();
        int h = linear.getHeight();
        int[] row = linear.getRGB(0, 0, w, h, null, 0, w);
        for (int i = 0; i < row.length; i++) {
            int argb = row[i];
            row[i] = SRGB[(argb >> 16) & 255] << 16 | SRGB[(argb >> 8) & 255] << 8 | SRGB[argb & 255];
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, w, h, row, 0, w);
        ImageIO.write(image, "png", file.toFile());
    }
}
