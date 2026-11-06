package com.truesim.effects.weather.lightning;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.common.math.random.Hash;

/**
 * A lightning bolt's shape: a tree of segments from the flash's origin to its end, made by midpoint displacement
 * (each level splits every segment and pushes its midpoint sideways by up to {@code boltDisplacement} of its length)
 * with branches forking off new points towards the channel's end. A function of the endpoints, the seed and the
 * configuration: the same bolt on every machine. Reusable buffers; {@link #generate} allocates nothing.
 *
 * <p>For drawing (an emissive ribbon per segment with {@link #brightness}), for the in-cloud glow (distance to the
 * nearest segment) and for thunder ({@link Thunder#nearest}). Branch depth is limited to 3.
 */
public final class BoltGeometry {

    private static final int MAX_DEPTH = 3;

    private final int capacity;
    private final double[] x;
    private final double[] y;
    private final double[] z;
    private int points;
    private final int[] start;
    private final int[] end;
    private final int[] depth;
    private final int[] channelEnd;
    private final int[] tmpStart;
    private final int[] tmpEnd;
    private final int[] tmpDepth;
    private final int[] tmpChannelEnd;
    private int segments;
    private int seed;
    private int counter;
    private float branchBrightness;

    /** Buffers for bolts of up to {@code maxPoints} points. */
    @AllowAllocation("made once")
    public BoltGeometry(int maxPoints) {
        capacity = Checks.range(maxPoints, 2, 1 << 20, "maxPoints");
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
        start = new int[capacity];
        end = new int[capacity];
        depth = new int[capacity];
        channelEnd = new int[capacity];
        tmpStart = new int[capacity];
        tmpEnd = new int[capacity];
        tmpDepth = new int[capacity];
        tmpChannelEnd = new int[capacity];
    }

    private double random() {
        return Hash.random(seed, counter++);
    }

    private int addPoint(double px, double py, double pz) {
        x[points] = px;
        y[points] = py;
        z[points] = pz;
        return points++;
    }

    /** Makes the bolt from (sx, sy, sz) to (ex, ey, ez) for {@code flashSeed}. */
    public void generate(double sx, double sy, double sz, double ex, double ey, double ez, long flashSeed,
        LightningConfig config) {
        seed = Hash.combine((int) flashSeed, (int) (flashSeed >>> 32));
        counter = 0;
        points = 0;
        branchBrightness = (float) config.branchBrightness();
        addPoint(sx, sy, sz);
        addPoint(ex, ey, ez);
        start[0] = 0;
        end[0] = 1;
        depth[0] = 0;
        channelEnd[0] = 1;
        segments = 1;
        for (int level = 0; level < config.boltLevels(); level++) {
            int m = 0;
            for (int s = 0; s < segments; s++) {
                int a = start[s];
                int b = end[s];
                double dx = x[b] - x[a];
                double dy = y[b] - y[a];
                double dz = z[b] - z[a];
                double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
                // Room for a midpoint and a branch tip, and for three segments in the next buffer.
                if (len == 0 || points + 2 > capacity || m + 3 > capacity) {
                    m = keep(m, a, b, depth[s], channelEnd[s]);
                    continue;
                }
                // A sideways direction: a random unit vector without its component along the segment.
                double ux = dx / len;
                double uy = dy / len;
                double uz = dz / len;
                double rz = 2 * random() - 1;
                double phi = random() * Maths.TAU;
                double rr = Math.sqrt(1 - rz * rz);
                double rx = rr * Maths.cos(phi);
                double ry = rr * Maths.sin(phi);
                double along = rx * ux + ry * uy + rz * uz;
                rx -= along * ux;
                ry -= along * uy;
                rz -= along * uz;
                double offset = (2 * random() - 1) * config.boltDisplacement() * len;
                double side = Math.sqrt(rx * rx + ry * ry + rz * rz);
                double k = side > 1e-9 ? offset / side : 0.0;
                int mid = addPoint(0.5 * (x[a] + x[b]) + k * rx, 0.5 * (y[a] + y[b]) + k * ry,
                    0.5 * (z[a] + z[b]) + k * rz);
                m = keep(m, a, mid, depth[s], channelEnd[s]);
                m = keep(m, mid, b, depth[s], channelEnd[s]);
                if (depth[s] < MAX_DEPTH && random() < config.branchChance()) {
                    m = branch(m, mid, channelEnd[s], depth[s] + 1, len, config);
                }
            }
            System.arraycopy(tmpStart, 0, start, 0, m);
            System.arraycopy(tmpEnd, 0, end, 0, m);
            System.arraycopy(tmpDepth, 0, depth, 0, m);
            System.arraycopy(tmpChannelEnd, 0, channelEnd, 0, m);
            segments = m;
        }
    }

    private int keep(int m, int a, int b, int d, int channel) {
        tmpStart[m] = a;
        tmpEnd[m] = b;
        tmpDepth[m] = d;
        tmpChannelEnd[m] = channel;
        return m + 1;
    }

    /**
     * A branch from {@code from} towards the channel's end, deflected randomly: branchLength of the remaining way,
     * but at most three times the length of the segment it forks from, so forks made late (and no longer subdivided)
     * stay short twigs while early ones become long, jagged arms.
     */
    private int branch(int m, int from, int toward, int d, double segmentLength, LightningConfig config) {
        double tx = x[toward] - x[from];
        double ty = y[toward] - y[from];
        double tz = z[toward] - z[from];
        double remaining = Math.sqrt(tx * tx + ty * ty + tz * tz);
        if (remaining == 0) {
            return m;
        }
        double rz = 2 * random() - 1;
        double phi = random() * Maths.TAU;
        double rr = Math.sqrt(1 - rz * rz);
        double bx = tx / remaining + 0.8 * rr * Maths.cos(phi);
        double by = ty / remaining + 0.8 * rr * Maths.sin(phi);
        double bz = tz / remaining + 0.8 * rz;
        double norm = Math.sqrt(bx * bx + by * by + bz * bz);
        double length = Math.min(config.branchLength() * remaining, 3 * segmentLength) / Math.max(norm, 1e-9);
        int tip = addPoint(x[from] + bx * length, y[from] + by * length, z[from] + bz * length);
        return keep(m, from, tip, d, tip);
    }

    public int capacity() {
        return capacity;
    }

    public int pointCount() {
        return points;
    }

    public int segmentCount() {
        return segments;
    }

    public double x(int point) {
        return x[point];
    }

    public double y(int point) {
        return y[point];
    }

    public double z(int point) {
        return z[point];
    }

    /** The point segment {@code s} starts at (nearer the origin). */
    public int segmentStart(int s) {
        return start[s];
    }

    /** The point segment {@code s} ends at. */
    public int segmentEnd(int s) {
        return end[s];
    }

    /** 0 for the main channel, 1 for its branches, and so on. */
    public int depth(int s) {
        return depth[s];
    }

    /** The segment's brightness relative to the main channel: branchBrightness^depth. */
    public float brightness(int s) {
        float b = 1f;
        for (int i = 0; i < depth[s]; i++) {
            b *= branchBrightness;
        }
        return b;
    }
}
