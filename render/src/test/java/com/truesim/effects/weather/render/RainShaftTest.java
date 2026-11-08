package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.StormSimulation;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherPreset;
import org.junit.jupiter.api.Test;

/**
 * A mature storm's rain shaft from 20 km in daylight, in effects-atmosphere's sky: below the cloud base it is a dark
 * curtain against the horizon sky beside it, as rain in a storm's shadow is. Before, beyond the clouds' Beer shadow map
 * (16 km from the camera) the rain was lit as under a clear sky (the column over it counted as empty): a light skirt
 * the colour of the horizon.
 */
class RainShaftTest {

    private static final int W = 320;
    private static final int H = 180;

    @Test
    void aStormsRainShaftIsDarkAgainstTheHorizonFromTwentyKilometres() {
        WeatherConfig config = WeatherConfig.forQuality(Quality.MEDIUM);
        try (CloudScene s = new CloudScene(W, H, config, Quality.MEDIUM, WeatherPreset.CLEAR, true, true)) {
            s.weather.spawnStorm(0, 0, -20_000);
            StormSimulation storms = s.weather.storms();
            int cell = StormShots.first(storms);
            CloudShots.run(s, 25, storms.position(cell, 0), 0, storms.position(cell, 2));
            double x = storms.position(cell, 0);
            double z = storms.position(cell, 2);
            double pitch = Math.toRadians(2);
            double fov = Math.toRadians(30);
            s.ev100(13).sun(40, 200).look(x, 2, z + 20_000, 0, Math.tan(pitch), -1, fov);
            float[] rgb = s.frames(24, 1.0 / 60);
            // Rows at 0.8 to 2 degrees up (under the base at 1.2 km: 3.4 degrees), columns within a degree of the core
            // (the shaft) and 15 to 20 degrees beside it (the clear horizon).
            double tanHalf = Math.tan(fov / 2);
            double aspect = (double) W / H;
            double shaft = 0;
            double clear = 0;
            int n = 0;
            int m = 0;
            for (double e = 0.8; e <= 2.0; e += 0.1) {
                int y = (int) Math.round(H / 2.0 - Math.tan(Math.toRadians(e) - pitch) / tanHalf * H / 2);
                for (double a = -1; a <= 1; a += 0.1) {
                    shaft += s.luminance(rgb, column(a, tanHalf, aspect), y);
                    n++;
                }
                for (double a = 15; a <= 20; a += 0.25) {
                    clear += s.luminance(rgb, column(a, tanHalf, aspect), y);
                    m++;
                }
            }
            shaft /= n;
            clear /= m;
            System.out.printf("Rain shaft from 20 km: %.4g scene units, the horizon beside it %.4g (%.2f of it)%n", shaft,
                clear, shaft / clear);
            Images.writeShown("rain-shaft-20km", s.shown(), W, H);
            assertThat(shaft).as("the rain shaft is darker than the horizon beside it").isLessThan(0.75 * clear);
        }
    }

    private static int column(double degrees, double tanHalf, double aspect) {
        return (int) Math.round(W / 2.0 + Math.tan(Math.toRadians(degrees)) / (tanHalf * aspect) * W / 2);
    }
}
