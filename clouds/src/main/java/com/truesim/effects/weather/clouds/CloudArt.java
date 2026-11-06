package com.truesim.effects.weather.clouds;

/**
 * Art overrides on top of the physical cloud model, off by default (the user's decision: physically fitted defaults,
 * overrides only when a game turns them on). While {@link #enabled} is false every accessor of the effective values
 * returns the physical value.
 *
 * @param enabled whether the overrides apply
 * @param densityScale multiplies the extinction
 * @param erosionScale multiplies the detail erosion
 * @param darkness multiplies the storm darkness channel's effect (absorption)
 * @param powder the Beer–powder term's strength (Schneider 2015), 0 = none; not physically based
 * @param silverLining multiplies the forward-scattering lobe's brightness, 1 = physical
 * @param coverageOffset added to every coverage value, [−1, 1]
 */
public record CloudArt(boolean enabled, double densityScale, double erosionScale, double darkness, double powder,
    double silverLining, double coverageOffset) {

    /** No overrides. */
    public static final CloudArt OFF = new CloudArt(false, 1, 1, 1, 0, 1, 0);

    public CloudArt {
        Checks.atLeast(densityScale, 0, "densityScale");
        Checks.atLeast(erosionScale, 0, "erosionScale");
        Checks.atLeast(darkness, 0, "darkness");
        Checks.range(powder, 0, 1, "powder");
        Checks.atLeast(silverLining, 0, "silverLining");
        Checks.range(coverageOffset, -1, 1, "coverageOffset");
    }

    /** The extinction to use for a physical {@code extinction}. */
    public double extinction(double extinction) {
        return enabled ? extinction * densityScale : extinction;
    }

    /** The erosion to use for a physical {@code erosion}, at most 1. */
    public double erosion(double erosion) {
        return enabled ? Math.min(1.0, erosion * erosionScale) : erosion;
    }

    /** The coverage to use for a weather map's {@code coverage}, in [0, 1]. */
    public double coverage(double coverage) {
        return enabled ? Math.clamp(coverage + coverageOffset, 0.0, 1.0) : coverage;
    }

    /** The powder strength in effect: 0 unless enabled. */
    public double powderInEffect() {
        return enabled ? powder : 0.0;
    }
}
