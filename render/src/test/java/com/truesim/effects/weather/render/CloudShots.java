package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.render.AtmosphereExposure;
import com.truesim.effects.weather.WeatherBrush;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherPreset;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.lightning.FlashType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

/**
 * Screenshots and films (TRUESIM_SCREENSHOTS=true; pictures into TRUESIM_SHOTS): fair-weather cumulus and their
 * shadows, overcast, a thunderstorm with lightning, flying through a layer and above it, an anvil from below, high
 * clouds at dusk; films of a storm rolling in over half an hour, cloud shadows sweeping over the ground, lightning under
 * a storm, a climb through a cloud layer, the tiers side by side, rain shafts under a moving cell, cloud types changing,
 * and a sunset under high clouds. effects-atmosphere's sky, aerial perspective and sky capture; the ground grassland's
 * albedo; exposed as a camera's auto exposure would (effects-atmosphere's {@link AtmosphereExposure}: the EV100 of the
 * frame's log-average luminance, eased over time in films), white balanced to the daylight, tone mapped by the engine's
 * display transform with AgX ({@code display.toneCurve}).
 */
class CloudShots {

    private static final int W = 1280;
    private static final int H = 720;
    private static final double FRAME = 1.0 / 30;
    /** Seconds a film's exposure takes to follow the light (a camera's adaptation). */
    private static final double ADAPTATION = 1.0;

    private static boolean wanted() {
        return "true".equals(System.getenv("TRUESIM_SCREENSHOTS"));
    }

    /** A scene of effects-atmosphere's sky, its exposure in its scene units. */
    static CloudScene scene(int width, int height, WeatherPreset preset) {
        return new CloudScene(width, height, WeatherConfig.forQuality(Quality.ULTRA), Quality.ULTRA, preset, true, true);
    }

    /** The physical log-average luminance (cd/m²) of {@code rgb} in {@code s}'s scene units. */
    static double logAverage(CloudScene s, float[] rgb) {
        double units = s.atmosphere.sceneUnits();
        double sum = 0;
        int n = 0;
        for (int i = 0; i < rgb.length; i += 3 * 7) { // every 7th pixel is plenty
            double l = (0.2126 * rgb[i] + 0.7152 * rgb[i + 1] + 0.0722 * rgb[i + 2]) / units;
            sum += Math.log(Math.max(l, 1e-6));
            n++;
        }
        return Math.exp(sum / n);
    }

    /**
     * Exposes {@code s} as an auto exposure would: frames at the current EV, metered, then the EV100 of their
     * log-average luminance plus {@code bias}, and frames again until the clouds' history has settled.
     */
    static double expose(CloudScene s, int frames, double bias) {
        double ev = 0;
        for (int k = 0; k < 2; k++) {
            float[] rgb = s.frames(frames, 1.0 / 60);
            ev = AtmosphereExposure.ev100(logAverage(s, rgb)) + bias;
            s.ev100(ev);
        }
        return ev;
    }

    private static void shot(CloudScene s, String name, int frames) {
        shot(s, name, frames, 0);
    }

    static void shot(CloudScene s, String name, int frames, double bias) {
        double ev = expose(s, frames, bias);
        s.balance();
        s.frames(frames, 1.0 / 60);
        float[] rgb = s.frames(1, 1.0 / 60);
        float[] shown = s.shown();
        Path path = Images.writeShown(name, shown, s.width, s.height);
        double mean = 0;
        for (float v : shown) {
            mean += v;
        }
        System.out.printf("Screenshot %s (EV100 %.1f, log-average %.1f cd/m2, mean shown %.3f)%n",
            path.toAbsolutePath(), ev, logAverage(s, rgb), mean / shown.length);
    }

    /**
     * Runs the weather {@code minutes} of its time with the focus at ({@code x}, {@code y}, {@code z}) (steps are a
     * second at most qualities, half a second at ULTRA).
     */
    static void run(CloudScene s, double minutes, double x, double y, double z) {
        double end = s.weather.time() + minutes * 60;
        while (s.weather.time() < end - 1e-9) {
            s.weather.setFocus(x, y, z);
            s.weather.step();
        }
    }

    /** A storm 25 minutes into its life, carried by the westerlies (14 m/s) to about 6 km west of the camera. */
    private static void storm(CloudScene s) {
        s.weather.spawnStorm(-27_000, 0, 6000);
        s.weather.spawnStorm(-36_000, 0, 1000);
        run(s, 25, 0, 2, 0);
    }

    /** A ground flash from the storm in view, 7 km west, at the frame it begins in. */
    private static void inView(CloudScene s) {
        s.weather.lightning().add(s.time() + 0.5 / 60, FlashType.NEGATIVE_GROUND, -7000, 5000, 500, -6600, 0, 900,
            -35, 3, 11L, -1);
        s.frames(1, 1.0 / 60);
    }

