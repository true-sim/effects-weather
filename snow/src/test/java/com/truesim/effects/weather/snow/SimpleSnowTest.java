package com.truesim.effects.weather.snow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.truesim.common.math.Vec3;
import com.truesim.common.math.color.Color;
import com.truesim.common.state.ChangeClock;
import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Environment;
import com.truesim.engine.render.EnvironmentLighting;
import com.truesim.engine.render.EnvironmentMaps;
import com.truesim.engine.render.Material;
import com.truesim.engine.render.Materials;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookOverrides;
import com.truesim.engine.render.ShadingHookValues;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.render.materials.MetallicRoughnessParameters;
import com.truesim.engine.render.testing.TestMeshes;
import com.truesim.engine.render.testing.TestRenderer;
import com.truesim.engine.scene.ShadingHookSync;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.testing.TestGpu;
import com.truesim.scene.core.SceneGraph;
import java.awt.image.BufferedImage;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Simple snow on the GPU (effects-weather#4, part 1): dark ground under a uniform grey sky, so snow (albedo 0.9) shows
 * bright and bare ground dark. Looking straight down from 2 m, a roof 3 m up covers the left half of the view: at full
 * coverage the open right half is white and the ground under the roof bare, in forward shading and the visibility
 * buffer alike; at coverage 0 the image is the same, pixel for pixel, as without the hook. Vertical faces take none;
 * coverage between thresholds the noise into patches; a material refusing the hook and an instance whose override entry
 * switches it off stay bare; snow accumulates over time while it snows. Validated.
 */
class SimpleSnowTest {

    private static final int SIZE = 64;
    private static final MetallicRoughnessParameters MR = MetallicRoughnessParameters.DEFAULTS;
    private static final int SNOW = 80;
    private static final int BARE = 40;

    /** Fall directions: straight down, and slanted 45 degrees by the wind. */
    enum Fall {
        STRAIGHT(new Vec3(0, -1, 0)),
        SLANTED(new Vec3(1, -1, 0));

        final Vec3 direction;

        Fall(Vec3 direction) {
            this.direction = direction;
        }
    }

    /** The ways of shading a view. */
    enum Shading { FORWARD, VISIBILITY_BUFFER }

    private TestGpu gpu;
    private VulkanDevice device;
    private TestRenderer rig;
    private Materials materials;
    private Environment environment;
    private int box;
    private int ground;
    private SimpleSnow snow;
    private Camera camera;

    @BeforeEach
    void start() {
        gpu = TestGpu.open();
        device = gpu.device();
        rig = gpu.closing(TestRenderer.of(device, SIZE, SIZE));
        box = rig.add(TestMeshes.box());
        Textures textures = gpu.closing(new Textures(device));
        materials = gpu.closing(new Materials(device, 8));
        ground = materials.add(new Material(MR.withBaseColor(new Color(0.05f, 0.05f, 0.05f, 1)).withRoughness(1)
            .withMetallic(0)));
        rig.scene().setMaterials(materials);
        int sky = textures.add(TextureFormat.RGBA16F, 16, 8, Textures.GENERATE, uniformSky(16, 8));
        environment = Environment.panorama("grey sky", sky, 16);
        rig.graph().settings().set(SnowSettings.AREA, 16f);
        rig.graph().settings().set(SnowSettings.MAP_RESOLUTION, 256);
        rig.graph().settings().set(SnowSettings.ACCUMULATION_RESOLUTION, 64);
    }

    @AfterEach
    void stop() {
        if (gpu != null) {
            if (snow != null) {
                device.waitIdle();
                snow.close();
            }
            rig.scene().setMaterials(null);
            gpu.close(); // fails on validation errors
        }
    }

    @Test
    void theListingRebuildsTheEnginesShadingWithTheSnow() {
        ShadingHooks hooks = ShadingHooks.of(device);
        assertThat(hooks.hooks()).contains(SimpleSnow.HOOK);
        assertThat(hooks.parameters(hooks.index(SimpleSnow.HOOK))).isEqualTo(SnowParameters.class);
    }

    @ParameterizedTest
    @EnumSource(Shading.class)
    void snowLiesOnOpenLevelGroundAndNotUnderARoof(Shading shading) {
        assumeShading(shading);
        groundAndRoof();
        values(SnowParameters.DEFAULTS.withCoverage(1));
        look(shading, 0, 2, 0, 0, -1, 0);
        frames(3);
        for (int y = 8; y < SIZE - 8; y += 8) {
            assertThat(red(SIZE / 2 + 16, y)).as("open ground, row %d", y).isGreaterThan(SNOW);
            assertThat(red(SIZE / 2 - 16, y)).as("under the roof, row %d", y).isLessThan(BARE);
        }
        // The map is what shelters it: with a bias reaching past the roof, the ground under it is snowed on too
        rig.graph().settings().set(SnowSettings.BIAS, 5f);
        frames(2);
        assertThat(red(SIZE / 2 - 16, SIZE / 2)).as("under the roof, the map biased past it").isGreaterThan(SNOW);
    }

    @Test
    void slantedSnowIsShelteredDownwindOfTheRoof() {
        groundAndRoof();
        // Falling 45 degrees towards +x: the roof 3 m up shelters the ground 3 m further on, to x 3
        values(SnowParameters.DEFAULTS.withCoverage(1).withFallDirection(new Vec3(1, -1, 0)));
        look(Shading.FORWARD, 0, 2, 0, 0, -1, 0);
        frames(4);
        assertThat(red(pixel(1), SIZE / 2)).as("open to the sky straight up, but downwind of the roof").isLessThan(BARE);
        assertThat(red(pixel(-1), SIZE / 2)).as("under the roof").isLessThan(BARE);
        // Driven nearly along -z: the roof, open towards z, shelters the ground under it as straight down; level ground
        // keeps its snow (slopes are against gravity, whatever the wind)
        values(SnowParameters.DEFAULTS.withCoverage(1).withFallDirection(new Vec3(0, -0.3, -1)));
        frames(4);
        assertThat(red(pixel(1), SIZE / 2)).as("open, falling along -z").isGreaterThan(SNOW);
        assertThat(red(pixel(-1), SIZE / 2)).as("under the roof, falling along -z").isLessThan(BARE);
    }

    @Test
    void theMapAndTheAccumulationFollowTheCameraAndTheSnowTurnsOff() {
        groundAndRoof();
        values(SnowParameters.DEFAULTS.withCoverage(1).withFallDirection(new Vec3(0, 0, 0)));
        look(Shading.FORWARD, 0, 2, 0, 0, -1, 0);
        frames(3);
        assertThat(red(SIZE / 2 + 16, SIZE / 2)).as("open ground").isGreaterThan(SNOW);
        // The camera moves 1.5 m along x and 60 m up and back: the map and the accumulation's window follow
        camera.position(1.5, 62, 0);
        frames(3);
        camera.position(1.5, 2, 0);
        frames(3);
        assertThat(red(40, SIZE / 2)).as("open ground at x 2").isGreaterThan(SNOW);
        assertThat(red(3, SIZE / 2)).as("under the roof at x -0.3").isLessThan(BARE);
        snow.active(false);
        frames(1);
        assertThat(red(40, SIZE / 2)).as("the snow off").isLessThan(BARE);
        // Another area rebuilds the grid: the snow comes back on a new map
        snow.active(true);
        rig.graph().settings().set(SnowSettings.AREA, 24f);
        frames(3);
        assertThat(red(40, SIZE / 2)).as("on the new grid").isGreaterThan(SNOW);
    }

    @Test
    void coverageZeroIsExactlyNoSnow() {
        groundAndRoof();
        values(SnowParameters.DEFAULTS.withCoverage(0).withSnowfall(1));
        look(Shading.FORWARD, 0, 2, 0, 0, -1, 0);
        snow.active(false);
        frames(3);
        BufferedImage without = rig.target().shot();
        snow.active(true);
        frames(3);
        BufferedImage with = rig.target().shot();
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertThat(with.getRGB(x, y)).as("pixel %d, %d", x, y).isEqualTo(without.getRGB(x, y));
            }
        }
        assertThat(red(SIZE / 2 + 16, SIZE / 2)).as("bare open ground").isLessThan(BARE);
    }

    @Test
    void verticalFacesTakeNoSnowWhileTheTopOfTheWallAndTheGroundDo() {
        rig.scene().set(0, box, ground, 0, -0.1, 0, 0, 0, 0, 1, 40, 0.1f, 40);
        // A wall 3 m high, its face 3.5 m ahead towards the camera, its top 3 m deep; the camera 4 m up, looking down
        // ahead: the top of the wall about rows 26 to 30, its face rows 30 to 50, the ground in front of it below
        rig.scene().set(1, box, ground, 0, 1.5, -5, 0, 0, 0, 1, 4, 1.5f, 1.5f);
        values(SnowParameters.DEFAULTS.withCoverage(1));
        look(Shading.FORWARD, 0, 4, 0, 0, -0.35, -1);
        frames(3);
        assertThat(red(SIZE / 2, 40)).as("the wall's vertical face").isLessThan(BARE);
        assertThat(red(SIZE / 2, 28)).as("the top of the wall, level and open").isGreaterThan(SNOW);
        assertThat(red(SIZE / 2, 60)).as("the ground in front of it").isGreaterThan(SNOW);
    }

    @Test
    void coverageThresholdsTheNoiseIntoPatches() {
        rig.scene().set(0, box, ground, 0, -0.1, 0, 0, 0, 0, 1, 60, 0.1f, 60);
        look(Shading.FORWARD, 0, 20, 0, 0, -1, 0);
        double[] covered = new double[3];
        float[] coverages = {0.3f, 0.9f, 1};
        for (int i = 0; i < coverages.length; i++) {
            values(SnowParameters.DEFAULTS.withCoverage(coverages[i]));
            frames(3);
            covered[i] = share();
        }
        assertThat(covered[0]).as("coverage 0.3").isBetween(0.05, 0.6);
        assertThat(covered[1]).as("coverage 0.9").isBetween(0.7, 1.0).isGreaterThan(covered[0]);
        assertThat(covered[2]).as("coverage 1").isEqualTo(1.0);
    }

    @Test
    void aMaterialRefusingTheHookAndAnInstanceSwitchedOffStayBare() {
        int refusing = materials.add(materials.material(ground).refuseHooks(SimpleSnow.HOOK));
        rig.scene().set(0, box, ground, 0, -0.1, 0, 0, 0, 0, 1, 40, 0.1f, 40);
        // Two slabs on the ground: on the left the refusing material, on the right switched off by an entry
        rig.scene().set(1, box, refusing, -1, 0.05, 0, 0, 0, 0, 1, 0.8f, 0.05f, 2);
        rig.scene().set(2, box, ground, 1, 0.05, 0, 0, 0, 0, 1, 0.8f, 0.05f, 2);
        ShadingHookOverrides overrides = rig.scene().hookOverrides();
        int entry = overrides.add();
        overrides.turn(entry, SimpleSnow.HOOK, false);
        overrides.assign(2, entry);
        values(SnowParameters.DEFAULTS.withCoverage(1));
        look(Shading.FORWARD, 0, 2, 0, 0, -1, 0);
        frames(3);
        assertThat(red(pixel(-1), SIZE / 2)).as("the refusing material").isLessThan(BARE);
        assertThat(red(pixel(1), SIZE / 2)).as("the instance switched off").isLessThan(BARE);
        assertThat(red(SIZE / 2, 4)).as("the open ground beyond them").isGreaterThan(SNOW);
    }

    @Test
    void snowAccumulatesWhileItSnowsAndMelts() {
        rig.scene().set(0, box, ground, 0, -0.1, 0, 0, 0, 0, 1, 40, 0.1f, 40);
        rig.graph().settings().set(SnowSettings.INITIAL, 0f);
        rig.graph().settings().set(SnowSettings.FILL_SECONDS, 10f);
        rig.graph().settings().set(SnowSettings.MELT_SECONDS, 10f);
        values(SnowParameters.DEFAULTS.withCoverage(1).withSnowfall(1));
        look(Shading.FORWARD, 0, 2, 0, 0, -1, 0);
        frames(3);
        assertThat(red(SIZE / 2, SIZE / 2)).as("nothing has fallen yet").isLessThan(BARE);
        for (int i = 0; i < 3; i++) {
            snow.update(5);
            frames(1);
        }
        assertThat(red(SIZE / 2, SIZE / 2)).as("after 15 s of a 10 s fill").isGreaterThan(SNOW);
        // It stops snowing and melts
        values(SnowParameters.DEFAULTS.withCoverage(1).withMelt(1));
        frames(1);
        for (int i = 0; i < 3; i++) {
            snow.update(5);
            frames(1);
        }
        assertThat(red(SIZE / 2, SIZE / 2)).as("after 15 s of a 10 s melt").isLessThan(BARE);
    }

    @Test
    void slantedSnowFillsInOpenSlopingGroundAsStraightSnowDoesWhenTheWindTurns() {
        // Ground rising 12 degrees towards +x, open to the sky; looking straight down at it from 4 m
        double tilt = Math.toRadians(12);
        rig.scene().set(0, box, ground, 0, -0.1, 0, 0, 0, (float) Math.sin(tilt / 2), (float) Math.cos(tilt / 2), 40,
            0.1f, 40);
        rig.graph().settings().set(SnowSettings.INITIAL, 0f);
        rig.graph().settings().set(SnowSettings.FILL_SECONDS, 10f);
        // It starts snowing straight down, then the wind turns the fall 45 degrees towards +x (up the slope)
        values(SnowParameters.DEFAULTS.withCoverage(1).withSnowfall(1));
        look(Shading.FORWARD, 0, 4, 0, 0, -1, 0);
        frames(3);
        assertThat(share()).as("nothing has fallen yet").isLessThan(0.05);
        values(SnowParameters.DEFAULTS.withCoverage(1).withSnowfall(1).withFallDirection(new Vec3(1, -1, 0)));
        for (int i = 0; i < 4; i++) {
            snow.update(5);
            rig.frames(1);
        }
        assertThat(share()).as("open ground after 20 s of a 10 s fill, slanted").isGreaterThan(0.95);
    }

    @ParameterizedTest
    @EnumSource(Fall.class)
    void aSettledSnowCoverStaysAsTheCameraMovesAndRisesAndFalls(Fall fall) {
        groundAndRoof();
        values(SnowParameters.DEFAULTS.withCoverage(1).withFallDirection(fall.direction));
        // Over the roof's edge from 6.5 m: the roof's top on the left, the open ground on the right
        look(Shading.FORWARD, -1, 6.5, 0, 0, -1, 0);
        frames(3);
        BufferedImage settled = rig.target().shot();
        // The camera wanders across cells and up and down across whole metres, a frame at each place, and comes back
        for (int i = 1; i <= 24; i++) {
            double a = 2 * Math.PI * i / 24;
            camera.position(-1 + 1.3 * Math.sin(a), 6.5 + 2.2 * Math.sin(3 * a), 0.9 * Math.sin(2 * a));
            snow.update(0.1);
            rig.frames(1);
        }
        camera.position(-1, 6.5, 0);
        frames(2);
        BufferedImage after = rig.target().shot();
        int changed = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                int was = (settled.getRGB(x, y) >> 16) & 255;
                int now = (after.getRGB(x, y) >> 16) & 255;
                changed += Math.abs(was - now) > 20 ? 1 : 0;
            }
        }
        assertThat(changed).as("pixels changed by moving the camera, falling %s", fall).isLessThanOrEqualTo(SIZE);
        assertThat(share()).as("snow on the roof and the open ground").isGreaterThan(0.4);
    }

    @Test
    void theScenesComponentRunsTheSnowAndTimeRunsForwards() {
        groundAndRoof();
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long weather = scene.create();
        scene.components().set(weather, com.truesim.engine.scene.ShadingHookComponent.TYPE,
            SnowContent.component(SnowParameters.DEFAULTS.withCoverage(1).withFallDirection(new Vec3(0, -2, 0)), true));
        scene.update();
        new ShadingHookSync(scene, ShadingHookValues.of(device)).update();
        look(Shading.FORWARD, 0, 2, 0, 0, -1, 0);
        frames(3);
        assertThat(red(SIZE / 2 + 16, SIZE / 2)).as("open ground").isGreaterThan(SNOW);
        assertThat(red(SIZE / 2 - 16, SIZE / 2)).as("under the roof").isLessThan(BARE);
        assertThatThrownBy(() -> snow.update(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- the scene

    /** Dark ground; a roof 3 m up over x below 0. */
    private void groundAndRoof() {
        rig.scene().set(0, box, ground, 0, -0.1, 0, 0, 0, 0, 1, 40, 0.1f, 40);
        rig.scene().set(1, box, ground, -10, 3, 0, 0, 0, 0, 1, 10, 0.1f, 20);
    }

    private void values(SnowParameters parameters) {
        ShadingHookValues.of(device).set(SimpleSnow.HOOK, parameters, true);
    }

    /** The main view from ({@code x}, {@code y}, {@code z}) along ({@code fx}, {@code fy}, {@code fz}), snowed on. */
    private void look(Shading shading, double x, double y, double z, double fx, double fy, double fz) {
        boolean down = Math.abs(fy) > 0.99;
        camera = new Camera().position(x, y, z).set(fx, fy, fz, 0, down ? 0 : 1, down ? -1 : 0, Math.PI / 2,
            SIZE, SIZE, 0.05);
        SceneView main = rig.toTarget(rig.main(camera));
        SceneGeometry geometry = SceneGeometry.meshlets(rig.renderer());
        Technique opaque = shading == Shading.VISIBILITY_BUFFER
            ? new VisibilityBufferShading(geometry, main, TestRenderer.COLOR_FORMAT, TestRenderer.DEPTH_FORMAT)
                .clearColor(0, 0, 0)
            : rig.forward(main).clearColor(0, 0, 0);
        snow = new SimpleSnow(geometry, main);
        main.port().add(opaque).add(new EnvironmentLighting(environment, main,
            new EnvironmentMaps.Quality(64, 32, 4, 16, 64))).add(snow);
    }

    /** {@code count} frames, the snow updated before each (no time passing). */
    private void frames(int count) {
        for (int i = 0; i < count; i++) {
            snow.update(0);
            rig.frames(1);
        }
    }

    private void assumeShading(Shading shading) {
        org.junit.jupiter.api.Assumptions.assumeTrue(shading != Shading.VISIBILITY_BUFFER || device.primitiveIds(),
            "the visibility buffer needs primitive IDs in fragment shaders");
    }

    // ---------------------------------------------------------------- reading pixels

    private int red(int x, int y) {
        return rig.target().rgba(x, y)[0];
    }

    /** The share of the view's pixels that are snow (brighter than halfway between snow and bare). */
    private double share() {
        int snowy = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                snowy += red(x, y) > (SNOW + BARE) / 2 ? 1 : 0;
            }
        }
        return snowy / (double) (SIZE * SIZE);
    }

    /** The pixel column over world x {@code x} looking straight down from 2 m (90 degrees across the view). */
    private static int pixel(double x) {
        return (int) Math.floor((x / 2 + 1) / 2 * SIZE);
    }

    /** A panorama of half floats, mid grey everywhere. */
    private static MemorySegment uniformSky(int width, int height) {
        MemorySegment texels = Arena.ofAuto().allocate(8L * width * height);
        ValueLayout.OfShort half = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < width * height; i++) {
            for (int k = 0; k < 3; k++) {
                texels.set(half, 8L * i + 2L * k, Float.floatToFloat16(0.5f));
            }
            texels.set(half, 8L * i + 6, Float.floatToFloat16(1));
        }
        return texels;
    }
}
