package com.truesim.effects.weather.render;

import com.truesim.common.math.Maths;
import com.truesim.effects.atmosphere.render.AtmosphereConfig;
import java.util.Objects;

/**
 * The clouds lit by effects-atmosphere's atmosphere (Hillaire 2020): its sun (direction, illuminance outside the
 * atmosphere, scene units), its media (the clouds' per-sample transmittance and aerial perspective evaluate the same
 * Rayleigh and Mie coefficients analytically), its exposure ({@link com.truesim.effects.atmosphere.render.Atmosphere#sceneUnits}), and the sky's light: its
 * mean radiance where its volumetric fog has read it back ({@link com.truesim.effects.atmosphere.render.Atmosphere#skyAmbient}), else the light the
 * atmosphere scatters out of the sun's beam (its transmittance from the ground), as {@link SceneSky} estimates it. The
 * sky itself, the aerial perspective on surfaces and the environment's light come from the atmosphere's own
 * techniques ({@code AtmosphereSky}, {@code AerialPerspective}, {@code SkyCapture}). Reads allocate nothing.
 */
public final class AtmosphereLighting implements CloudLighting {

    /** The clear sky's higher orders and aerosols, as {@link SceneSky} adds them to the single scattering. */
    private static final double DIFFUSE_FLOOR = 0.12;

    private final com.truesim.effects.atmosphere.render.Atmosphere atmosphere;
    private final double[] direction = new double[3];
    private final double[] top = new double[3];
    private final double[] transmittance = new double[3];
    private final double[] ambient = new double[3];
    private Atmosphere media;
    private AtmosphereConfig mediaOf;

    /** The clouds under {@code atmosphere}. */
    public AtmosphereLighting(com.truesim.effects.atmosphere.render.Atmosphere atmosphere) {
        this.atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
    }

    @Override
    public void sun(double[] towards, double[] illuminance) {
        atmosphere.sunDirection(towards);
        atmosphere.lightIlluminance(0, illuminance);
    }

    @Override
    public void sky(double[] illuminance) {
        atmosphere.skyAmbient(ambient);
        if (ambient[0] + ambient[1] + ambient[2] > 0) {
            // The mean radiance over the sphere, lit ground included: the upper half's irradiance about pi times it.
            for (int c = 0; c < 3; c++) {
                illuminance[c] = Math.PI * ambient[c];
            }
            return;
        }
        atmosphere.sunDirection(direction);
        atmosphere.lightIlluminance(0, top);
        atmosphere.transmittance(0, 0, transmittance);
        double mu = Math.max(direction[1], 0);
        for (int c = 0; c < 3; c++) {
            double t = transmittance[c];
            illuminance[c] = top[c] * mu * (0.5 * (1 - t) + DIFFUSE_FLOOR * t);
        }
    }

    @Override
    public void horizon(double[] radiance) {
        atmosphere.skyAmbient(ambient);
        if (ambient[0] + ambient[1] + ambient[2] > 0) {
            System.arraycopy(ambient, 0, radiance, 0, 3);
            return;
        }
        atmosphere.lightIlluminance(0, top);
        atmosphere.transmittance(0, 0, transmittance);
        Atmosphere a = atmosphere();
        for (int c = 0; c < 3; c++) {
            double beta = (c == 0 ? a.rayleighRed() : c == 1 ? a.rayleighGreen() : a.rayleighBlue())
                + a.mieScattering();
            radiance[c] = top[c] * transmittance[c] * (1 - Maths.exp(-beta * 50_000)) / (4 * Math.PI);
        }
    }

    @Override
    @com.truesim.annotations.AllowAllocation("when the atmosphere's media change")
    public Atmosphere atmosphere() {
        AtmosphereConfig config = atmosphere.config();
        if (config != mediaOf) {
            // Per km and km in the atmosphere's settings; per metre and metres here.
            media = new Atmosphere(config.planetRadius() * 1000.0, config.rayleighRed() * 1e-3,
                config.rayleighGreen() * 1e-3, config.rayleighBlue() * 1e-3, config.rayleighScaleHeight() * 1000.0,
                config.mieScattering() * 1e-3, (config.mieScattering() + config.mieAbsorption()) * 1e-3,
                config.mieScaleHeight() * 1000.0);
            mediaOf = config;
        }
        return media;
    }

    @Override
    public double units() {
        return atmosphere.sceneUnits();
    }
}
