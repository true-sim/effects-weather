package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherPreset;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Screenshots and the storm film (TRUESIM_SCREENSHOTS=true; pictures into TRUESIM_SHOTS): fair-weather cumulus,
 * overcast and a thunderstorm with lightning from the ground, flying through a cloud layer and above it, and a storm
 * rolling in over half an hour of game time as frames for a 30-second film. Exposed automatically (the log-average
 * luminance at middle grey), tone mapped with the ACES fit, the analytic test sky behind (effects-atmosphere's sky is
 * not there yet).
 */
class CloudShots {

    private static final int W = 1280;
    private static final int H = 720;

    private static boolean wanted() {
        return "true".equals(System.getenv("TRUESIM_SCREENSHOTS"));
    }

    /** Middle grey over the image's log-average luminance (Reinhard et al. 2002), times {@code key}. */
    static double exposure(CloudScene s, float[] rgb, double key) {
        double sum = 0;
        int n = s.width * s.height;
        for (int y = 0; y < s.height; y++) {
            for (int x = 0; x < s.width; x++) {
                sum += Math.log(1e-3 + s.luminance(rgb, x, y));
            }
        }
        return key * 0.18 / Math.exp(sum / n);
    }

    private static void shot(CloudScene s, String name, int frames, double key) {
        float[] rgb = s.frames(frames, 1.0 / 60);
        Path path = Images.write(name, rgb, s.width, s.height, exposure(s, rgb, key));
        System.out.println("Screenshot " + path.toAbsolutePath());
    }

    @Test
    void fairCumulus() {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = new CloudScene(W, H, Quality.ULTRA, WeatherPreset.SCATTERED, true)) {
            s.sun(38, 200).look(0, 2, 0, -0.3, 0.22, -1, Math.toRadians(70));
            shot(s, "fair-cumulus-ground", 24, 1);
            s.look(0, 2, 0, 0.05, 1, -0.3, Math.toRadians(90));
            shot(s, "fair-cumulus-overhead", 24, 1);
            // From a hill, 500 m up: the clouds' shadows on the ground.
            s.look(0, 500, 0, -0.3, -0.08, -1, Math.toRadians(70));
            shot(s, "fair-cumulus-shadows", 24, 1);
        }
    }

    @Test
    void overcast() {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = new CloudScene(W, H, Quality.ULTRA, WeatherPreset.BROKEN, true)) {
            s.weather.setPreset(WeatherPreset.OVERCAST, 0);
            s.weather.advance(2);
            s.weather.refreshAll();
            s.sun(30, 160).look(0, 2, 0, 0.4, 0.18, -1, Math.toRadians(70));
            shot(s, "overcast-ground", 24, 1);
        }
    }

    @Test
    void stormWithLightning() {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = new CloudScene(W, H, Quality.ULTRA, WeatherPreset.STORM, true)) {
            s.flashLights(4);
            // A cell 14 km west-south-west, grown into a cumulonimbus over 25 minutes.
            s.weather.spawnStorm(-13_000, 0, 4000);
            s.weather.spawnStorm(-17_000, 0, -3000);
            for (int i = 0; i < 25 * 60; i++) {
                s.weather.setFocus(0, 2, 0);
                s.weather.step();
            }
            s.weather.refreshAll();
            s.sun(25, 120).look(0, 2, 0, -1, 0.28, 0.2, Math.toRadians(75));
            shot(s, "storm-ground", 24, 1);
            // Wait for the storm's own flash (the weather alone), then show the frame it lights.
            float[] rgb = flash(s);
            Images.write("storm-lightning", rgb, W, H, exposure(s, rgb, 1) * 0.5);
            // At dusk, a flash in the anvil.
            s.sun(1, 120);
            s.frames(8, 1.0 / 60, false);
            rgb = flash(s);
            Images.write("storm-lightning-dusk", rgb, W, H, exposure(s, rgb, 1) * 0.3);
        }
    }

    /** Steps the weather until its storms flash, then renders the frame the flash begins in. */
    private static float[] flash(CloudScene s) {
        var events = s.weather.lightning();
        long seen = events.nextId();
        for (int i = 0; i < 3600 && events.nextId() == seen; i++) {
            s.weather.setFocus(0, 2, 0);
            s.weather.step();
        }
        double t = events.time((int) ((events.nextId() - 1) % events.capacity()));
        s.at(t - 0.5 / 60);
        return s.frames(1, 1.0 / 60);
    }

    @Test
    void flyingThrough() {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = new CloudScene(W, H, Quality.ULTRA, WeatherPreset.BROKEN, true)) {
            s.sun(35, 230);
            // In the stratocumulus layer (0.5-2 km), level flight, then above the tops.
            s.look(0, 1300, 0, -1, 0.02, -0.3, Math.toRadians(75));
            shot(s, "flying-through", 24, 1);
            s.look(400, 1300, 0, -1, 0.02, -0.3, Math.toRadians(75));
            shot(s, "flying-through-2", 24, 1);
            s.look(0, 3200, 0, -1, -0.25, -0.3, Math.toRadians(75));
            shot(s, "flying-above", 24, 1);
        }
    }

    /** A storm rolling in over half an hour of game time: 900 frames of 2 seconds each, for a 30-second film. */
    @Test
    void stormRollingIn() throws Exception {
        assumeThat(wanted()).isTrue();
        Path dir = Images.directory().resolve("film-storm");
        Files.createDirectories(dir);
        WeatherConfig config = WeatherConfig.forQuality(Quality.ULTRA);
        try (CloudScene s = new CloudScene(W, H, config, Quality.ULTRA, WeatherPreset.FAIR, true)) {
            s.flashLights(4);
            s.weather.setPreset(WeatherPreset.STORM, 1800);
            // Cells forming upwind, carried in by the westerlies.
            s.weather.spawnStorm(-26_000, 0, 6000);
            s.weather.spawnStorm(-30_000, 0, -2000);
            s.weather.spawnStorm(-34_000, 0, 9000);
            s.sun(32, 140);
            double exposure = 0;
            for (int f = 0; f < 900; f++) {
                // The camera turns slowly from west-south-west to west.
                double a = Math.toRadians(250 + 20.0 * f / 900);
                s.look(0, 2, 0, Math.sin(a), 0.22, -Math.cos(a), Math.toRadians(72));
                float[] rgb = null;
                for (int k = 0; k < 4; k++) {
                    rgb = s.frames(1, 0.5, k == 3);
                }
                double e = exposure(s, rgb, 1);
                exposure = f == 0 ? e : exposure * 0.9 + e * 0.1; // the eye adapts over a few frames
                Images.write(dir.resolve(String.format("f%04d", f)).toString(), rgb, W, H, exposure);
            }
            System.out.println("Film frames " + dir.toAbsolutePath());
        }
    }
}
