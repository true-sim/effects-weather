package com.truesim.effects.weather.snow;

import com.truesim.common.math.color.Color;
import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.EnvironmentMaps;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
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
 * Pictures of simple snow (TRUESIM_SCREENSHOTS=true; {@code -Pscreenshots=<directory>}, else build/screenshots): bumpy
 * ground with a carport (a roof on posts), a shed with a pitched roof, crates and a wall, under a pale winter sky, at
 * coverage 0, 0.3 and 0.9. The ground under the carport stays bare; the roofs' slopes keep snow below slopeEnd, the
 * walls none.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class SnowScreenshots {

    private static final int WIDTH = 960;
    private static final int HEIGHT = 540;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;

    @Test
    void snowAtThreeCoveragesWithARoof() throws IOException {
        Path out = Path.of(System.getProperty("truesim.screenshots", "build/screenshots"));
        Files.createDirectories(out);
        try (TestGpu gpu = TestGpu.open()) {
            TestRenderer rig = gpu.closing(TestRenderer.of(gpu.device(), WIDTH, HEIGHT,
                com.truesim.engine.render.GeometryPool.Capacity.of(1 << 17, 4096, 8), 1024,
                com.truesim.engine.render.ViewOptions.of(4096, 1 << 18).withOcclusion(true)));
            Textures textures = gpu.closing(new Textures(gpu.device()));
            Materials materials = gpu.closing(new Materials(gpu.device(), 16));
            rig.scene().setMaterials(materials);
            int box = rig.add(TestMeshes.box());
            int terrain = rig.add(TestMeshes.terrain(64));
            int earth = materials.add(matte(0.20f, 0.17f, 0.12f));
            int roof = materials.add(matte(0.45f, 0.12f, 0.08f));
            int wood = materials.add(matte(0.35f, 0.24f, 0.14f));
            int stone = materials.add(matte(0.42f, 0.42f, 0.44f));
            int[] id = {0};
            Placer place = (material, x, y, z, angle, hx, hy, hz) -> rig.scene().set(id[0]++, box, material, x, y, z,
                0, 0, (float) Math.sin(angle / 2), (float) Math.cos(angle / 2), (float) hx, (float) hy, (float) hz);
            rig.scene().set(id[0]++, terrain, earth, 0, -0.3, -10, 0, 0, 0, 1, 1, 1, 1);
            // A carport: a flat roof on four posts over the ground in front, a little to the left
            for (int i = 0; i < 4; i++) {
                place.at(wood, -4 + (i % 2) * 5, 1.2, -8 + (i / 2) * 4, 0, 0.12, 1.5, 0.12);
            }
            place.at(roof, -1.5, 2.75, -6, 0, 3, 0.08, 2.4);
            // A shed with a pitched roof (35 degrees: snow; its walls none), on the right
            place.at(stone, 5, 1.2, -12, 0, 2.5, 1.2, 2);
            place.at(roof, 5 - 1.25, 3.05, -12, Math.toRadians(35), 1.6, 0.08, 2.2);
            place.at(roof, 5 + 1.25, 3.05, -12, Math.toRadians(-35), 1.6, 0.08, 2.2);
            // Crates and a wall
            place.at(wood, 1.5, 0.4, -3.5, 0.3, 0.45, 0.45, 0.45);
            place.at(wood, 2.4, 0.35, -2.8, -0.2, 0.35, 0.35, 0.35);
            place.at(stone, -6, 0.8, -14, 0, 4, 0.8, 0.25);

            int sky = textures.add(TextureFormat.RGBA16F, 64, 32, Textures.GENERATE, sky(64, 32));
            Environment environment = Environment.panorama("winter sky", sky, 64);
            EnvironmentMaps.Quality quality = new EnvironmentMaps.Quality(64, 32, 4, 16, 64);
            Camera camera = new Camera().position(-1, 4.2, 4).set(0.15, -0.45, -1, 0, 1, 0, Math.toRadians(60), WIDTH,
                HEIGHT, 0.05);
            SceneView main = rig.toTarget(rig.main(camera));
            SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
            SimpleSnow snow = main.port().own(new SimpleSnow(geometry, main));
            main.port().add(rig.forward(main)).add(new EnvironmentLighting(environment, main, quality))
                .add(new SkyBackground(environment, main, quality)).add(snow);
            for (float coverage : new float[] {0, 0.3f, 0.9f}) {
                ShadingHookValues.of(gpu.device()).set(SimpleSnow.HOOK, SnowParameters.DEFAULTS
                    .withCoverage(coverage), true);
                for (int i = 0; i < 4; i++) {
                    snow.update(0);
                    rig.frames(1);
                }
                write(rig.target().shot(), out.resolve(String.format(Locale.ROOT, "snow-coverage-%.1f.png",
                    coverage)));
            }
            rig.scene().setMaterials(null);
        }
    }

    @FunctionalInterface
    private interface Placer {
        void at(int material, double x, double y, double z, double angleZ, double hx, double hy, double hz);
    }

    private static Material matte(float r, float g, float b) {
        return new Material(MR.withBaseColor(new Color(r, g, b, 1)).withRoughness(0.9f).withMetallic(0));
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

    /** The linear frame as sRGB, a PNG. */
    private static void write(BufferedImage linear, Path file) throws IOException {
        BufferedImage image = new BufferedImage(linear.getWidth(), linear.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < linear.getHeight(); y++) {
            for (int x = 0; x < linear.getWidth(); x++) {
                int argb = linear.getRGB(x, y);
                image.setRGB(x, y, srgb((argb >> 16) & 255) << 16 | srgb((argb >> 8) & 255) << 8 | srgb(argb & 255));
            }
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static int srgb(int linear) {
        double v = linear / 255.0;
        double s = v <= 0.0031308 ? 12.92 * v : 1.055 * Math.pow(v, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(s, 0, 1) * 255);
    }
}
