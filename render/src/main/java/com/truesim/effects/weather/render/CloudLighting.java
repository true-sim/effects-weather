package com.truesim.effects.weather.render;

import com.truesim.common.math.Maths;

/**
 * What the clouds need from the sky and the atmosphere: the sun (its direction, its illuminance outside the
 * atmosphere), the sky's light from above, and the air between the camera and the clouds. With effects-atmosphere
 * ({@link #tables()}: {@link AtmosphereLighting}) the clouds read its tables (Hillaire 2020): every sample's sunlight
 * through its transmittance table with the planet's shadow, and its aerial perspective volume in front of them. Without
 * ({@link SceneSky}) the GPU evaluates the transmittance and the aerial perspective from {@link #atmosphere()}'s
 * coefficients analytically (the Chapman function after Schüler 2012). Read once a frame, on the thread that updates
 * {@link WeatherGpu}; implementations allocate nothing there.
 */
public interface CloudLighting {

    /** The sun: {@code direction} towards it (unit, world axes, y up), {@code illuminance} outside the atmosphere (lux, RGB). */
    void sun(double[] direction, double[] illuminance);

    /** The sky's illuminance on a horizontal surface above the clouds (lux, RGB): the clouds' ambient light. */
    void sky(double[] illuminance);

    /** The sky's radiance near the horizon (cd/m², RGB): the aerial perspective's in-scattered light. */
    void horizon(double[] radiance);

    /** The atmosphere's media, for the analytic transmittance and aerial perspective. */
    Atmosphere atmosphere();

    /**
     * effects-atmosphere's atmosphere whose tables light the clouds and lie in front of them, or null (analytic). The
     * clouds then request its tables and aerial perspective volume ({@code SkyViewLut}) in their views.
     */
    default com.truesim.effects.atmosphere.render.Atmosphere tables() {
        return null;
    }

    /**
     * Scene units per lux and per cd/m² (1: physical units). The illuminances above are in scene units already; the
     * clouds' own physical values (the flashes' candela) are scaled by it, so a sky with an exposure baked into its
     * units (effects-atmosphere's EV100) and the clouds agree.
     */
    default double units() {
        return 1;
    }

    /**
     * An exponential atmosphere: Rayleigh scattering and Mie extinction at sea level (1/m) with their scale heights
     * (m), over a planet of {@code radius} (m).
     */
    record Atmosphere(double radius, double rayleighRed, double rayleighGreen, double rayleighBlue,
        double rayleighHeight, double mieScattering, double mieExtinction, double mieHeight) {

        /**
         * The Earth's, as Hillaire 2020 gives it (after Bruneton and Neyret 2008): Rayleigh (5.802, 13.558, 33.1)·10⁻⁶
         * per metre over 8 km, Mie 3.996·10⁻⁶ scattering and 4.40·10⁻⁶ extinction per metre over 1.2 km, a planet of
         * 6,360 km.
         */
        public static final Atmosphere EARTH = new Atmosphere(6.36e6, 5.802e-6, 13.558e-6, 33.1e-6, 8000, 3.996e-6,
            4.40e-6, 1200);

        /** The transmittance of channel {@code c} (0 red, 1 green, 2 blue) from altitude {@code h} towards a direction of cosine {@code mu} from the zenith. */
        public double transmittance(int c, double h, double mu) {
            double rayleigh = c == 0 ? rayleighRed : c == 1 ? rayleighGreen : rayleighBlue;
            double depth = rayleigh * rayleighHeight * chapman(radius, rayleighHeight, h, mu)
                + mieExtinction * mieHeight * chapman(radius, mieHeight, h, mu);
            return Maths.exp(-Math.min(depth, 80));
        }

        /** Schüler 2012's Chapman function times the density at height {@code h}, as clouds.slang has it. */
        static double chapman(double radius, double scaleHeight, double h, double mu) {
            double x = radius / scaleHeight;
            double hh = Math.max(h, 0) / scaleHeight;
            double c = Math.sqrt(x + hh);
            if (mu >= 0) {
                return c / (c * mu + 1) * Maths.exp(-hh);
            }
            double x0 = Math.sqrt(Math.max(1 - mu * mu, 0)) * (x + hh);
            double c0 = Math.sqrt(x0);
            return 2 * c0 * Maths.exp(x - x0) - c / (1 - c * mu) * Maths.exp(-hh);
        }
    }
}
