package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.WeatherBrush;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.WeatherSample;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.engine.render.Camera;
import org.junit.jupiter.api.Test;

/**
 * The clouds drawn from the weather: their coverage follows the weather map, their shadow darkens the ground under
 * them (and only with the shadow mask), lightning lights them from inside and the ground through the scene, and the
 * cheap tiers draw the same weather. Small images, validated, a few frames each.
 */
class CloudRenderTest {

    private static final int W = 96;
    private static final int H = 64;
    private static final double FRAME = 1.0 / 60;

    /** A clear sky but for a disc of altostratus (2–6 km) 4 km across, west of the origin. */
    private static CloudScene disc(boolean mask) {
        CloudScene s = new CloudScene(W, H, Quality.MEDIUM, WeatherPreset.CLEAR, mask);
        s.weather.addBrush(WeatherBrush.set(4000, CloudType.ALTOSTRATUS, 1, 0), -3000, 0, 0);
        s.weather.refreshAll();
        return s;
    }

    /** Where pixel (x, y)'s ray meets altitude {@code altitude}: x, z into {@code out}; false if it never does. */
    private static boolean hit(CloudScene s, int x, int y, double altitude, double[] out) {
        Camera c = s.camera;
        float[] inverse = c.inverseViewProjection();
        double nx = (x + 0.5) / s.width * 2 - 1;
        double ny = (y + 0.5) / s.height * 2 - 1;
        double[] p = new double[4];
        for (int r = 0; r < 4; r++) {
            p[r] = inverse[r] * nx + inverse[4 + r] * ny + inverse[8 + r] * 0.5 + inverse[12 + r];
        }
        double dx = p[0] / p[3];
        double dy = p[1] / p[3];
        double dz = p[2] / p[3];
        double t = (altitude - c.position(1)) / dy;
        if (!(t > 0)) {
            return false;
        }
        out[0] = c.position(0) + dx * t;
        out[1] = c.position(2) + dz * t;
        return true;
    }