    @Test
    void stills() {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.SCATTERED)) {
            s.sun(38, 200).look(0, 2, 0, -0.3, 0.22, -1, Math.toRadians(70));
            shot(s, "fair-cumulus-ground", 32);
            s.look(0, 2, 0, 0.05, 1, -0.3, Math.toRadians(90));
            shot(s, "fair-cumulus-overhead", 32);
            s.look(0, 600, 0, -0.3, -0.12, -1, Math.toRadians(70));
            shot(s, "fair-cumulus-shadows", 32);
        }
        try (CloudScene s = scene(W, H, WeatherPreset.OVERCAST)) {
            s.sun(30, 160).look(0, 2, 0, 0.4, 0.18, -1, Math.toRadians(70));
            shot(s, "overcast-ground", 32);
        }
        try (CloudScene s = scene(W, H, WeatherPreset.STORM)) {
            s.flashLights(4);
            storm(s);
            s.sun(30, 120).look(0, 2, 0, -1, 0.3, 0.15, Math.toRadians(75));
            shot(s, "storm-ground", 32);
            inView(s);
            Images.writeShown("storm-lightning", s.shown(), W, H);
            s.sun(-8, 120);
            shot(s, "storm-dusk", 24);
            inView(s);
            Images.writeShown("storm-lightning-dusk", s.shown(), W, H);
        }
        try (CloudScene s = scene(W, H, WeatherPreset.BROKEN)) {
            s.sun(35, 230);
            s.look(0, 1700, 0, -1, 0.05, -0.3, Math.toRadians(75));
            shot(s, "flying-through", 32);
            s.look(0, 3200, 0, -1, -0.25, -0.3, Math.toRadians(75));
            shot(s, "flying-above", 32);
        }
        anvils();
        dusk();
    }

    /**
     * A mature cumulonimbus 40 minutes into its life, from the side 28 km away (its tower, flat base, rain, anvil
     * spreading downwind), and its anvil overhead from below, 7 km downwind of the core.
     */
    private static void anvils() {
        try (CloudScene s = scene(W, H, WeatherPreset.FAIR)) {
            s.weather.spawnStorm(-30_000, 0, 0);
            run(s, 40, 0, 2, 0);
            double[] c = cell(s);
            s.sun(40, 200).look(c[0] + 6000, 2, c[1] + 28_000, -6000, 0.3 * 28_600, -28_000, Math.toRadians(70));
            shot(s, "cumulonimbus-side", 32);
            s.look(c[0] + 7000, 2, c[1], -0.1, 1, 0.05, Math.toRadians(90));
            shot(s, "anvil-below", 32);
        }
    }

    /** The core (x, z) of the storm cell with the widest anvil, or the origin. */
    private static double[] cell(CloudScene s) {
        var storms = s.weather.storms();
        int best = -1;
        for (int i = 0; i < storms.capacity(); i++) {
            if (storms.active(i)) {
                System.out.printf("Cell %d at %.0f %.0f: top %.0f, anvil %.2f, core radius %.0f, %s%n", i,
                    storms.position(i, 0), storms.position(i, 2), storms.top(i), storms.anvil(i),
                    storms.coreRadius(i), storms.cloudType(i));
                if (best < 0 || storms.anvil(i) > storms.anvil(best)) {
                    best = i;
                }
            }
        }
        return best < 0 ? new double[] {0, 0} : new double[] {storms.position(best, 0), storms.position(best, 2)};
    }

    /** High clouds (altocumulus and cirrus) and a cumulus field with the sun 2 and 5 degrees below the horizon. */
    private static void dusk() {
        try (CloudScene s = scene(W, H, WeatherPreset.SCATTERED)) {
            s.weather.addBrush(WeatherBrush.set(60_000, CloudType.ALTOCUMULUS, 0.55, 0), 0, 0, -20_000);
            s.weather.addBrush(WeatherBrush.set(30_000, CloudType.CIRRUS, 0.7, 0), 25_000, 0, -30_000);
            s.look(0, 2, 0, 0.3, 0.25, -1, Math.toRadians(75));
            s.sun(-2, 200);
            shot(s, "dusk-high-clouds", 32);
            s.sun(-5, 200);
            shot(s, "dusk-late", 32);
        }
    }

    /**
     * Renders {@code frames} frames into TRUESIM_SHOTS/{@code name}/fNNNN.png, {@code each} before each; exposed as an
     * auto exposure adapting over {@link #ADAPTATION} seconds of film time ({@code bias} EV over its choice).
     */
    static void film(CloudScene s, String name, int frames, int sub, double seconds, double bias,
        IntConsumer each) throws Exception {
        Path dir = Images.directory().resolve(name);
        Files.createDirectories(dir);
        each.accept(0);
        double ev = expose(s, 16, bias);
        for (int f = 0; f < frames; f++) {
            each.accept(f);
            s.balance();
            float[] rgb = null;
            for (int k = 0; k < sub; k++) {
                rgb = s.frames(1, seconds / sub, k == sub - 1);
            }
            Images.writeShown(dir.resolve(String.format("f%04d", f)).toString(), s.shown(), s.width, s.height);
            ev = AtmosphereExposure.adapt(ev, AtmosphereExposure.ev100(logAverage(s, rgb)) + bias, FRAME, ADAPTATION);
            s.ev100(ev);
        }
        System.out.println("Film frames " + dir.toAbsolutePath());
    }

    /** A storm rolling in over half an hour of game time: 900 frames of 2 seconds each, for a 30-second film. */
    @Test
    void stormRollingIn() throws Exception {
        assumeThat(wanted()).isTrue();
        WeatherConfig config = WeatherConfig.forQuality(Quality.ULTRA);
        try (CloudScene s = new CloudScene(W, H, config, Quality.ULTRA, WeatherPreset.FAIR, true, true)) {
            s.flashLights(4);
            s.weather.setPreset(WeatherPreset.STORM, 1800);
            s.weather.spawnStorm(-26_000, 0, 6000);
            s.weather.spawnStorm(-30_000, 0, -2000);
            s.weather.spawnStorm(-34_000, 0, 9000);
            s.sun(32, 140);
            film(s, "film-storm", 900, 8, 2, 0, f -> {
                double a = Math.toRadians(250 + 20.0 * f / 900);
                s.look(0, 2, 0, Math.sin(a), 0.22, -Math.cos(a), Math.toRadians(72));
            });
        }
    }

    /** Cloud shadows sweeping over the ground with the wind: 20 s, 4 s of weather a frame, from 900 m. */
    @Test
    void shadowsSweeping() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.SCATTERED)) {
            s.sun(40, 200).look(0, 600, 6000, 0, -0.3, -1, Math.toRadians(70));
            film(s, "film-shadows", 600, 4, 4, 0, f -> { });
        }
    }

    /** Lightning under a dark storm base at dusk: 15 s in real time, a flash every couple of seconds. */
    @Test
    void lightningUnderAStorm() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.STORM)) {
            s.flashLights(4);
            storm(s);
            s.sun(-2, 120).look(0, 2, 0, -1, 0.18, 0.1, Math.toRadians(80));
            java.util.Random random = new java.util.Random(5);
            film(s, "film-lightning", 450, 1, FRAME, 0, f -> {
                if (f % 50 == 20) {
                    double x = -4000 - 4000 * random.nextDouble();
                    double z = -3000 + 6000 * random.nextDouble();
                    boolean ground = f % 100 == 20;
                    s.weather.lightning().add(s.time() + 0.5 * FRAME, ground ? FlashType.NEGATIVE_GROUND
                        : FlashType.INTRACLOUD, x, 4500, z, ground ? x + 500 : x + 3000, ground ? 0 : 5000, z + 300,
                        -20 - 30 * random.nextDouble(), ground ? 3 : 1, random.nextLong(), -1);
                }
            });
        }
    }

    /** A climb from below a stratocumulus layer, through it, to above it: 15 s. */
    @Test
    void climbThroughTheClouds() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.BROKEN)) {
            s.weather.addBrush(WeatherBrush.set(30_000, CloudType.STRATOCUMULUS, 0.95, 0), 0, 0, -20_000);
            s.sun(35, 160);
            film(s, "film-climb", 450, 2, FRAME, 0, f -> {
                double t = f / 449.0;
                double y = 200 + 3800 * t * t * (3 - 2 * t);
                s.look(0, y, -60.0 * f, 0, 0.12 - 0.3 * t, -1, Math.toRadians(75));
            });
        }
    }

    /**
     * Rain shafts under a storm cell carried past by the westerlies: 30 min of weather in 20 s (3 s a frame), the camera
     * turned to keep the cell in view, from 21 km west-northwest to its north.
     */
    @Test
    void rainShafts() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.FAIR)) {
            s.flashLights(4);
            s.weather.spawnStorm(-45_000, 0, -16_000);
            run(s, 25, 0, 2, 0);
            s.sun(25, 120);
            film(s, "film-rain", 600, 3, 3, 0, f -> {
                double[] c = cell(s);
                double d = Math.hypot(c[0], c[1]);
                s.look(0, 2, 0, c[0] / d, 0.14, c[1] / d, Math.toRadians(70));
            });
        }
    }

    /**
     * A sunset under altocumulus and cirrus over a cumulus field: the sun from 4 degrees above the horizon to 6 below
     * in 20 s (a minute of sunset a second), looking towards it.
     */
    @Test
    void duskFilm() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.SCATTERED)) {
            s.weather.addBrush(WeatherBrush.set(60_000, CloudType.ALTOCUMULUS, 0.55, 0), 0, 0, -20_000);
            s.weather.addBrush(WeatherBrush.set(30_000, CloudType.CIRRUS, 0.7, 0), 25_000, 0, -30_000);
            s.look(0, 2, 0, 0.3, 0.25, -1, Math.toRadians(75));
            film(s, "film-dusk", 600, 1, 0.1, 0, f -> s.sun(4 - 10.0 * f / 599, 200));
        }
    }

    /** The three tiers on the same weather, 640 x 360 each: 10 s of a slow pan. */
    @Test
    void tiers() throws Exception {
        assumeThat(wanted()).isTrue();
        for (String tier : new String[] {"skybox", "layered", "volumetric"}) {
            try (CloudScene s = scene(640, 360, WeatherPreset.SCATTERED)) {
                Clouds.SLOT.choose(s.settings, tier.equals("volumetric") ? Clouds.VOLUMETRIC
                    : tier.equals("layered") ? Clouds.LAYERED : Clouds.SKYBOX);
                s.sun(35, 200);
                film(s, "film-tier-" + tier, 300, 2, 1, 0, f -> {
                    double a = Math.toRadians(-20 + 40.0 * f / 300);
                    s.look(0, 2, 0, Math.sin(a), 0.2, -Math.cos(a), Math.toRadians(70));
                });
            }
        }
    }

    /**
     * Cloud types changing, slowed: fair cumulus to overcast stratus over 10 minutes, and a storm cell growing from
     * towering cumulus into a cumulonimbus, 640 x 360 each, 10 s.
     */
    @Test
    void typeTransitions() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(640, 360, WeatherPreset.FAIR)) {
            s.weather.setPreset(WeatherPreset.OVERCAST, 600);
            s.sun(35, 200);
            s.look(0, 2, 0, -0.2, 0.25, -1, Math.toRadians(70));
            film(s, "film-transition-overcast", 300, 2, 2, 0, f -> { });
        }
        try (CloudScene s = scene(640, 360, WeatherPreset.FAIR)) {
            s.weather.spawnStorm(-14_000, 0, -3000);
            s.sun(35, 160);
            s.look(0, 2, 0, -1, 0.3, 0.2, Math.toRadians(70));
            film(s, "film-transition-storm", 300, 2, 6, 0, f -> { });
        }
    }

    /**
     * A cumulus field into which a storm cell grows, from towering cumulus to a cumulonimbus with its anvil and rain, over
     * half an hour of weather (6 s a frame, 10 s of film), the camera turned so the cell stays in view as the westerlies
     * carry it (14 m/s): from 38 km west-northwest to 13 km. And the ground under the passing weather close up, from
     * 30 m (its shadow and light, frame to frame).
     */
    @Test
    void cumulusToCumulonimbus() throws Exception {
        assumeThat(wanted()).isTrue();
        for (boolean ground : new boolean[] {false, true}) {
            try (CloudScene s = scene(W, H, WeatherPreset.FAIR)) {
                s.weather.spawnStorm(-38_000, 0, -14_000);
                s.sun(35, 160);
                film(s, ground ? "film-transition-ground" : "film-transition-cb", 300, 2, 6, 0, f -> {
                    if (ground) {
                        s.look(0, 30, 0, -0.6, -0.55, -0.4, Math.toRadians(70));
                    } else {
                        double[] c = cell(s);
                        double dx = c[0];
                        double dz = c[1];
                        double d = Math.hypot(dx, dz);
                        s.look(0, 2, 0, dx / d, 0.32, dz / d, Math.toRadians(70));
                    }
                });
            }
        }
    }

    /**
     * The ground close up from 30 m in real time (a frame a thirtieth of a second) under a storm 40 minutes old and
     * fair cumulus: 10 s, so whatever changes from frame to frame is the renderer's (noise, flicker), not weather.
     */
    @Test
    void groundRealTime() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.FAIR)) {
            s.weather.spawnStorm(-38_000, 0, -14_000);
            run(s, 40, 0, 2, 0);
            s.sun(35, 160);
            film(s, "film-ground-realtime", 300, 1, FRAME, 0, f -> s.look(0, 30, 0, -0.6, -0.55, -0.4,
                Math.toRadians(70)));
        }
    }
}
