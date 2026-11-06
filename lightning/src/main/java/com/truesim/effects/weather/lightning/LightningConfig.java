package com.truesim.effects.weather.lightning;

import com.truesim.effects.atmosphere.Quality;
import java.util.Objects;

/**
 * Lightning's tunables: the tier, the statistics flashes are drawn from, the bolt geometry and thunder.
 *
 * <p>Statistics (defaults): about three intracloud flashes per cloud-to-ground flash and 10 % of ground flashes
 * positive (Rakov and Uman, <i>Lightning: Physics and Effects</i>, 2003, ch. 1, 4, 5); peak currents log-normal with a
 * median of 30 kA for negative first strokes, σ(log₁₀) 0.265, and 35 kA for positive ones (Berger, Anderson and
 * Kröninger, Electra 41, 1975); 3–5 strokes per negative flash, 60 ms between strokes (geometric mean), continuing
 * current in about 40 % of flashes (Rakov and Uman).
 *
 * @param tier what is produced
 * @param intracloudRatio intracloud (and cloud-to-cloud) flashes per cloud-to-ground flash
 * @param positiveFraction the fraction of cloud-to-ground flashes that are positive
 * @param medianPeakCurrentKa the median peak current of negative first strokes, kA
 * @param medianPositivePeakCurrentKa the median peak current of positive strokes, kA
 * @param peakCurrentSigmaLn the log-normal's σ in natural log units (0.265 · ln 10 ≈ 0.61)
 * @param meanStrokes the mean number of strokes of a negative ground flash (positive ones have one)
 * @param interstrokeMs the mean time between strokes, ms
 * @param strokeDecayMs the luminosity decay time of a stroke as drawn, ms
 * @param continuingCurrentChance the chance a stroke has continuing current (a steady glow afterwards)
 * @param continuingCurrentMs how long continuing current lasts, ms
 * @param continuingCurrentLevel its luminosity relative to the stroke's peak
 * @param intracloudPulses the luminous pulses of an intracloud flash
 * @param intracloudDurationMs how long an intracloud flash flickers, ms
 * @param boltLevels midpoint-displacement levels of a bolt's main channel (2^levels segments)
 * @param boltDisplacement the largest sideways displacement as a fraction of a segment's length
 * @param branchChance the chance per new point that a branch starts there
 * @param branchLength a branch's length relative to the parent segment's remaining length
 * @param branchBrightness brightness per branch depth relative to its parent
 * @param maxBoltPoints the bolt buffers' capacity
 * @param airTemperatureCelsius the air temperature for the speed of sound
 * @param thunderRangeMetres beyond this thunder is not audible
 */
public record LightningConfig(Tier tier, double intracloudRatio, double positiveFraction, double medianPeakCurrentKa,
    double medianPositivePeakCurrentKa, double peakCurrentSigmaLn, double meanStrokes, double interstrokeMs,
    double strokeDecayMs, double continuingCurrentChance, double continuingCurrentMs, double continuingCurrentLevel,
    int intracloudPulses, double intracloudDurationMs, int boltLevels, double boltDisplacement, double branchChance,
    double branchLength, double branchBrightness, int maxBoltPoints, double airTemperatureCelsius,
    double thunderRangeMetres) {

    /** The lightning tiers. */
    public enum Tier {
        /** No lightning events. */
        OFF,
        /** Events for in-cloud flashes and the scene light; no bolt geometry drawn. */
        FLASHES,
        /** Everything: flashes, visible branching bolts, ground strikes. */
        FULL
    }

    public LightningConfig {
        Objects.requireNonNull(tier, "tier");
        Checks.atLeast(intracloudRatio, 0, "intracloudRatio");
        Checks.range(positiveFraction, 0, 1, "positiveFraction");
        Checks.above(medianPeakCurrentKa, 0, "medianPeakCurrentKa");
        Checks.above(medianPositivePeakCurrentKa, 0, "medianPositivePeakCurrentKa");
        Checks.atLeast(peakCurrentSigmaLn, 0, "peakCurrentSigmaLn");
        Checks.atLeast(meanStrokes, 1, "meanStrokes");
        Checks.above(interstrokeMs, 0, "interstrokeMs");
        Checks.above(strokeDecayMs, 0, "strokeDecayMs");
        Checks.range(continuingCurrentChance, 0, 1, "continuingCurrentChance");
        Checks.atLeast(continuingCurrentMs, 0, "continuingCurrentMs");
        Checks.range(continuingCurrentLevel, 0, 1, "continuingCurrentLevel");
        Checks.range(intracloudPulses, 1, 64, "intracloudPulses");
        Checks.above(intracloudDurationMs, 0, "intracloudDurationMs");
        Checks.range(boltLevels, 1, 12, "boltLevels");
        Checks.range(boltDisplacement, 0, 1, "boltDisplacement");
        Checks.range(branchChance, 0, 1, "branchChance");
        Checks.range(branchLength, 0, 1, "branchLength");
        Checks.range(branchBrightness, 0, 1, "branchBrightness");
        Checks.range(maxBoltPoints, 2, 1 << 20, "maxBoltPoints");
        Checks.range(airTemperatureCelsius, -100, 100, "airTemperatureCelsius");
        Checks.atLeast(thunderRangeMetres, 0, "thunderRangeMetres");
    }

    /** The FULL tier with the statistics above. */
    public static LightningConfig defaults() {
        return new LightningConfig(Tier.FULL, 3.0, 0.1, 30, 35, 0.61, 3.5, 60, 2.0, 0.4, 120, 0.1, 5, 300, 7, 0.18,
            0.25, 0.45, 0.5, 2048, 15, 25_000);
    }

    /** LOW: flashes only; MEDIUM to ULTRA: everything, ULTRA with finer bolts. */
    public static LightningConfig forQuality(Quality quality) {
        LightningConfig d = defaults();
        return switch (quality) {
            case LOW -> d.withTier(Tier.FLASHES);
            case MEDIUM -> d.withBoltLevels(6);
            case HIGH -> d;
            case ULTRA -> d.withBoltLevels(8);
        };
    }

    public LightningConfig withTier(Tier newTier) {
        return new LightningConfig(newTier, intracloudRatio, positiveFraction, medianPeakCurrentKa,
            medianPositivePeakCurrentKa, peakCurrentSigmaLn, meanStrokes, interstrokeMs, strokeDecayMs,
            continuingCurrentChance, continuingCurrentMs, continuingCurrentLevel, intracloudPulses, intracloudDurationMs,
            boltLevels, boltDisplacement, branchChance, branchLength, branchBrightness, maxBoltPoints,
            airTemperatureCelsius, thunderRangeMetres);
    }

    public LightningConfig withBoltLevels(int levels) {
        return new LightningConfig(tier, intracloudRatio, positiveFraction, medianPeakCurrentKa,
            medianPositivePeakCurrentKa, peakCurrentSigmaLn, meanStrokes, interstrokeMs, strokeDecayMs,
            continuingCurrentChance, continuingCurrentMs, continuingCurrentLevel, intracloudPulses, intracloudDurationMs,
            levels, boltDisplacement, branchChance, branchLength, branchBrightness, maxBoltPoints, airTemperatureCelsius,
            thunderRangeMetres);
    }

    /** Whether events are produced at all. */
    public boolean enabled() {
        return tier != Tier.OFF;
    }

    /** The speed of sound in m/s at {@link #airTemperatureCelsius}: 331.3·√(1 + T/273.15). */
    public double speedOfSound() {
        return Thunder.speedOfSound(airTemperatureCelsius);
    }
}
