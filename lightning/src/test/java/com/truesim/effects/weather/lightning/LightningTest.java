package com.truesim.effects.weather.lightning;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.atmosphere.Quality;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Lightning events, flicker, bolt geometry and thunder. */
class LightningTest {

    private static final LightningConfig CONFIG = LightningConfig.defaults();

    @Test
    void eventsAreARingReadSinceAMark() {
        LightningEvents events = new LightningEvents(3);
        for (int i = 0; i < 5; i++) {
            events.add(i, FlashType.NEGATIVE_GROUND, i, 5000, 0, i, 0, 100, -30, 4, 77L + i, 2);
        }
        List<Long> seen = new ArrayList<>();
        long newest = events.forEachSince(-1, (e, slot, id) -> seen.add(id));
        assertThat(seen).containsExactly(2L, 3L, 4L);
        assertThat(newest).isEqualTo(4);
        seen.clear();
        events.forEachSince(3, (e, slot, id) -> {
            seen.add(id);
            assertThat(e.time(slot)).isEqualTo(4.0);
            assertThat(e.type(slot)).isEqualTo(FlashType.NEGATIVE_GROUND);
            assertThat(e.origin(slot, 1)).isEqualTo(5000.0);
            assertThat(e.end(slot, 2)).isEqualTo(100.0);
            assertThat(e.peakCurrent(slot)).isEqualTo(-30f);
            assertThat(e.strokes(slot)).isEqualTo(4);
            assertThat(e.seed(slot)).isEqualTo(81L);
            assertThat(e.cell(slot)).isEqualTo(2);
        });
        assertThat(seen).containsExactly(4L);
        assertThat(events.nextId()).isEqualTo(5);
        assertThat(events.capacity()).isEqualTo(3);
        assertThat(new LightningEvents(4).forEachSince(-1, (e, s, id) -> seen.add(id))).isEqualTo(-1);
        assertThat(FlashType.INTRACLOUD.strikesGround()).isFalse();
        assertThat(FlashType.of(1)).isEqualTo(FlashType.POSITIVE_GROUND);
    }

    @Test
    void groundFlashesFlickerWithStrokes() {
        long seed = 12345;
        assertThat(Flicker.strokeTime(FlashType.NEGATIVE_GROUND, seed, 0, CONFIG)).isZero();
        double previous = 0;
        for (int k = 1; k < 4; k++) {
            double t = Flicker.strokeTime(FlashType.NEGATIVE_GROUND, seed, k, CONFIG);
            assertThat(t).isGreaterThan(previous);
            previous = t;
        }
        assertThat(Flicker.strokePeak(FlashType.NEGATIVE_GROUND, seed, 0)).isEqualTo(1.0);
        assertThat(Flicker.strokePeak(FlashType.NEGATIVE_GROUND, seed, 2)).isBetween(0.3, 0.7);
        assertThat(Flicker.intensity(FlashType.NEGATIVE_GROUND, 4, seed, 0, CONFIG)).isGreaterThanOrEqualTo(1.0);
        assertThat(Flicker.intensity(FlashType.NEGATIVE_GROUND, 4, seed, -1, CONFIG)).isZero();
        double end = Flicker.duration(FlashType.NEGATIVE_GROUND, 4, seed, CONFIG);
        assertThat(end).isGreaterThan(previous);
        assertThat(Flicker.intensity(FlashType.NEGATIVE_GROUND, 4, seed, end + 1, CONFIG)).isLessThan(1e-6);
        // The mean interstroke interval over many flashes is the configured 60 ms.
        double sum = 0;
        for (int s = 0; s < 4000; s++) {
            sum += Flicker.strokeTime(FlashType.NEGATIVE_GROUND, s, 1, CONFIG);
        }
        assertThat(sum / 4000).isCloseTo(0.060, within(0.004));
    }

    @Test
    void averagesIntegrateExactly() {
        long seed = 99;
        double t0 = 0.0;
        double t1 = 0.4;
        int n = 400_000;
        double numeric = 0;
        for (int i = 0; i < n; i++) {
            numeric += Flicker.intensity(FlashType.NEGATIVE_GROUND, 3, seed, t0 + (i + 0.5) * (t1 - t0) / n, CONFIG);
        }
        numeric /= n;
        assertThat(Flicker.average(FlashType.NEGATIVE_GROUND, 3, seed, t0, t1, CONFIG)).isCloseTo(numeric,
            within(numeric * 0.01));
        assertThat(Flicker.average(FlashType.INTRACLOUD, 1, seed, 0.1, 0.1, CONFIG))
            .isEqualTo(Flicker.intensity(FlashType.INTRACLOUD, 1, seed, 0.1, CONFIG));
        // An intracloud flash pulses over its duration, weaker than a ground stroke.
        double tLast = Flicker.strokeTime(FlashType.INTRACLOUD, seed, CONFIG.intracloudPulses() - 1, CONFIG);
        assertThat(tLast).isBetween(0.15, 0.35);
        assertThat(Flicker.strokePeak(FlashType.INTRACLOUD, seed, 0)).isBetween(0.2, 0.5);
        assertThat(Flicker.average(FlashType.INTRACLOUD, 1, seed, 0, 0.3, CONFIG)).isPositive();
    }

