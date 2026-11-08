package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.weather.StormSimulation;

/**
 * A storm cell of {@link StormSimulation} as the clouds draw it: a convective cloud of towers on one flat base, each a
 * function of the cell's age only, continuous in it (the clouds never step from one frame to the next). From the
 * meteorology of an ordinary multicell thunderstorm (Byers and Braham 1949; Browning et al. 1976; Houze,
 * <i>Cloud Dynamics</i>, 2014, ch. 8; the WMO International Cloud Atlas' species of cumulus and cumulonimbus):
 * <ul>
 * <li>the <b>main updraft's tower</b> over the core, its top the simulation's: 0.3 of the core's radius while it is a
 *     cumulus (humilis, a lens flatter than wide; mediocris), 1.25 of it once it towers (congestus; a mature
 *     cumulonimbus's body is 5 to 15 km across, about as wide as it is tall, not a slender column), its top widening
 *     into the anvil as that spreads (incus);</li>
 * <li>the <b>flanking line</b>: three towers stepping down and away on the right flank of the motion (where new cells
 *     grow in the northern hemisphere), each starting later and staying lower, still growing while the main tower is
 *     mature (towers at different stages);</li>
 * <li>two early <b>cumuli</b> beside the core that the main tower absorbs as it grows;</li>
 * <li><b>glaciation</b>: once the top passes the glaciation level (about -35 C) the upper parts turn to ice over the
 *     next 1.5 km of growth (calvus: the cauliflower softens), fibrous as the anvil spreads (capillatus); the ice
 *     outlives the liquid towers;</li>
 * <li>the <b>tropopause</b> (its maximum top) flattening every top but the <b>overshooting top</b>'s, a dome over the
 *     updraft early in the mature stage, rising and sinking back over about twenty minutes;</li>
 * <li><b>dissipation</b>: the liquid towers erode away as the cell rains out, the ice later (the anvil lingers,
 *     thinning: the weather maps' anvil channel);</li>
 * <li>far from the focus, where the simulation drops cells (1.5 times its spawn radius), they fade out first instead
 *     of vanishing.</li>
 * </ul>
 * Where the cell's towers stand is fixed by its id's hash, so it is the same on every machine and frame.
 */
final class StormTowers {

    /** Towers per cell (clouds.slang's MAX_TOWERS): the main one, three of the flanking line, two early cumuli. */
    static final int TOWERS = 6;

    /** The flanking line's direction: radians to the right of the motion, and the spread around it (100, 40 degrees). */
    private static final double FLANK = 100 * Maths.PI / 180;
    private static final double FLANK_SPREAD = 40 * Maths.PI / 180;

    // Per cell, written by update(): x, z from the anchor, radius, top (metres) of each tower.
    final float[] towers;
    final float[] base;
    final float[] reach;
    final float[] glaciation;
    final float[] fibres;
    final float[] water;
    final float[] overshoot;
    final float[] anvil;
    final float[] anvilTop;
    final float[] noise;
    final float[] ceiling;
    final float[] flare;
    final int[] count;
    /** The highest point of any cell: a tower's top, an overshooting top, an anvil's top. */
    float highest;

    @AllowAllocation("made once")
    StormTowers(int capacity) {
        towers = new float[4 * TOWERS * capacity];
        base = new float[capacity];
        reach = new float[capacity];
        glaciation = new float[capacity];
        fibres = new float[capacity];
        water = new float[capacity];
        overshoot = new float[capacity];
        anvil = new float[capacity];
        anvilTop = new float[capacity];
        noise = new float[3 * capacity];
        ceiling = new float[capacity];
        flare = new float[capacity];
        count = new int[capacity];
    }

    /** A hash of (id, k) in [0, 1) (Wellons' lowbias32 on both). */
    static double hash(int id, int k) {
        int x = id * 0x9e3779b9 + k * 0x85ebca6b;
        x ^= x >>> 16;
        x *= 0x7feb352d;
        x ^= x >>> 15;
        x *= 0x846ca68b;
        x ^= x >>> 16;
        return (x >>> 8) * (1.0 / (1 << 24));
    }

