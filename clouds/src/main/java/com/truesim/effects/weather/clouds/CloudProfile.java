package com.truesim.effects.weather.clouds;

import com.truesim.common.math.Maths;

/**
 * One cloud type's physical description: where its layer is, how its density varies with height, and the microphysics
 * the lighting needs. The vertical profile is defined exactly here (the CPU reference); the GPU's profile atlas is
 * baked from {@link #density} and {@link #anvil}, so both agree.
 *
 * <p>With h the height in the layer normalised to [0, 1] (0 at {@code base}, 1 at {@code top}) and
 * S(x) = smoothstep(0, 1, x) = x²(3 − 2x) on clamped x:
 * <pre>
 * density(h) = S(h / baseRamp) · S((1 − h) / topRamp) · clamp(1 + gradient · (h − 0.5), 0, 2)
 * anvil(h)   = anvilSpread · S((h − anvilStart) / (1 − anvilStart))        (0 when anvilSpread = 0)
 * </pre>
 * {@code density} scales the coverage-remapped base shape; {@code anvil} widens the coverage near the top (the
 * renderer adds it to the coverage and shears it along the wind by {@code shear}).
 *
 * @param base the layer's base above the ground, metres
 * @param top the layer's top, metres (a weather map's top-height channel can override it per place)
 * @param baseRamp the fraction of the layer over which density rises from the base: small for the flat bases of cumulus
 * @param topRamp the fraction over which it falls to the top: large for rounded tops
 * @param gradient the linear change of density with height: negative for denser bases (stratus, nimbostratus)
 * @param anvilSpread how much the coverage widens at the top (cumulonimbus anvils), 0 for none
 * @param anvilStart where in the layer the anvil begins
 * @param shear how far the upper part leans downwind, as a fraction of the layer's height
 * @param erosion the detail noise's erosion: 0 billowy, 1 wispy
 * @param detailScale the detail noise's period in metres
 * @param extinction the extinction coefficient at density 1, per metre (liquid water content and droplet size give it:
 *     σ ≈ 3·LWC / (2·ρ_w·r_e))
 * @param dropletDiameter the effective droplet diameter in micrometres, for the approximate Mie phase function
 *     (Jendersie and d'Eon 2023); ice crystals of cirrus use {@code ice}
 * @param absorption the single-scattering co-albedo (1 − albedo): storm interiors darken by soot-free absorption only
 *     slightly; physical values are near 0
 * @param ice whether it is made of ice crystals (phase: Henyey–Greenstein g ≈ 0.75, halos optional)
 */
public record CloudProfile(double base, double top, double baseRamp, double topRamp, double gradient,
    double anvilSpread, double anvilStart, double shear, double erosion, double detailScale, double extinction,
    double dropletDiameter, double absorption, boolean ice) {

    public CloudProfile {
        Checks.above(top, base, "top");
        Checks.range(baseRamp, 1e-6, 1, "baseRamp");
        Checks.range(topRamp, 1e-6, 1, "topRamp");
        Checks.range(gradient, -2, 2, "gradient");
        Checks.atLeast(anvilSpread, 0, "anvilSpread");
        Checks.range(anvilStart, 0, 0.99, "anvilStart");
        Checks.range(shear, -10, 10, "shear");
        Checks.range(erosion, 0, 1, "erosion");
        Checks.above(detailScale, 0, "detailScale");
        Checks.atLeast(extinction, 0, "extinction");
        Checks.above(dropletDiameter, 0, "dropletDiameter");
        Checks.range(absorption, 0, 0.99, "absorption");
    }

    /** The layer's thickness in metres. */
    public double thickness() {
        return top - base;
    }

    /** The normalised height in the layer of altitude {@code altitude} (metres), clamped to [0, 1]. */
    public double heightFraction(double altitude) {
        return Math.clamp((altitude - base) / (top - base), 0.0, 1.0);
    }

    /** The vertical density profile at normalised height {@code h}, as the class documentation defines it. */
    public double density(double h) {
        if (h < 0 || h > 1) {
            return 0.0;
        }
        return smooth(h / baseRamp) * smooth((1 - h) / topRamp) * Math.clamp(1 + gradient * (h - 0.5), 0.0, 2.0);
    }

    /** The anvil's coverage widening at normalised height {@code h}. */
    public double anvil(double h) {
        if (anvilSpread == 0) {
            return 0.0;
        }
        return anvilSpread * smooth((h - anvilStart) / (1 - anvilStart));
    }

    /** The extinction coefficient (1/m) of liquid water content {@code lwc} (kg/m³) for this droplet size. */
    public double extinctionOfWaterContent(double lwc) {
        double radius = dropletDiameter * 0.5e-6;
        return 3 * lwc / (2 * 1000.0 * radius);
    }

    /** S(x): the smoothstep of x clamped to [0, 1]. */
    static double smooth(double x) {
        double t = Maths.clamp01(x);
        return t * t * (3 - 2 * t);
    }

    public CloudProfile withLayer(double newBase, double newTop) {
        return new CloudProfile(newBase, newTop, baseRamp, topRamp, gradient, anvilSpread, anvilStart, shear, erosion,
            detailScale, extinction, dropletDiameter, absorption, ice);
    }

    public CloudProfile withExtinction(double newExtinction) {
        return new CloudProfile(base, top, baseRamp, topRamp, gradient, anvilSpread, anvilStart, shear, erosion,
            detailScale, newExtinction, dropletDiameter, absorption, ice);
    }
}
