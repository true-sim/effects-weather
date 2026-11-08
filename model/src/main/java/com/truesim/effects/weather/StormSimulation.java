package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Rng;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.effects.weather.lightning.LightningEvents;

/**
 * Thunderstorm cells through their life cycle, on fixed steps, deterministic: the same seed, configuration and calls
 * give bit-identical cells and flashes on every machine (common-math's xoshiro {@code Rng} and fdlibm functions, no
 * {@code java.lang.Math} transcendental, slots in a fixed order). Multiplayer peers step it in lockstep or replicate
 * {@link #stateHash} to check.
 *
 * <p>A cell forms (at the storm rate around the focus, as a daughter of a mature cell, or by {@link #spawn}) and goes
 * through three stages ({@link Stage}): <b>growing</b> (its top rises from the base to its maximum, towering cumulus
 * until 80 %, then cumulonimbus; rain starts at 70 %, the first flashes at 80 %), <b>mature</b> (full height, the
 * heaviest rain, peak flash rate, the anvil spreading downwind) and <b>dissipating</b> (rain and lightning die away,
 * the top sinks by a fifth, the anvil lingers and thins). Mature cells seed daughter cells on their flank (multicell
 * storms) where no other core stands. It drifts with the wind at half its top's height (the
 * steering level). Flashes go to a {@link LightningEvents} ring. {@link #step} allocates nothing.
 */
public final class StormSimulation {

    /** A cell's stage. */
    public enum Stage {
        GROWING,
        MATURE,
        DISSIPATING
    }

    private static final Stage[] STAGES = Stage.values();

    private final StormConfig config;
    private final LightningConfig lightning;
    private final LightningEvents events;
    private final WeatherDomain domain;
    private final Rng rng;
    private final int capacity;
    private final double[] scratch = new double[9];
    private final double[] point = new double[3];

    private final boolean[] active;
    private final int[] id;
    private final double[] age;
    private final double[] growthEnd;
    private final double[] matureEnd;
    private final double[] end;
    private final double[] position;
    private final double[] anvilPosition;
    private final double[] maxTop;
    private final double[] coreRadius;
    private final double[] maxPrecipitation;
    private final double[] maxFlashRate;
    // Derived each step.
    private final byte[] stage;
    private final double[] top;
    private final double[] coverage;
    private final double[] precipitation;
    private final double[] anvil;
    private final double[] flashRate;
    private int nextId = 1;
    private int activeCount;

    @AllowAllocation("made once")
    public StormSimulation(StormConfig config, LightningConfig lightning, LightningEvents events, WeatherDomain domain,
        long seed) {
        this.config = config;
        this.lightning = lightning;
        this.events = events;
        this.domain = domain;
        this.rng = Rng.seeded(seed);
        capacity = config.maxCells();
        active = new boolean[capacity];
        id = new int[capacity];
        age = new double[capacity];
        growthEnd = new double[capacity];
        matureEnd = new double[capacity];
        end = new double[capacity];
        position = new double[3 * capacity];
        anvilPosition = new double[3 * capacity];
        maxTop = new double[capacity];
        coreRadius = new double[capacity];
        maxPrecipitation = new double[capacity];
        maxFlashRate = new double[capacity];
        stage = new byte[capacity];
        top = new double[capacity];
        coverage = new double[capacity];
        precipitation = new double[capacity];
        anvil = new double[capacity];
        flashRate = new double[capacity];
    }

    /** Forms a cell at ground point (x, y, z); returns its slot, or −1 when every slot is taken. */
    public int spawn(double x, double y, double z) {
        int s = -1;
        for (int i = 0; i < capacity; i++) {
            if (!active[i]) {
                s = i;
                break;
            }
        }
        if (s < 0) {
            return -1;
        }
        active[s] = true;
        id[s] = nextId++;
        age[s] = 0;
        growthEnd[s] = rng.nextDouble(config.growthMin(), config.growthMax() + 1e-9);
        matureEnd[s] = growthEnd[s] + rng.nextDouble(config.matureMin(), config.matureMax() + 1e-9);
        end[s] = matureEnd[s] + rng.nextDouble(config.decayMin(), config.decayMax() + 1e-9);
        position[3 * s] = x;
        position[3 * s + 1] = y;
        position[3 * s + 2] = z;
        anvilPosition[3 * s] = x;
        anvilPosition[3 * s + 1] = y;
        anvilPosition[3 * s + 2] = z;
        maxTop[s] = rng.nextDouble(config.topMin(), config.topMax() + 1e-9);
        coreRadius[s] = rng.nextDouble(config.coreRadiusMin(), config.coreRadiusMax() + 1e-9);
        maxPrecipitation[s] = rng.nextDouble(config.precipitationMin(), config.precipitationMax() + 1e-9);
        maxFlashRate[s] = rng.nextDouble(config.flashRateMin(), config.flashRateMax() + 1e-9);
        activeCount++;
        derive(s);
        return s;
    }

