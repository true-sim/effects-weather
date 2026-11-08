package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.atmosphere.Quality;
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
 * shadows, overcast, a thunderstorm with lightning, flying through a layer and above it; films of a storm rolling in
 * over half an hour, cloud shadows sweeping over the ground, lightning under a storm, a climb through a cloud layer,
 * the tiers side by side, rain shafts under a moving cell, and cloud types changing. Exposed as a camera would be
 * (EV100 per shot: 15 sunny, 13 overcast, 11-12 under a storm; exposure 1 / (1.2 * 2^EV), Lagarde and de Rousiers
 * 2014), tone mapped with the ACES fit; the analytic test atmosphere (effects-atmosphere's sky is not there yet).
 */
class CloudShots {

    private static final int W = 1280;
    private static final int H = 720;
    private static final double FRAME = 1.0 / 30;

    private static boolean wanted() {
        return "true".equals(System.getenv("TRUESIM_SCREENSHOTS"));
    }

    /** The exposure of {@code ev100}: the luminance 1.2 * 2^EV maps to 1. */
    static double ev(double ev100) {
        return 1 / (1.2 * Math.pow(2, ev100));
    }

    /** A scene of effects-atmosphere's sky, its exposure in its scene units. */
    private static CloudScene scene(int width, int height, WeatherPreset preset) {
        return new CloudScene(width, height, WeatherConfig.forQuality(Quality.ULTRA), Quality.ULTRA, preset, true, true);
    }

    private static void shot(CloudScene s, String name, int frames, double ev100) {
        s.ev100(ev100);
        float[] rgb = s.frames(frames, 1.0 / 60);
        Path path = Images.write(name, rgb, s.width, s.height, 1, s.whiteBalance());
        System.out.println("Screenshot " + path.toAbsolutePath());
    }

    /** A storm 25 minutes into its life, carried by the westerlies to about 6 km west of the camera. */
    private static void storm(CloudScene s) {
        s.weather.spawnStorm(-24_000, 0, 6000);
        s.weather.spawnStorm(-32_000, 0, 1000);
        for (int i = 0; i < 25 * 60; i++) {
            s.weather.setFocus(0, 2, 0);
            s.weather.step();
        }
    }

    /** A ground flash from the storm in view, 7 km west: the frame it begins in. */
    private static float[] inView(CloudScene s) {
        s.weather.lightning().add(s.time() + 0.5 / 60, FlashType.NEGATIVE_GROUND, -7000, 5000, 500, -6600, 0, 900,
            -35, 3, 11L, -1);
        return s.frames(1, 1.0 / 60);
    }

    @Test
    void stills() {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.SCATTERED)) {
            s.sun(38, 200).look(0, 2, 0, -0.3, 0.22, -1, Math.toRadians(70));
            shot(s, "fair-cumulus-ground", 32, 14);
            s.look(0, 2, 0, 0.05, 1, -0.3, Math.toRadians(90));
            shot(s, "fair-cumulus-overhead", 32, 14);
            s.look(0, 600, 0, -0.3, -0.12, -1, Math.toRadians(70));
            shot(s, "fair-cumulus-shadows", 32, 14);
        }
        try (CloudScene s = scene(W, H, WeatherPreset.OVERCAST)) {
            s.sun(30, 160).look(0, 2, 0, 0.4, 0.18, -1, Math.toRadians(70));
            shot(s, "overcast-ground", 32, 14.5);
        }
        try (CloudScene s = scene(W, H, WeatherPreset.STORM)) {
            s.flashLights(4);
            storm(s);
            s.sun(30, 120).look(0, 2, 0, -1, 0.3, 0.15, Math.toRadians(75));
            shot(s, "storm-ground", 32, 13.5);
            float[] rgb = inView(s);
            Images.write("storm-lightning", rgb, W, H, 1, s.whiteBalance());
            s.sun(-8, 120).ev100(4);
            s.frames(24, 1.0 / 60, false);
            Images.write("storm-dusk", s.frames(1, 1.0 / 60), W, H, 1);
            rgb = inView(s);
            Images.write("storm-lightning-dusk", rgb, W, H, 1);
        }
        try (CloudScene s = scene(W, H, WeatherPreset.BROKEN)) {
            s.sun(35, 230);
            s.look(0, 1700, 0, -1, 0.05, -0.3, Math.toRadians(75));
            shot(s, "flying-through", 32, 14);
            s.look(0, 3200, 0, -1, -0.25, -0.3, Math.toRadians(75));
            shot(s, "flying-above", 32, 15);
        }
    }

    /** Renders {@code frames} frames into TRUESIM_SHOTS/{@code name}/fNNNN.png, {@code each} before each. */
    private static void film(CloudScene s, String name, int frames, int sub, double seconds, double ev100,
        IntConsumer each) throws Exception {
        Path dir = Images.directory().resolve(name);
        Files.createDirectories(dir);
        s.ev100(ev100);
        for (int f = 0; f < frames; f++) {
            each.accept(f);
            float[] rgb = null;
            for (int k = 0; k < sub; k++) {
                rgb = s.frames(1, seconds / sub, k == sub - 1);
            }
            Images.write(dir.resolve(String.format("f%04d", f)).toString(), rgb, s.width, s.height, 1,
                s.whiteBalance());
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
            film(s, "film-storm", 900, 8, 2, 15, f -> {
                double a = Math.toRadians(250 + 20.0 * f / 900);
                s.look(0, 2, 0, Math.sin(a), 0.22, -Math.cos(a), Math.toRadians(72));
                s.ev100(15 - 2.5 * f / 899.0); // the camera opens up as the storm darkens the day
            });
        }
    }

    /** Cloud shadows sweeping over the ground with the wind: 20 s, 4 s of weather a frame, from 900 m. */
    @Test
    void shadowsSweeping() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.SCATTERED)) {
            s.sun(40, 200).look(0, 600, 6000, 0, -0.3, -1, Math.toRadians(70));
            film(s, "film-shadows", 600, 4, 4, 14.7, f -> { });
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
            film(s, "film-lightning", 450, 1, FRAME, 7, f -> {
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
            film(s, "film-climb", 450, 2, FRAME, 14, f -> {
                double t = f / 449.0;
                double y = 200 + 3800 * t * t * (3 - 2 * t);
                s.look(0, y, -60.0 * f, 0, 0.12 - 0.3 * t, -1, Math.toRadians(75));
            });
        }
    }

    /** Rain shafts under a storm cell moving past: 20 s, 3 s of weather a frame. */
    @Test
    void rainShafts() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = scene(W, H, WeatherPreset.FAIR)) {
            s.flashLights(4);
            s.weather.spawnStorm(-22_000, 0, -12_000);
            for (int i = 0; i < 22 * 60; i++) {
                s.weather.setFocus(0, 2, 0);
                s.weather.step();
            }
            s.sun(25, 120).look(0, 2, 0, -0.6, 0.12, -1, Math.toRadians(70));
            film(s, "film-rain", 600, 3, 3, 13.5, f -> { });
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
                film(s, "film-tier-" + tier, 300, 2, 1, 15, f -> {
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
            film(s, "film-transition-overcast", 300, 2, 2, 14.5, f -> { });
        }
        try (CloudScene s = scene(640, 360, WeatherPreset.FAIR)) {
            s.weather.spawnStorm(-14_000, 0, -3000);
            s.sun(35, 160);
            s.look(0, 2, 0, -1, 0.3, 0.2, Math.toRadians(70));
            film(s, "film-transition-storm", 300, 2, 6, 14.5, f -> { });
        }
    }

    /**
     * The same type transitions in the analytic test sky and exposure the renderer before the type blending was
     * filmed in (its films: before-transition-*), for a side-by-side comparison.
     */
    @Test
    void typeTransitionsAsBefore() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = new CloudScene(640, 360, Quality.ULTRA, WeatherPreset.FAIR, true)) {
            s.weather.setPreset(WeatherPreset.OVERCAST, 600);
            s.sun(35, 200);
            s.look(0, 2, 0, -0.2, 0.25, -1, Math.toRadians(70));
            filmPhysical(s, "after-transition-overcast", 300, 2, 2, 14.5);
        }
        try (CloudScene s = new CloudScene(640, 360, Quality.ULTRA, WeatherPreset.FAIR, true)) {
            s.weather.spawnStorm(-14_000, 0, -3000);
            s.sun(35, 160);
            s.look(0, 2, 0, -1, 0.3, 0.2, Math.toRadians(70));
            filmPhysical(s, "after-transition-storm", 300, 2, 6, 14.5);
        }
    }

    private static void filmPhysical(CloudScene s, String name, int frames, int sub, double seconds, double ev100)
        throws Exception {
        Path dir = Images.directory().resolve(name);
        Files.createDirectories(dir);
        for (int f = 0; f < frames; f++) {
            float[] rgb = null;
            for (int k = 0; k < sub; k++) {
                rgb = s.frames(1, seconds / sub, k == sub - 1);
            }
            Images.write(dir.resolve(String.format("f%04d", f)).toString(), rgb, s.width, s.height, ev(ev100));
        }
    }
}
