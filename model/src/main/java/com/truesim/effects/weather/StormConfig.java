package com.truesim.effects.weather;

import com.truesim.effects.atmosphere.Quality;

/**
 * The storm life cycle's tunables. Each cell draws its stage durations, height, size, rain and flash rate from these
 * ranges when it forms. The defaults follow the single-cell thunderstorm of the Thunderstorm Project (Byers and
 * Braham, <i>The Thunderstorm</i>, 1949): a cumulus stage of 10–20 minutes, a mature stage of 15–30 minutes with the
 * heaviest rain and most lightning, a dissipating stage of 15–40 minutes; tops of 8–14 km, cores of 2–6 km radius;
 * 1–8 flashes per minute (ordinary storms; severe ones exceed 10). Multicell storms grow from daughter cells on the
 * mature cell's flank.
 *
 * @param growthMin cumulus stage, seconds
 * @param growthMax cumulus stage, seconds
 * @param matureMin mature stage, seconds
 * @param matureMax mature stage, seconds
 * @param decayMin dissipating stage, seconds
 * @param decayMax dissipating stage, seconds
 * @param baseAltitude cloud base, metres
 * @param topMin the highest top, metres
 * @param topMax the highest top, metres
 * @param coreRadiusMin metres
 * @param coreRadiusMax metres
 * @param anvilFactor the anvil's full radius over the core's
 * @param precipitationMin the mature stage's precipitation [0, 1] of the maximum rate
 * @param precipitationMax the mature stage's precipitation
 * @param flashRateMin flashes per minute at maturity
 * @param flashRateMax flashes per minute at maturity
 * @param daughterRate daughter cells per minute of maturity (0.06: about one per cell over a 15–30 minute mature
 *     stage, so a multicell storm keeps a handful of cells instead of multiplying)
 * @param spawnRadius cells form and live within this distance of the focus, metres (they are dropped at 1.5×)
 * @param maxCells the capacity
 * @param rateScale multiplies the preset's storm rate
 */
public record StormConfig(double growthMin, double growthMax, double matureMin, double matureMax, double decayMin,
    double decayMax, double baseAltitude, double topMin, double topMax, double coreRadiusMin, double coreRadiusMax,
    double anvilFactor, double precipitationMin, double precipitationMax, double flashRateMin, double flashRateMax,
    double daughterRate, double spawnRadius, int maxCells, double rateScale) {

    public StormConfig {
        Checks.above(growthMin, 0, "growthMin");
        Checks.atLeast(growthMax, growthMin, "growthMax");
        Checks.above(matureMin, 0, "matureMin");
        Checks.atLeast(matureMax, matureMin, "matureMax");
        Checks.above(decayMin, 0, "decayMin");
        Checks.atLeast(decayMax, decayMin, "decayMax");
        Checks.atLeast(baseAltitude, 0, "baseAltitude");
        Checks.above(topMin, baseAltitude, "topMin");
        Checks.atLeast(topMax, topMin, "topMax");
        Checks.above(coreRadiusMin, 0, "coreRadiusMin");
        Checks.atLeast(coreRadiusMax, coreRadiusMin, "coreRadiusMax");
        Checks.atLeast(anvilFactor, 1, "anvilFactor");
        Checks.range(precipitationMin, 0, 1, "precipitationMin");
        Checks.range(precipitationMax, precipitationMin, 1, "precipitationMax");
        Checks.atLeast(flashRateMin, 0, "flashRateMin");
        Checks.atLeast(flashRateMax, flashRateMin, "flashRateMax");
        Checks.atLeast(daughterRate, 0, "daughterRate");
        Checks.above(spawnRadius, 0, "spawnRadius");
        Checks.range(maxCells, 0, 1 << 16, "maxCells");
        Checks.atLeast(rateScale, 0, "rateScale");
    }

    /** The ranges above, 64 cells within 60 km. */
    public static StormConfig defaults() {
        return new StormConfig(600, 1200, 900, 1800, 900, 2400, 1200, 8000, 14000, 2000, 6000, 3, 0.4, 1.0, 1, 8, 0.06,
            60_000, 64, 1);
    }

    /** Fewer cells at lower quality (the life cycle is the same). */
    public static StormConfig forQuality(Quality quality) {
        StormConfig d = defaults();
        int cells = switch (quality) {
            case LOW -> 8;
            case MEDIUM -> 24;
            case HIGH -> 64;
            case ULTRA -> 128;
        };
        return d.withMaxCells(cells);
    }

    public StormConfig withMaxCells(int cells) {
        return new StormConfig(growthMin, growthMax, matureMin, matureMax, decayMin, decayMax, baseAltitude, topMin,
            topMax, coreRadiusMin, coreRadiusMax, anvilFactor, precipitationMin, precipitationMax, flashRateMin,
            flashRateMax, daughterRate, spawnRadius, cells, rateScale);
    }

    public StormConfig withDaughterRate(double perMinute) {
        return new StormConfig(growthMin, growthMax, matureMin, matureMax, decayMin, decayMax, baseAltitude, topMin,
            topMax, coreRadiusMin, coreRadiusMax, anvilFactor, precipitationMin, precipitationMax, flashRateMin,
            flashRateMax, perMinute, spawnRadius, maxCells, rateScale);
    }

    public StormConfig withRateScale(double scale) {
        return new StormConfig(growthMin, growthMax, matureMin, matureMax, decayMin, decayMax, baseAltitude, topMin,
            topMax, coreRadiusMin, coreRadiusMax, anvilFactor, precipitationMin, precipitationMax, flashRateMin,
            flashRateMax, daughterRate, spawnRadius, maxCells, scale);
    }
}