    /**
     * Advances every cell by {@code dt} seconds ending at simulation time {@code time + dt}: forms new cells at
     * {@code stormRate} (per 10,000 km² per hour, times the configuration's scale) within the spawn radius of the focus
     * ground point (fx, fy, fz), moves them with {@code wind} (times {@code windScale}), ages them, emits their flashes
     * and drops dead and distant ones.
     */
    public void step(double time, double dt, double stormRate, WindProfile wind, double windScale, double fx,
        double fy, double fz) {
        double area = Maths.PI * config.spawnRadius() * config.spawnRadius() / 1e10;
        int spawns = poisson(stormRate * config.rateScale() * area * dt / 3600.0);
        for (int k = 0; k < spawns; k++) {
            double r = config.spawnRadius() * Math.sqrt(rng.nextDouble());
            double theta = rng.nextDouble() * Maths.TAU;
            point[0] = fx;
            point[1] = fy;
            point[2] = fz;
            domain.move(point, 0, r * Maths.cos(theta), r * Maths.sin(theta), scratch);
            spawn(point[0], point[1], point[2]);
        }
        double limit = 1.5 * config.spawnRadius();
        for (int s = 0; s < capacity; s++) {
            if (!active[s]) {
                continue;
            }
            age[s] += dt;
            if (age[s] >= end[s] || distance(s, fx, fy, fz) > limit) {
                active[s] = false;
                activeCount--;
                continue;
            }
            derive(s);
            double steering = 0.5 * top[s];
            domain.move(position, 3 * s, wind.east(steering) * windScale * dt, wind.north(steering) * windScale * dt,
                scratch);
            // The anvil sits downwind of the core at the top's wind, as far as it has spread.
            double ue = wind.east(top[s]);
            double un = wind.north(top[s]);
            double speed = Math.sqrt(ue * ue + un * un);
            double reach = speed > 1e-6 ? 0.5 * anvilRadius(s) * anvil[s] / speed : 0.0;
            System.arraycopy(position, 3 * s, anvilPosition, 3 * s, 3);
            domain.move(anvilPosition, 3 * s, ue * reach, un * reach, scratch);
            if (stage[s] == Stage.MATURE.ordinal() && config.daughterRate() > 0
                && rng.nextDouble() < config.daughterRate() * dt / 60.0) {
                double theta = rng.nextDouble() * Maths.TAU;
                double r = coreRadius[s] * rng.nextDouble(1.5, 2.5);
                System.arraycopy(position, 3 * s, point, 0, 3);
                domain.move(point, 0, r * Maths.cos(theta), r * Maths.sin(theta), scratch);
                // A new updraft needs room: none where another core already stands (its outflow suppresses it).
                if (!crowded(point[0], point[1], point[2])) {
                    spawn(point[0], point[1], point[2]);
                }
            }
            if (lightning.enabled()) {
                flashes(s, time, dt);
            }
        }
    }

    /** Whether a cell's core covers (x, y, z) within 1.2 of its radius. */
    private boolean crowded(double x, double y, double z) {
        for (int c = 0; c < capacity; c++) {
            if (active[c] && distance(c, x, y, z) < 1.2 * coreRadius[c]) {
                return true;
            }
        }
        return false;
    }

