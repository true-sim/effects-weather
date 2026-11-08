package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.lightning.Flicker;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.effects.weather.lightning.LightningEvents;

/**
 * The flashes lit during a frame, the brightest first: each event of a {@link LightningEvents} ring whose flicker
 * ({@link Flicker}) is still going, with its mean luminous intensity over the frame (candela: the configured peak of a
 * median first stroke, scaled by the flash's peak current over the median, times {@link Flicker#average} over the
 * frame's interval, so millisecond strokes never alias at any frame rate). The clouds' glow ({@link WeatherGpu}), the
 * scene's flash lights ({@link LightningLights}) and the bolts ({@link LightningBolts}) read the same selection.
 * Selecting allocates nothing.
 */
public final class ActiveFlashes {

    private final int capacity;
    private final int[] slots;
    private final double[] intensities;
    private final double[] ages;
    private int count;

    /** Room for {@code capacity} flashes at once. */
    @AllowAllocation("made once")
    public ActiveFlashes(int capacity) {
        this.capacity = capacity;
        slots = new int[Math.max(capacity, 1)];
        intensities = new double[Math.max(capacity, 1)];
        ages = new double[Math.max(capacity, 1)];
    }

    /**
     * Selects the flashes of {@code events} lit between {@code from} and {@code to} (seconds of the weather's time), up
     * to {@code limit} (at most the capacity), the brightest kept; {@code peak} is a median first stroke's peak
     * intensity in candela.
     */
    public ActiveFlashes select(LightningEvents events, LightningConfig config, double from, double to, double peak,
        int limit) {
        count = 0;
        int wanted = Math.min(limit, capacity);
        if (wanted <= 0) {
            return this;
        }
        long next = events.nextId();
        for (long id = Math.max(0, next - events.capacity()); id < next; id++) {
            int slot = (int) (id % events.capacity());
            double start = events.time(slot);
            if (start > to) {
                continue;
            }
            double end = start + Flicker.duration(events.type(slot), events.strokes(slot), events.seed(slot), config);
            if (end < from) {
                continue;
            }
            double relative = Flicker.average(events.type(slot), events.strokes(slot), events.seed(slot),
                Math.max(from - start, 0), Math.max(to - start, 0), config);
            double median = events.peakCurrent(slot) >= 0 ? config.medianPositivePeakCurrentKa()
                : config.medianPeakCurrentKa();
            double intensity = peak * Math.abs(events.peakCurrent(slot)) / median * relative;
            if (intensity <= 0) {
                continue;
            }
            insert(slot, intensity, to - start, wanted);
        }
        return this;
    }

    /** Keeps the brightest {@code wanted}, sorted brightest first. */
    private void insert(int slot, double intensity, double age, int wanted) {
        if (count == wanted && intensities[count - 1] >= intensity) {
            return;
        }
        int i = count < wanted ? count : count - 1;
        while (i > 0 && intensities[i - 1] < intensity) {
            slots[i] = slots[i - 1];
            intensities[i] = intensities[i - 1];
            ages[i] = ages[i - 1];
            i--;
        }
        slots[i] = slot;
        intensities[i] = intensity;
        ages[i] = age;
        if (count < wanted) {
            count++;
        }
    }

    /** How many flashes are lit. */
    public int count() {
        return count;
    }

    /** The event ring's slot of flash {@code i}. */
    public int slot(int i) {
        return slots[i];
    }

    /** Its mean luminous intensity over the frame, candela. */
    public double intensity(int i) {
        return intensities[i];
    }

    /** Seconds since it began, at the frame's end. */
    public double age(int i) {
        return ages[i];
    }

    /** The sum of the lit flashes' intensities: a flash is lighting things when it is above 0. */
    public double total() {
        double sum = 0;
        for (int i = 0; i < count; i++) {
            sum += intensities[i];
        }
        return sum;
    }
}