    /**
     * The mean luminance of the pixels whose rays meet altitude 4 km where the weather map has full cover, and where it
     * has none.
     */
    private static double[] coveredAndClear(CloudScene s, float[] rgb) {
        WeatherSample sample = new WeatherSample();
        double[] at = new double[2];
        double[] sums = new double[4];
        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                if (!hit(s, x, y, 4000, at)) {
                    continue;
                }
                s.weather.sample(at[0], 0, at[1], sample);
                float l = s.luminance(rgb, x, y);
                if (sample.coverage() > 0.9) {
                    sums[0] += l;
                    sums[1]++;
                } else if (sample.coverage() < 0.01) {
                    sums[2] += l;
                    sums[3]++;
                }
            }
        }
        assertThat(sums[1]).as("pixels over the disc").isGreaterThan(200);
        assertThat(sums[3]).as("pixels over the clear sky").isGreaterThan(200);
        return new double[] {sums[0] / sums[1], sums[2] / sums[3]};
    }

    @Test
    void coverageFollowsTheWeatherMap() {
        try (CloudScene s = disc(false)) {
            s.ground(0).sun(60, 180).look(-1500, 12_000, 0, 0.001, -1, 0.001, Math.toRadians(60));
            float[] rgb = s.frames(6, FRAME);
            Images.write("coverage", rgb, W, H, 1.0 / 30_000);
            double[] m = coveredAndClear(s, rgb);
            System.out.printf("Coverage: covered pixels %.0f cd/m2, clear %.0f cd/m2%n", m[0], m[1]);
            assertThat(m[0]).as("sunlit cloud tops where the map has cover").isGreaterThan(5000);
            assertThat(m[1]).as("the black ground, through 12 km of air, where it has none").isLessThan(m[0] * 0.1);
        }
    }

    @Test
    void theCloudsShadowDarkensTheGroundUnderThem() {
        double[] ratio = new double[2];
        for (int run = 0; run < 2; run++) {
            boolean mask = run == 1;
            try (CloudScene s = disc(mask)) {
                // Below the 2 km base, looking straight down at the ground across the disc's eastern edge, the sun high.
                s.sun(80, 180).look(1000, 1500, 0, 0.001, -1, 0.001, Math.toRadians(100));
                float[] rgb = s.frames(4, FRAME);
                Images.write(mask ? "shadow-mask" : "shadow-none", rgb, W, H, 1.0 / 10_000);
                WeatherSample sample = new WeatherSample();
                double[] at = new double[2];
                double under = 0;
                double open = 0;
                int underCount = 0;
                int openCount = 0;
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        if (!hit(s, x, y, 0, at)) {
                            continue;
                        }
                        // The cloud over this ground point, towards the sun (80 degrees: 0.7 km south at 4 km).
                        s.weather.sample(at[0], 0, at[1] + 4000 / Math.tan(Math.toRadians(80)), sample);
                        float l = s.luminance(rgb, x, y);
                        if (sample.coverage() > 0.9) {
                            under += l;
                            underCount++;
                        } else if (sample.coverage() < 0.01) {
                            open += l;
                            openCount++;
                        }
                    }
                }
                under /= underCount;
                open /= openCount;
                ratio[run] = under / open;
                System.out.printf("Ground %s the mask: under the cloud %.0f cd/m2 (%d px), in the open %.0f (%d px)%n",
                    mask ? "with" : "without", under, underCount, open, openCount);
                assertThat(underCount).isGreaterThan(200);
                assertThat(openCount).isGreaterThan(200);
            }
        }
        assertThat(ratio[0]).as("without the mask the ground is lit alike").isGreaterThan(0.9);
        assertThat(ratio[1]).as("with it, the ground under the cloud is in its shadow").isLessThan(0.2);
    }

    @Test
    void lightningLightsTheCloudsFromInsideAndTheGround() {
        try (CloudScene s = new CloudScene(W, H, Quality.MEDIUM, WeatherPreset.RAIN, false)) {
            s.sunIlluminance(0).flashLights(2).ground(0.25f);
            s.look(0, 2, 0, 0.001, 1, 0.001, Math.toRadians(100));
            float[] dark = s.frames(4, FRAME);
            // A flash 3 km up, its channel 2 km long, starting just before the next frame ends.
            double start = s.time() + 0.5 * FRAME;
            s.weather.lightning().add(start, FlashType.INTRACLOUD, -1000, 3000, -500, 1000, 3000, 500, -30, 1, 99L,
                -1);
            float[] lit = s.frames(1, FRAME);
            Images.write("flash", lit, W, H, 1.0 / 200);
            double before = mean(s, dark);
            double after = mean(s, lit);
            System.out.printf("Lightning: the clouds from below at night %.3f cd/m2, with a flash %.1f cd/m2%n", before,
                after);
            assertThat(s.clouds.flashes().count()).isEqualTo(1);
            // Night-adapted eyes see 0.01 cd/m2: a flash deep in a nimbostratus lights its base well beyond that.
            assertThat(after).as("a flash lights the clouds from inside").isGreaterThan(Math.max(0.05, 100 * before));
            assertThat(s.flashLights.light(0).intensity()).as("and a light of the scene").isGreaterThan(0);

            // The ground under the flash, lit through the scene's light.
            s.look(0, 300, 0, 0.001, -1, 0.001, Math.toRadians(90));
            double darkGround = mean(s, s.frames(2, FRAME)); // the flash over: dark again
            s.weather.lightning().add(s.time() + 0.5 * FRAME, FlashType.NEGATIVE_GROUND, 0, 3000, 0, 300, 0, 200, -30,
                1, 7L, -1);
            float[] strike = s.frames(1, FRAME);
            double litGround = mean(s, strike);
            Images.write("strike", strike, W, H, 1.0 / 20);
            assertThat(s.bolts.segments()).as("the ground flash's bolt").isGreaterThan(16);
            System.out.printf("Lightning: the ground at night %.4f cd/m2, under a ground flash %.2f cd/m2%n",
                darkGround, litGround);
            assertThat(litGround).as("the flash's scene light lights the ground").isGreaterThan(
                Math.max(1, 10 * darkGround));
        }
    }

    @Test
    void groundFlashesDrawTheirBolts() {
        try (CloudScene s = new CloudScene(W, H, Quality.MEDIUM, WeatherPreset.CLEAR, false)) {
            s.sunIlluminance(0).flashLights(1).ground(0.25f);
            s.look(0, 2, 4000, 0, 0.35, -1, Math.toRadians(60));
            s.frames(2, FRAME);
            s.weather.lightning().add(s.time() + 0.5 * FRAME, FlashType.NEGATIVE_GROUND, 0, 2500, 0, 100, 0, 0, -30,
                1, 3L, -1);
            float[] rgb = s.frames(1, FRAME);
            Images.write("bolt", rgb, W, H, 1.0 / 2000);
            int bright = 0;
            for (int y = 0; y < H; y++) {
                for (int x = 0; x < W; x++) {
                    bright += s.luminance(rgb, x, y) > 100 ? 1 : 0;
                }
            }
            System.out.printf("Bolt: %d segments, %d bright pixels%n", s.bolts.segments(), bright);
            assertThat(s.bolts.segments()).isGreaterThan(16);
            assertThat(bright).as("the bolt's pixels").isGreaterThan(H / 2);
        }
    }

    /** The mean luminance of the upper half of the image (the sky's) looking up. */
    private static double upward(CloudScene s, int frames) {
        s.look(0, 2, 0, 0.001, 1, 0.001, Math.toRadians(60));
        float[] rgb = s.frames(frames, FRAME);
        return mean(s, rgb);
    }

    @Test
    void aThickRainCloudIsDarkFromBelow() {
        double zenith;
        try (CloudScene s = new CloudScene(W, H, Quality.MEDIUM, WeatherPreset.CLEAR, false)) {
            s.sun(50, 180);
            zenith = upward(s, 4);
        }
        double sunlit;
        try (CloudScene s = disc(false)) {
            // Fair-weather tops in the sun, from above (as coverageFollowsTheWeatherMap sees them).
            s.ground(0).sun(50, 180).look(-1500, 12_000, 0, 0.001, -1, 0.001, Math.toRadians(60));
            sunlit = coveredAndClear(s, s.frames(4, FRAME))[0];
        }
        double base;
        try (CloudScene s = new CloudScene(W, H, Quality.MEDIUM, WeatherPreset.CLEAR, false)) {
            // A nimbostratus deck 0.5-4 km, raining: optical depth about a hundred.
            s.weather.addBrush(WeatherBrush.set(20_000, CloudType.NIMBOSTRATUS, 1, 0.6), 0, 0, 0);
            s.weather.refreshAll();
            s.sun(50, 180);
            float[] rgb;
            s.look(0, 2, 0, 0.001, 1, 0.001, Math.toRadians(60));
            rgb = s.frames(4, FRAME);
            Images.write("nimbostratus-below", rgb, W, H, 1 / (1.2 * Math.pow(2, 11)));
            base = mean(s, rgb);
        }
        System.out.printf("From below: clear zenith %.0f cd/m2, under nimbostratus %.0f; sunlit tops %.0f%n", zenith,
            base, sunlit);
        assertThat(base).as("a rain cloud's base is darker than the clear sky beside it").isLessThan(zenith);
        assertThat(base).as("and dark grey beside sunlit clouds").isLessThan(0.1 * sunlit);
    }

    @Test
    void aChangeOfCloudTypeIsContinuous() {
        try (CloudScene s = new CloudScene(W, H, Quality.MEDIUM, WeatherPreset.SCATTERED, false)) {
            s.settings.set(Clouds.LAYERED.setting("temporal"), false); // every frame its own
            s.settings.set(CloudSettings.EVOLUTION, 0f);
            s.sun(40, 200).look(0, 2, 0, -0.3, 0.3, -1, Math.toRadians(70));
            s.frames(2, 1);
            // Cumulus to stratocumulus to overcast stratus over 60 s, a frame a second.
            s.weather.setPreset(WeatherPreset.BROKEN, 30);
            float[] last = s.frames(1, 1);
            double[] steps = new double[60];
            for (int f = 0; f < steps.length; f++) {
                if (f == 30) {
                    s.weather.setPreset(WeatherPreset.OVERCAST, 30);
                }
                float[] rgb = s.frames(1, 1);
                double sum = 0;
                for (int i = 0; i < rgb.length; i++) {
                    sum += Math.abs(rgb[i] - last[i]);
                }
                steps[f] = sum / rgb.length;
                last = rgb;
            }
            double[] sorted = steps.clone();
            java.util.Arrays.sort(sorted);
            double median = sorted[steps.length / 2];
            double largest = sorted[steps.length - 1];
            System.out.printf("Type transition: frame-to-frame change median %.1f, largest %.1f cd/m2: %s%n", median,
                largest, java.util.Arrays.toString(java.util.Arrays.stream(steps).mapToLong(Math::round).toArray()));
            assertThat(largest).as("no frame jumps: the largest change a few times the typical one")
                .isLessThan(4 * median);
        }
    }

    @Test
    void everyTierDrawsTheSameWeather() {
        for (String kind : new String[] {"volumetric", "layered", "skybox"}) {
            try (CloudScene s = disc(false)) {
                Clouds.SLOT.choose(s.settings, kind.equals("volumetric") ? Clouds.VOLUMETRIC
                    : kind.equals("layered") ? Clouds.LAYERED : Clouds.SKYBOX);
                s.ground(0).sun(60, 180).look(-1500, 12_000, 0, 0.001, -1, 0.001, Math.toRadians(60));
                float[] rgb = s.frames(4, FRAME);
                Images.write("tier-" + kind, rgb, W, H, 1.0 / 30_000);
                double[] m = coveredAndClear(s, rgb);
                System.out.printf("Tier %s: covered %.0f cd/m2, clear %.0f cd/m2%n", kind, m[0], m[1]);
                assertThat(m[0]).as(kind + ": the clouds of the map").isGreaterThan(1000);
                assertThat(m[1]).as(kind + ": none where it has none").isLessThan(m[0] * 0.1);
            }
        }
    }

    private static double mean(CloudScene s, float[] rgb) {
        double sum = 0;
        for (int y = 0; y < s.height; y++) {
            for (int x = 0; x < s.width; x++) {
                sum += s.luminance(rgb, x, y);
            }
        }
        return sum / (s.width * s.height);
    }
}