    private static double smooth(double x) {
        double t = Math.clamp(x, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    /**
     * Cell {@code s} of {@code storms} into slot {@code c}, from the anchor ({@code anchorX}, {@code anchorZ}), at the
     * simulation's age plus {@code ahead} seconds (the frame's time between steps), its flank found from the steering
     * wind's direction ({@code windX}, {@code windZ}: x east, z south, unit or zero), {@code fade} [0, 1] fading it out
     * (far away), the glaciation level {@code glaciationAltitude}, overshooting tops of {@code overshootHeight} metres.
     */
    void update(int c, StormSimulation storms, int s, double anchorX, double anchorZ, double ahead, double windX,
        double windZ, double fade, double glaciationAltitude, double overshootHeight, double lean, double rise) {
        int id = storms.id(s);
        double age = storms.age(s) + ahead;
        double growth = storms.growthEnd(s);
        double matureEnd = storms.matureEnd(s);
        double end = storms.end(s);
        double baseAltitude = storms.baseAltitude();
        double maxTop = storms.maxTop(s);
        double core = storms.coreRadius(s);
        double cx = storms.position(s, 0) - anchorX;
        double cz = storms.position(s, 2) - anchorZ;
        double g = Math.clamp(age / growth, 0, 1);
        double dissipating = Math.clamp((age - matureEnd) / (end - matureEnd), 0, 1);
        base[c] = (float) baseAltitude;
        // The flank: the motion turned 100 degrees to the right (x east, z south: north is -z), within 20 either way.
        double flankX;
        double flankZ;
        if (windX * windX + windZ * windZ > 1e-6) {
            double a = Maths.atan2(-windZ, windX) - FLANK + (hash(id, 1) - 0.5) * FLANK_SPREAD;
            flankX = Maths.cos(a);
            flankZ = -Maths.sin(a);
        } else {
            double a = hash(id, 1) * Maths.TAU;
            flankX = Maths.cos(a);
            flankZ = Maths.sin(a);
        }
        int o = 4 * TOWERS * c;
        double top = storms.topAt(s, age);
        // The main tower: wider than the core once it towers (a mature cumulonimbus's body is as wide as it is tall),
        // a third as wide while it is a cumulus.
        put(o, cx, cz, core * (0.3 + 0.95 * smooth(g / 0.7)), top);
        double reachSq = core;
        // The flanking line: three towers stepping down and away, each starting later than the one before.
        for (int k = 1; k <= 3; k++) {
            double lag = growth * (0.3 + 0.28 * k);
            double fraction = 0.82 - 0.14 * k + 0.1 * hash(id, 10 + k);
            double towerTop = baseAltitude + (storms.topAt(s, age - lag) - baseAltitude) * fraction;
            double distance = core * (0.85 + 0.75 * k) * (0.9 + 0.2 * hash(id, 20 + k));
            double side = (hash(id, 30 + k) - 0.5) * 0.5 * core;
            double x = cx + flankX * distance - flankZ * side;
            double z = cz + flankZ * distance + flankX * side;
            double grown = Math.clamp((age - lag) / growth, 0, 1);
            double radius = core * (0.55 - 0.07 * k) * (0.5 + 0.5 * smooth(grown / 0.7));
            put(o + 4 * k, x, z, radius, towerTop);
            reachSq = Math.max(reachSq, distance + Math.abs(side) + radius);
        }
        // Two early cumuli beside the core, absorbed as the main tower grows over them.
        for (int k = 4; k <= 5; k++) {
            double angle = hash(id, 40 + k) * Maths.TAU;
            double distance = core * (0.9 + 0.5 * hash(id, 50 + k));
            double lag = growth * 0.12 * hash(id, 60 + k);
            double grown = Math.clamp((age - lag) / growth, 0, 1);
            double height = (storms.topAt(s, age - lag) - baseAltitude) * (0.25 + 0.15 * hash(id, 70 + k))
                * (1 - smooth((g - 0.45) / 0.35));
            double radius = core * 0.25 * (0.6 + 0.4 * smooth(grown / 0.3));
            double x = cx + Maths.cos(angle) * distance;
            double z = cz + Maths.sin(angle) * distance;
            put(o + 4 * k, x, z, radius, baseAltitude + height);
            reachSq = Math.max(reachSq, distance + radius);
        }
        count[c] = TOWERS;
        // Glaciated as the top passes the level; the ice outlives the liquid towers as the cell dissipates (it joins
        // the anvil), then goes too.
        double ice = smooth((top - Math.min(glaciationAltitude, maxTop - 1500)) / 1500)
            * (1 - smooth((dissipating - 0.5) / 0.5)) * fade;
        glaciation[c] = (float) ice;
        fibres[c] = (float) (ice * smooth(storms.anvil(s) / 0.5));
        // The liquid towers rain out and erode away as the cell dissipates, and fade far away.
        water[c] = (float) ((1 - smooth(dissipating / 0.85)) * fade);
        // The overshooting top: early in the mature stage, rising and sinking back over about twenty minutes.
        double mature = (age - growth) / Math.max(matureEnd - growth, 1);
        double bump = smooth(mature / 0.15) * (1 - smooth((mature - 0.25) / 0.45));
        overshoot[c] = (float) (overshootHeight * bump * fade);
        anvil[c] = (float) (storms.anvil(s) * fade);
        anvilTop[c] = (float) maxTop;
        ceiling[c] = (float) (maxTop + 150);
        // The main tower's top widens into the anvil as it spreads (the incus' shape).
        flare[c] = (float) (0.35 * smooth(storms.anvil(s) / 0.4));
        // Reach: the towers' farthest edge plus their lean at the top and their turrets.
        double height = Math.max(top, maxTop) - baseAltitude;
        reach[c] = (float) (1.15 * reachSq + lean * height + 600);
        // The cell's place in the turret noise: its own (hashed), rising with its updraft.
        noise[3 * c] = (float) hash(id, 80);
        noise[3 * c + 1] = (float) (hash(id, 81) - age * rise);
        noise[3 * c + 2] = (float) hash(id, 82);
        highest = (float) Math.max(highest, Math.max(top, maxTop) + overshoot[c] + 300);
    }

    private void put(int o, double x, double z, double radius, double top) {
        towers[o] = (float) x;
        towers[o + 1] = (float) z;
        towers[o + 2] = (float) Math.max(radius, 0);
        towers[o + 3] = (float) top;
    }
}
