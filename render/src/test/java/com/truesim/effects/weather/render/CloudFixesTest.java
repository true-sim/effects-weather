package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.WeatherBrush;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.WindProfile;
import com.truesim.effects.weather.clouds.CloudType;
import org.junit.jupiter.api.Test;

/**
 * What the second round of the clouds fixed, measured in effects-atmosphere's sky: the horizon below a deck (no bright
 * line of sky between the deck and the ground), the colour of high clouds lit by the sun after it set at the ground
 * (the atmosphere's own sunlight at their altitude, not a saturated pink), and the interleaved march (temporal
 * amortization) converging to the image of the full march.
 */
class CloudFixesTest {

    private static final double FRAME = 1.0 / 60;

    private static CloudScene scene(int w, int h, WeatherConfig config, WeatherPreset preset) {
        return new CloudScene(w, h, config, Quality.MEDIUM, preset, true, true);
    }

    /**
     * Under a stratocumulus deck from 200 m, the rows across the horizon: the deck meets the ground (the clouds lie on
     * the planet, curving down to the horizon as the atmosphere's ground does), so no row there is much brighter than
     * the deck above it and the ground below it. Before, the flat deck ended above the horizon and a line of the bright
     * horizon sky showed through.
     */
    @Test
    void theHorizonUnderADeckHasNoBrightLine() {
        int w = 160;
        int h = 240;
        WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM).withWind(WindProfile.calm());
        try (CloudScene s = scene(w, h, config, WeatherPreset.CLEAR)) {
            s.weather.addBrush(WeatherBrush.set(400_000, CloudType.STRATOCUMULUS, 1, 0), 0, 0, 0);
            s.weather.refreshAll();
            s.ev100(12).sun(35, 160).look(0, 200, 0, 0, 0, -1, Math.toRadians(6)); // 0.025 degrees a row
            float[] rgb = s.frames(16, FRAME);
            double[] rows = new double[h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    rows[y] += s.luminance(rgb, x, y) / w;
                }
            }
            // The worst row over the brighter of the rows 12 above and 12 below it (0.3 degrees).
            double worst = 0;
            int at = 0;
            int horizon = h / 2 + (int) Math.round(Math.toDegrees(Math.sqrt(2 * 200 / 6.36e6)) / 6 * h);
            // From half a degree above the horizon (the flat deck of before ended at the horizontal, 0.45 degrees up)
            // to 0.2 below it.
            for (int y = horizon - 20; y < horizon + 8; y++) {
                double around = Math.max(rows[y - 12], rows[y + 12]);
                double ratio = rows[y] / Math.max(around, 1e-9);
                if (ratio > worst) {
                    worst = ratio;
                    at = y;
                }
            }
            System.out.printf("Horizon under a deck: brightest row %.3f times its neighbours' (row %d: %.4g, above "
                + "%.4g, below %.4g scene units)%n", worst, at, rows[at], rows[at - 12], rows[at + 12]);
            Images.writeShown("horizon-under-deck", s.shown(), w, h);
            assertThat(worst).as("no bright line at the horizon").isLessThan(1.2);
        }
    }

    /**
     * Dusk in the atmosphere's colours. Cirrus 8 to 10 km up towards the sun 2 degrees below the horizon, still in its
     * light: the brightest clouds' colour is a mix of the atmosphere's own sunlight at their altitude (its
     * transmittance table, with ozone) and its sky's light (the sky-view table over the hemisphere), within 0.03 of the
     * segment between the two in chromaticity. Altocumulus 2.5 to 4.5 km overhead with the sun 4 degrees down, in the
     * planet's shadow (its edge 15 km up there): lit by the sky alone, their colour is the sky light's, not the red of
     * a sun they cannot see. Before, every sample's sunlight was an analytic Chapman transmittance without the
     * planet's shadow and the sky's light an estimate that is black below the horizon: clouds in the shadow glowed a
     * saturated pink-red, the rest black.
     */
    @Test
    void cloudsAtDuskHaveTheAtmospheresColours() {
        double[] lit = dusk(CloudType.CIRRUS, -2, 9, true);
        double[] shadowed = dusk(CloudType.ALTOCUMULUS, -4, 3.5, false);
        assertThat(lit[2]).as("the sky's light at dusk (lux, scene units)").isPositive();
        assertThat(lit[0]).as("sunlit cirrus: a mix of the sunlight and the sky light").isLessThan(0.03);
        assertThat(shadowed[1]).as("clouds in the planet's shadow: the sky light's colour").isLessThan(0.06);
    }

    /**
     * The brightest tenth of a dusk sky of {@code type} with the sun at {@code elevation} degrees, looking towards the
     * sun ({@code towards}) or up: its chromaticity's distance from the segment between the atmosphere's sunlight at
     * {@code km} and the sky light, and from the sky light's.
     */
    private static double[] dusk(CloudType type, double elevation, double km, boolean towards) {
        int w = 96;
        int h = 64;
        WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM).withWind(WindProfile.calm());
        try (CloudScene s = scene(w, h, config, WeatherPreset.CLEAR)) {
            s.weather.addBrush(WeatherBrush.set(200_000, type, type == CloudType.CIRRUS ? 0.8 : 0.6, 0), 0, 0, 0);
            s.weather.refreshAll();
            double a = Math.toRadians(200);
            s.ev100(2).sun(elevation, 200);
            if (towards) {
                s.look(0, 2, 0, Math.sin(a), 0.47, -Math.cos(a), Math.toRadians(60));
            } else {
                s.look(0, 2, 0, 0.001, 1, 0.001, Math.toRadians(60));
            }
            float[] rgb = s.frames(24, FRAME);
            double[] l = new double[w * h];
            for (int i = 0; i < w * h; i++) {
                l[i] = 0.2126 * rgb[3 * i] + 0.7152 * rgb[3 * i + 1] + 0.0722 * rgb[3 * i + 2];
            }
            double[] sorted = l.clone();
            java.util.Arrays.sort(sorted);
            double threshold = sorted[(int) (0.9 * sorted.length)];
            double[] sum = new double[3];
            for (int i = 0; i < w * h; i++) {
                if (l[i] >= threshold) {
                    for (int c = 0; c < 3; c++) {
                        sum[c] += rgb[3 * i + c];
                    }
                }
            }
            double[] sun = new double[3];
            double[] top = new double[3];
            double[] t = new double[3];
            s.sky.sun(sun, top);
            s.atmosphere.transmittance(0, km, t);
            double[] sky = new double[3];
            s.clouds.skyLight(sky);
            double[] cloud = chromaticity(sum);
            double[] sunColour = chromaticity(new double[] {top[0] * t[0], top[1] * t[1], top[2] * t[2]});
            double[] skyColour = chromaticity(sky);
            double off = Double.isNaN(sunColour[0]) ? Double.NaN : segmentDistance(cloud, sunColour, skyColour);
            double fromSky = Math.sqrt(Math.pow(cloud[0] - skyColour[0], 2) + Math.pow(cloud[1] - skyColour[1], 2)
                + Math.pow(cloud[2] - skyColour[2], 2));
            System.out.printf("Dusk %s, sun %.0f degrees: chromaticity %.3f %.3f %.3f; sunlight at %.1f km %.3f %.3f "
                + "%.3f, sky light %.3f %.3f %.3f; %.3f from their mix, %.3f from the sky light's%n", type, elevation,
                cloud[0], cloud[1], cloud[2], km, sunColour[0], sunColour[1], sunColour[2], skyColour[0],
                skyColour[1], skyColour[2], off, fromSky);
            Images.writeShown("dusk-" + type.name().toLowerCase(java.util.Locale.ROOT), s.shown(), w, h);
            return new double[] {off, fromSky, sky[0] + sky[1] + sky[2]};
        }
    }

    /** The distance (in chromaticity) from {@code p} to the segment from {@code a} to {@code b}. */
    private static double segmentDistance(double[] p, double[] a, double[] b) {
        double ab = 0;
        double ap = 0;
        for (int c = 0; c < 3; c++) {
            ab += (b[c] - a[c]) * (b[c] - a[c]);
            ap += (p[c] - a[c]) * (b[c] - a[c]);
        }
        double t = Math.clamp(ap / Math.max(ab, 1e-12), 0, 1);
        double d = 0;
        for (int c = 0; c < 3; c++) {
            double e = p[c] - (a[c] + t * (b[c] - a[c]));
            d += e * e;
        }
        return Math.sqrt(d);
    }

    private static double[] chromaticity(double[] rgb) {
        double sum = rgb[0] + rgb[1] + rgb[2];
        return new double[] {rgb[0] / sum, rgb[1] / sum, rgb[2] / sum};
    }

    /**
     * The march's cost settings (the defaults: an interleave of 2, the light march every second cloud sample and two
     * light steps beyond 16 km, coarser fine steps, rays stopped at 1 % transmittance, no detail beyond 30 km) change
     * the image little: converged in a still scene, within 4 % of the reference march's (every pixel every frame, the
     * light march at every sample, the first round's steps); and the interleave alone within 1.5 %.
     */
    @Test
    void theCheaperMarchConvergesToTheReferenceMarch() {
        float[] reference = still(true, false, 96);
        float[] later = still(true, false, 120);
        // Interleaved, each pixel is new every fourth frame: four times the frames to converge as far.
        float[] interleaved = still(true, true, 4 * 96);
        float[] fast = still(false, true, 4 * 96);
        double floor = difference(reference, later);
        double alone = difference(reference, interleaved);
        double all = difference(reference, fast);
        System.out.printf("Cheaper march against the reference: interleave alone %.2f %%, every saving %.2f %% of the "
            + "mean (the reference against itself 24 frames later: %.2f %%)%n", 100 * alone, 100 * all, 100 * floor);
        assertThat(alone).isLessThan(0.015);
        assertThat(all).isLessThan(0.04);
    }

    /** A converged still of scattered cumulus: the reference march's settings, interleaved or not, or the defaults. */
    private static float[] still(boolean reference, boolean interleave, int frames) {
        WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM).withWind(WindProfile.calm());
        try (CloudScene s = scene(128, 72, config, WeatherPreset.SCATTERED)) {
            s.settings.set(CloudSettings.EVOLUTION, 0f);
            if (reference) {
                var k = Clouds.LAYERED;
                s.settings.set(k.setting("interleave"), interleave ? 2 : 1).set(k.setting("lightEvery"), 1)
                    .set(k.setting("lightStepsFar"), 4).set(k.setting("lightFar"), 1e6f)
                    .set(k.setting("fineRatio"), 0.35f).set(k.setting("opaque"), 0.003f)
                    .set(k.setting("detailFar"), 1e6f);
            }
            s.ev100(13).sun(40, 200).look(0, 2, 0, -0.3, 0.25, -1, Math.toRadians(70));
            return s.frames(frames, FRAME);
        }
    }

    /** The mean absolute difference of two images over the mean of the first. */
    private static double difference(float[] a, float[] b) {
        double d = 0;
        double mean = 0;
        for (int i = 0; i < a.length; i++) {
            d += Math.abs(a[i] - b[i]);
            mean += a[i];
        }
        return d / mean;
    }
}
