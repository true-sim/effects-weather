package com.truesim.effects.weather.clouds;

import java.util.Arrays;
import java.util.Objects;

/**
 * The profiles of all cloud types plus the art overrides: what a cloud renderer of any tier needs to turn a weather
 * map's type channel into a shape. Immutable; {@link #with} makes a changed copy.
 *
 * <p>The physical defaults are typical values, to be replaced by values fitted to the path tracer (design §10.3):
 * layer heights from the WMO International Cloud Atlas' étages (low below 2 km, middle 2–7 km, high 5–13 km in
 * temperate regions); liquid water contents (0.1–0.5 g/m³ for stratiform and fair-weather cumulus, 1–2 g/m³ in
 * congestus and cumulonimbus cores) and effective droplet diameters (16–30 µm; cirrus ice about 60 µm) from Pruppacher
 * and Klett, <i>Microphysics of Clouds and Precipitation</i> (1997) and Miles, Verlinde and Clothiaux, J. Atmos. Sci.
 * 57 (2000). Extinction follows from them as σ = 3·LWC / (2·ρ·r).
 */
public final class CloudTypes {

    private final CloudProfile[] profiles;
    private final CloudArt art;

    private CloudTypes(CloudProfile[] profiles, CloudArt art) {
        this.profiles = profiles;
        this.art = art;
    }

    private static CloudProfile p(double base, double top, double baseRamp, double topRamp, double gradient,
        double anvil, double anvilStart, double shear, double erosion, double detail, double lwcGramsPerCubicMetre,
        double diameter, boolean ice) {
        double density = ice ? 917.0 : 1000.0;
        double extinction = 3 * lwcGramsPerCubicMetre * 1e-3 / (2 * density * diameter * 0.5e-6);
        return new CloudProfile(base, top, baseRamp, topRamp, gradient, anvil, anvilStart, shear, erosion, detail,
            extinction, diameter, 0.0, ice);
    }

    /** The physical defaults, no art overrides. */
    public static CloudTypes physical() {
        CloudProfile[] p = new CloudProfile[CloudType.count()];
        p[CloudType.STRATUS.ordinal()] = p(100, 600, 0.2, 0.3, -0.5, 0, 0.5, 0, 0.2, 400, 0.25, 16, false);
        p[CloudType.STRATOCUMULUS.ordinal()] = p(500, 2000, 0.1, 0.4, -0.2, 0, 0.5, 0.05, 0.4, 300, 0.3, 18, false);
        p[CloudType.CUMULUS.ordinal()] = p(800, 2500, 0.05, 0.7, 0.3, 0, 0.5, 0.1, 0.5, 200, 0.5, 20, false);
        p[CloudType.TOWERING_CUMULUS.ordinal()] = p(800, 6000, 0.03, 0.5, 0.2, 0, 0.5, 0.15, 0.6, 250, 1.0, 24, false);
        p[CloudType.CUMULONIMBUS.ordinal()] = p(800, 12000, 0.03, 0.15, 0.0, 0.6, 0.75, 0.3, 0.5, 400, 1.5, 30, false);
        p[CloudType.ALTOCUMULUS.ordinal()] = p(2500, 4500, 0.2, 0.4, 0.0, 0, 0.5, 0.05, 0.7, 150, 0.2, 16, false);
        p[CloudType.ALTOSTRATUS.ordinal()] = p(2000, 6000, 0.3, 0.3, 0.0, 0, 0.5, 0, 0.3, 600, 0.1, 20, false);
        p[CloudType.NIMBOSTRATUS.ordinal()] = p(500, 4000, 0.1, 0.3, -0.4, 0, 0.5, 0, 0.2, 500, 0.4, 24, false);
        p[CloudType.CIRRUS.ordinal()] = p(8000, 10000, 0.3, 0.3, 0.0, 0, 0.5, 0.4, 0.9, 800, 0.02, 60, true);
        return new CloudTypes(p, CloudArt.OFF);
    }

    /** The profile of {@code type}. */
    public CloudProfile profile(CloudType type) {
        return profiles[type.ordinal()];
    }

    /** The art overrides. */
    public CloudArt art() {
        return art;
    }

    /** A copy with {@code type}'s profile replaced. */
    public CloudTypes with(CloudType type, CloudProfile profile) {
        CloudProfile[] copy = profiles.clone();
        copy[type.ordinal()] = Objects.requireNonNull(profile, "profile");
        return new CloudTypes(copy, art);
    }

    /** A copy with other art overrides. */
    public CloudTypes with(CloudArt newArt) {
        return new CloudTypes(profiles, Objects.requireNonNull(newArt, "art"));
    }

    /**
     * Bakes the profile atlas a renderer uploads: one row per type (in {@link CloudType} order), {@code heights}
     * samples per row at h = (i + 0.5) / heights, RGBA float: R density, G anvil, B erosion (art applied), A the droplet
     * diameter / 100 µm. {@code out} holds 4 · types · heights floats.
     */
    public void bakeProfileAtlas(int heights, float[] out) {
        for (int t = 0; t < profiles.length; t++) {
            CloudProfile p = profiles[t];
            for (int i = 0; i < heights; i++) {
                double h = (i + 0.5) / heights;
                int o = 4 * (t * heights + i);
                out[o] = (float) p.density(h);
                out[o + 1] = (float) p.anvil(h);
                out[o + 2] = (float) art.erosion(p.erosion());
                out[o + 3] = (float) (p.dropletDiameter() / 100.0);
            }
        }
    }

    /**
     * Bakes the per-type constants: base and top altitude (m), extinction (1/m, art applied), shear; 4 floats per
     * type.
     */
    public void bakeLayers(float[] out) {
        for (int t = 0; t < profiles.length; t++) {
            CloudProfile p = profiles[t];
            out[4 * t] = (float) p.base();
            out[4 * t + 1] = (float) p.top();
            out[4 * t + 2] = (float) art.extinction(p.extinction());
            out[4 * t + 3] = (float) p.shear();
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof CloudTypes other && Arrays.equals(profiles, other.profiles) && art.equals(other.art);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(profiles) + art.hashCode();
    }
}