    @Test
    void boltsReachTheirEndAndAreDeterministic() {
        BoltGeometry bolt = new BoltGeometry(CONFIG.maxBoltPoints());
        bolt.generate(0, 5000, 0, 300, 0, -200, 42L, CONFIG);
        assertThat(bolt.x(0)).isZero();
        assertThat(bolt.y(1)).isZero();
        int main = 0;
        int point = 0;
        // Follow the main channel from the origin: it reaches the end point.
        for (int guard = 0; guard < bolt.segmentCount() && point != 1; guard++) {
            for (int s = 0; s < bolt.segmentCount(); s++) {
                if (bolt.depth(s) == 0 && bolt.segmentStart(s) == point) {
                    point = bolt.segmentEnd(s);
                    main++;
                    break;
                }
            }
        }
        assertThat(point).isEqualTo(1);
        assertThat(main).isEqualTo(1 << CONFIG.boltLevels());
        boolean branched = false;
        for (int s = 0; s < bolt.segmentCount(); s++) {
            if (bolt.depth(s) > 0) {
                branched = true;
                assertThat(bolt.brightness(s)).isLessThan(1f);
            } else {
                assertThat(bolt.brightness(s)).isEqualTo(1f);
            }
        }
        assertThat(branched).isTrue();
        BoltGeometry again = new BoltGeometry(CONFIG.maxBoltPoints());
        again.generate(0, 5000, 0, 300, 0, -200, 42L, CONFIG);
        assertThat(again.pointCount()).isEqualTo(bolt.pointCount());
        for (int i = 0; i < bolt.pointCount(); i++) {
            assertThat(again.x(i)).isEqualTo(bolt.x(i));
            assertThat(again.z(i)).isEqualTo(bolt.z(i));
        }
        again.generate(0, 5000, 0, 300, 0, -200, 43L, CONFIG);
        assertThat(again.x(2)).isNotEqualTo(bolt.x(2));
    }

    @Test
    void boltsStayWithinTheirBuffers() {
        BoltGeometry small = new BoltGeometry(16);
        small.generate(0, 1000, 0, 0, 0, 0, 1L, CONFIG);
        assertThat(small.pointCount()).isLessThanOrEqualTo(16);
        assertThat(small.capacity()).isEqualTo(16);
        BoltGeometry flat = new BoltGeometry(64);
        flat.generate(5, 5, 5, 5, 5, 5, 1L, CONFIG); // zero length: nothing to split
        assertThat(flat.segmentCount()).isEqualTo(1);
        BoltGeometry bolt = new BoltGeometry(CONFIG.maxBoltPoints());
        long[] seed = {1};
        assertAllocationFree(() -> bolt.generate(0, 5000, 0, 300, 0, -200, seed[0]++, CONFIG));
    }

    @Test
    void thunderArrivesAtTheSpeedOfSound() {
        assertThat(Thunder.speedOfSound(20)).isCloseTo(343.2, within(0.1));
        assertThat(Thunder.speedOfSound(0)).isEqualTo(331.3);
        LightningConfig config = CONFIG; // 15 °C: 340.3 m/s, 3 km in 8.8 s
        assertThat(Thunder.delay(3000, config)).isCloseTo(8.82, within(0.01));
        LightningEvents events = new LightningEvents(4);
        events.add(0, FlashType.NEGATIVE_GROUND, 0, 4000, 0, 0, 0, 0, -30, 3, 1, 0);
        assertThat(Thunder.nearest(events, 0, 3000, 0, 0)).isEqualTo(3000);
        assertThat(Thunder.farthest(events, 0, 3000, 0, 0)).isEqualTo(5000);
        assertThat(Thunder.segmentDistance(1, 1, 1, 1, 1, 1, 1, 1, 4)).isEqualTo(3);
        BoltGeometry bolt = new BoltGeometry(512);
        bolt.generate(0, 4000, 0, 0, 0, 0, 7L, config);
        double near = Thunder.nearest(bolt, 3000, 0, 0);
        double far = Thunder.farthest(bolt, 3000, 0, 0);
        assertThat(near).isLessThanOrEqualTo(3000.0 + 1e-6).isGreaterThan(2000.0);
        assertThat(far).isGreaterThanOrEqualTo(4999.0);
    }

    @Test
    void presetsAndValidation() {
        assertThat(LightningConfig.forQuality(Quality.LOW).tier()).isEqualTo(LightningConfig.Tier.FLASHES);
        assertThat(LightningConfig.forQuality(Quality.MEDIUM).boltLevels()).isEqualTo(6);
        assertThat(LightningConfig.forQuality(Quality.HIGH)).isEqualTo(CONFIG);
        assertThat(LightningConfig.forQuality(Quality.ULTRA).boltLevels()).isEqualTo(8);
        assertThat(CONFIG.withTier(LightningConfig.Tier.OFF).enabled()).isFalse();
        assertThat(CONFIG.enabled()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> CONFIG.withBoltLevels(20));
        assertThatIllegalArgumentException().isThrownBy(() -> new LightningEvents(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new LightningConfig(LightningConfig.Tier.FULL, 3, 0.1, 0,
            35, 0.6, 3, 60, 2, 0.4, 120, 0.1, 5, 300, 7, 0.2, 0.2, 0.4, 0.5, 64, 15, 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> new LightningConfig(LightningConfig.Tier.FULL, 3, 2, 30,
            35, 0.6, 3, 60, 2, 0.4, 120, 0.1, 5, 300, 7, 0.2, 0.2, 0.4, 0.5, 64, 15, 1000));
        assertThat(FlashType.NEGATIVE_GROUND.strikesGround()).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> new BoltGeometry(1));
    }
}
