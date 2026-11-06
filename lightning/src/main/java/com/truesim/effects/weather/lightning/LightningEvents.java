package com.truesim.effects.weather.lightning;

import com.truesim.annotations.AllowAllocation;

/**
 * The flashes the storm simulation produced, for renderers (the in-cloud glow, the scene light, bolts) and audio
 * (thunder): a ring of the latest {@link #capacity()} events as packed arrays, each with a sequential id. Consumers keep
 * the last id they handled and read what came after ({@link #forEachSince}); one that falls more than the capacity
 * behind misses the oldest. Positions are metres in the weather domain's frame (x east, y up, z south on a flat world;
 * planet-centred on a sphere). Adding and reading allocate nothing.
 */
public final class LightningEvents {

    /** Receives events. */
    @FunctionalInterface
    public interface Visitor {

        /** The event of {@code id} is in {@code slot} of {@code events}. */
        void event(LightningEvents events, int slot, long id);
    }

    private final int capacity;
    private final double[] time;
    private final byte[] type;
    private final double[] origin;
    private final double[] end;
    private final float[] peakCurrent;
    private final int[] strokes;
    private final long[] seed;
    private final int[] cell;
    private long next;

    /** A ring of {@code capacity} events. */
    @AllowAllocation("made once")
    public LightningEvents(int capacity) {
        this.capacity = Checks.range(capacity, 1, 1 << 20, "capacity");
        time = new double[capacity];
        type = new byte[capacity];
        origin = new double[3 * capacity];
        end = new double[3 * capacity];
        peakCurrent = new float[capacity];
        strokes = new int[capacity];
        seed = new long[capacity];
        cell = new int[capacity];
    }

    public int capacity() {
        return capacity;
    }

    /** The id the next event gets; the number of events ever added. */
    public long nextId() {
        return next;
    }

    /**
     * Adds a flash: when (simulation seconds), what, where it starts (in the cloud) and ends (the ground strike point,
     * or the far end of an intracloud channel), its peak current (kA, signed: positive flashes positive), its strokes,
     * its seed (bolt branching, flicker) and the storm cell it came from. Returns its id.
     */
    public long add(double t, FlashType flashType, double ox, double oy, double oz, double ex, double ey, double ez,
        double peakKa, int strokeCount, long flashSeed, int cellId) {
        int s = (int) (next % capacity);
        time[s] = t;
        type[s] = (byte) flashType.ordinal();
        origin[3 * s] = ox;
        origin[3 * s + 1] = oy;
        origin[3 * s + 2] = oz;
        end[3 * s] = ex;
        end[3 * s + 1] = ey;
        end[3 * s + 2] = ez;
        peakCurrent[s] = (float) peakKa;
        strokes[s] = strokeCount;
        seed[s] = flashSeed;
        cell[s] = cellId;
        return next++;
    }

    /** Visits the events after {@code lastSeenId} (−1 for all retained), oldest first; returns the newest id or −1. */
    public long forEachSince(long lastSeenId, Visitor visitor) {
        long first = Math.max(lastSeenId + 1, next - capacity);
        for (long id = Math.max(first, 0); id < next; id++) {
            visitor.event(this, (int) (id % capacity), id);
        }
        return next - 1;
    }

    public double time(int slot) {
        return time[slot];
    }

    public FlashType type(int slot) {
        return FlashType.of(type[slot]);
    }

    /** The origin's coordinate {@code axis} (0 x, 1 y, 2 z), metres. */
    public double origin(int slot, int axis) {
        return origin[3 * slot + axis];
    }

    /** The end's coordinate {@code axis}, metres. */
    public double end(int slot, int axis) {
        return end[3 * slot + axis];
    }

    /** Peak current of the first stroke, kA (negative for negative flashes). */
    public float peakCurrent(int slot) {
        return peakCurrent[slot];
    }

    public int strokes(int slot) {
        return strokes[slot];
    }

    public long seed(int slot) {
        return seed[slot];
    }

    /** The storm cell it came from, −1 for flashes the game triggered. */
    public int cell(int slot) {
        return cell[slot];
    }
}