    private double distance(int s, double fx, double fy, double fz) {
        double dx = position[3 * s] - fx;
        double dy = position[3 * s + 1] - fy;
        double dz = position[3 * s + 2] - fz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** The stage-dependent values of cell {@code s} at its age. */
    private void derive(int s) {
        double t = age[s];
        double base = config.baseAltitude();
        if (t < growthEnd[s]) {
            double g = smooth(t / growthEnd[s]);
            stage[s] = (byte) Stage.GROWING.ordinal();
            top[s] = base + (maxTop[s] - base) * g;
            coverage[s] = 0.6 + 0.4 * g;
            precipitation[s] = maxPrecipitation[s] * 0.5 * Math.max(0.0, (g - 0.7) / 0.3);
            flashRate[s] = maxFlashRate[s] * 0.3 * Math.max(0.0, (g - 0.8) / 0.2);
            anvil[s] = 0;
        } else if (t < matureEnd[s]) {
            stage[s] = (byte) Stage.MATURE.ordinal();
            top[s] = maxTop[s];
            coverage[s] = 1.0;
            precipitation[s] = maxPrecipitation[s];
            flashRate[s] = maxFlashRate[s];
            // The anvil spreads once the updraft reaches the tropopause: over the first 20 minutes of maturity (at
            // the top's wind, ten or more metres a second), not the whole mature stage.
            anvil[s] = smooth((t - growthEnd[s]) / Math.min(ANVIL_SPREAD, matureEnd[s] - growthEnd[s]));
        } else {
            double u = (t - matureEnd[s]) / (end[s] - matureEnd[s]);
            double v = 1 - u;
            stage[s] = (byte) Stage.DISSIPATING.ordinal();
            top[s] = maxTop[s] * (1 - 0.2 * u);
            coverage[s] = v;
            precipitation[s] = maxPrecipitation[s] * v * v;
            flashRate[s] = maxFlashRate[s] * v * v * v;
            anvil[s] = 1.0;
        }
    }

    /** Seconds an anvil takes to spread to its full radius once a cell matures. */
    static final double ANVIL_SPREAD = 1200;

    private static double smooth(double x) {
        double t = Math.clamp(x, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    /** A Poisson draw of mean {@code mean} (Knuth's product of uniforms; means here are small). */
    private int poisson(double mean) {
        if (mean <= 0) {
            return 0;
        }
        double limit = Maths.exp(-Math.min(mean, 50.0));
        int k = 0;
        double p = rng.nextDouble();
        while (p > limit) {
            k++;
            p *= rng.nextDouble();
        }
        return k;
    }

    private void flashes(int s, double time, double dt) {
        int n = poisson(flashRate[s] / 60.0 * dt);
        double icChance = lightning.intracloudRatio() / (1 + lightning.intracloudRatio());
        for (int k = 0; k < n; k++) {
            double t = time + rng.nextDouble() * dt;
            double kind = rng.nextDouble();
            FlashType type = kind < icChance ? FlashType.INTRACLOUD
                : rng.nextDouble() < lightning.positiveFraction() ? FlashType.POSITIVE_GROUND : FlashType.NEGATIVE_GROUND;
            double sigma = lightning.peakCurrentSigmaLn() * rng.nextGaussian();
            long seed = rng.nextLong();
            if (type == FlashType.INTRACLOUD) {
                place(s, position, 0.6 * coreRadius[s], top[s] * rng.nextDouble(0.5, 0.75));
                double ox = point[0];
                double oy = point[1];
                double oz = point[2];
                place(s, anvilPosition, anvilRadius(s) * 0.5 + coreRadius[s], top[s] * rng.nextDouble(0.55, 0.8));
                events.add(t, type, ox, oy, oz, point[0], point[1], point[2],
                    -lightning.medianPeakCurrentKa() * 0.3 * Maths.exp(sigma), 1, seed, id[s]);
            } else if (type == FlashType.NEGATIVE_GROUND) {
                place(s, position, 0.5 * coreRadius[s], top[s] * rng.nextDouble(0.4, 0.5));
                double ox = point[0];
                double oy = point[1];
                double oz = point[2];
                place(s, position, coreRadius[s], 0);
                int strokes = 1 + poisson(lightning.meanStrokes() - 1);
                events.add(t, type, ox, oy, oz, point[0], point[1], point[2],
                    -lightning.medianPeakCurrentKa() * Maths.exp(sigma), strokes, seed, id[s]);
            } else {
                place(s, anvilPosition, anvilRadius(s) * 0.5, top[s] * 0.7);
                double ox = point[0];
                double oy = point[1];
                double oz = point[2];
                place(s, anvilPosition, anvilRadius(s) * 0.5, 0);
                events.add(t, type, ox, oy, oz, point[0], point[1], point[2],
                    lightning.medianPositivePeakCurrentKa() * Maths.exp(sigma), 1, seed, id[s]);
            }
        }
    }

    /**
     * Writes into {@code point} a random place within {@code radius} of the ground point of slot {@code s} in
     * {@code centres}, {@code altitude} metres up.
     */
    private void place(int s, double[] centres, double radius, double altitude) {
        double r = radius * Math.sqrt(rng.nextDouble());
        double theta = rng.nextDouble() * Maths.TAU;
        System.arraycopy(centres, 3 * s, point, 0, 3);
        domain.move(point, 0, r * Maths.cos(theta), r * Maths.sin(theta), scratch);
        domain.frame(point[0], point[1], point[2], scratch);
        point[0] += scratch[6] * altitude;
        point[1] += scratch[7] * altitude;
        point[2] += scratch[8] * altitude;
    }

    /** The anvil's full radius of cell {@code s}, metres. */
    public double anvilRadius(int s) {
        return coreRadius[s] * config.anvilFactor();
    }

    public int capacity() {
        return capacity;
    }

    /** How many cells are alive. */
    public int activeCount() {
        return activeCount;
    }

    public boolean active(int s) {
        return active[s];
    }

    /** The cell's id: unique for the simulation's life (slots are reused). */
    public int id(int s) {
        return id[s];
    }

    public Stage stage(int s) {
        return STAGES[stage[s]];
    }

    /** Seconds since the cell formed. */
    public double age(int s) {
        return age[s];
    }

    /** The core's ground point, coordinate {@code axis}. */
    public double position(int s, int axis) {
        return position[3 * s + axis];
    }

    /** The anvil's centre (ground point under it), coordinate {@code axis}. */
    public double anvilPosition(int s, int axis) {
        return anvilPosition[3 * s + axis];
    }

    /** The cloud top, metres. */
    public double top(int s) {
        return top[s];
    }

    public double coreRadius(int s) {
        return coreRadius[s];
    }

    /** The core's coverage [0, 1]. */
    public double coverage(int s) {
        return coverage[s];
    }

    /** Precipitation [0, 1] of the maximum rate. */
    public double precipitation(int s) {
        return precipitation[s];
    }

    /** How far the anvil has spread [0, 1]. */
    public double anvil(int s) {
        return anvil[s];
    }

    /** Flashes per minute now. */
    public double flashRate(int s) {
        return flashRate[s];
    }

    /** The cloud type the core shows: towering cumulus while young, then cumulonimbus. */
    public CloudType cloudType(int s) {
        return stage[s] == Stage.GROWING.ordinal() && top[s] < config.baseAltitude()
            + 0.8 * (maxTop[s] - config.baseAltitude()) ? CloudType.TOWERING_CUMULUS : CloudType.CUMULONIMBUS;
    }

    /** A hash of the whole state (FNV-1a over every value's bits): equal on every machine for equal inputs. */
    public long stateHash() {
        long h = 0xcbf29ce484222325L;
        for (int s = 0; s < capacity; s++) {
            h = mix(h, active[s] ? 1 : 0);
            if (!active[s]) {
                continue;
            }
            h = mix(h, id[s]);
            h = mix(h, Double.doubleToLongBits(age[s]));
            for (int a = 0; a < 3; a++) {
                h = mix(h, Double.doubleToLongBits(position[3 * s + a]));
                h = mix(h, Double.doubleToLongBits(anvilPosition[3 * s + a]));
            }
            h = mix(h, Double.doubleToLongBits(top[s]));
            h = mix(h, Double.doubleToLongBits(precipitation[s]));
            h = mix(h, Double.doubleToLongBits(end[s]));
        }
        return mix(h, nextId);
    }

    private static long mix(long h, long v) {
        long x = h;
        for (int i = 0; i < 8; i++) {
            x ^= (v >>> (8 * i)) & 0xff;
            x *= 0x100000001b3L;
        }
        return x;
    }
}
