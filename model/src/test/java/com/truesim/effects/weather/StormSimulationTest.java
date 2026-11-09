package com.truesim.effects.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.effects.weather.lightning.LightningEvents;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The storm life cycle: its stages, its flashes, and determinism (bit for bit, pinned by a golden hash). */
class StormSimulationTest {

    private static StormSimulation simulation(long seed, LightningEvents events) {
        return new StormSimulation(StormConfig.defaults(), LightningConfig.defaults(), events, WeatherDomain.flat(),
            seed);
    }

    @Test
    void aCellGrowsMaturesAndDecays() {
        LightningEvents events = new LightningEvents(4096);
        StormSimulation storms = new StormSimulation(StormConfig.defaults().withDaughterRate(0),
            LightningConfig.defaults(), events, WeatherDomain.flat(), 1); // a single cell
        int s = storms.spawn(0, 0, 0);
        assertThat(storms.activeCount()).isEqualTo(1);
        assertThat(storms.stage(s)).isEqualTo(StormSimulation.Stage.GROWING);
        assertThat(storms.cloudType(s)).isEqualTo(CloudType.TOWERING_CUMULUS);
        double top = storms.top(s);
        List<StormSimulation.Stage> seen = new ArrayList<>();
        double maxTop = 0;
        double maxPrecipitation = 0;
        int t = 0;
        while (storms.active(s) && t < 10_000) {
            storms.step(t, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
            if (!storms.active(s)) {
                break;
            }
            if (seen.isEmpty() || seen.getLast() != storms.stage(s)) {
                seen.add(storms.stage(s));
            }
            maxTop = Math.max(maxTop, storms.top(s));
            maxPrecipitation = Math.max(maxPrecipitation, storms.precipitation(s));
            t++;
        }
        assertThat(seen).containsExactly(StormSimulation.Stage.GROWING, StormSimulation.Stage.MATURE,
            StormSimulation.Stage.DISSIPATING);
        assertThat(t).isBetween(2400, 5400); // 40–90 minutes
        assertThat(maxTop).isBetween(8000.0, 14000.0).isGreaterThan(top);
        assertThat(maxPrecipitation).isBetween(0.4, 1.0);
        assertThat(storms.activeCount()).isZero();
        // A mature storm flashes a few times a minute: 30–90 minutes of life give dozens of flashes.
        assertThat(events.nextId()).isBetween(10L, 800L);
        int[] kinds = new int[3];
        events.forEachSince(-1, (e, slot, id) -> {
            kinds[e.type(slot).ordinal()]++;
            if (e.type(slot).strikesGround()) {
                assertThat(e.end(slot, 1)).isCloseTo(0, within(1e-9)); // on the ground
                assertThat(e.origin(slot, 1)).isGreaterThan(2000);
            } else {
                assertThat(e.end(slot, 1)).isGreaterThan(3000);
            }
            assertThat(e.peakCurrent(slot) > 0).isEqualTo(e.type(slot) == FlashType.POSITIVE_GROUND);
            assertThat(e.strokes(slot)).isPositive();
            assertThat(e.cell(slot)).isEqualTo(storms.id(s));
        });
        assertThat(kinds[FlashType.INTRACLOUD.ordinal()]).isGreaterThan(kinds[FlashType.NEGATIVE_GROUND.ordinal()]);
    }

    /** A flat world's ground above y = 0 (a terrain's valley floor): the strikes reach it, the flashes start above it. */
    @Test
    void theFlashesStandOnARaisedGround() {
        WeatherDomain domain = WeatherDomain.flat(800);
        assertThat(domain.altitude(5, 2300, 7)).isEqualTo(1500);
        double[] p = new double[3];
        domain.planeToGround(10, 20, p, 0);
        assertThat(p).containsExactly(10, 800, 20);
        LightningEvents events = new LightningEvents(4096);
        StormSimulation storms = new StormSimulation(StormConfig.defaults().withDaughterRate(0),
            LightningConfig.defaults(), events, domain, 1);
        int s = storms.spawn(0, 800, 0);
        for (int t = 0; storms.active(s); t++) {
            storms.step(t, 1, 0, WindProfile.calm(), 1, 0, 800, 0);
        }
        int[] ground = {0};
        events.forEachSince(-1, (e, slot, id) -> {
            if (e.type(slot).strikesGround()) {
                ground[0]++;
                assertThat(e.end(slot, 1)).isCloseTo(800, within(1e-9));
                assertThat(e.origin(slot, 1)).isGreaterThan(2800);
            } else {
                assertThat(e.end(slot, 1)).isGreaterThan(3800);
            }
        });
        assertThat(ground[0]).isPositive();
    }

    /**
     * Every value a cell shows is continuous in its age: a step of a second changes its cover, rain, flash rate and anvil
     * by at most a few hundredths and its top by tens of metres, at birth, at every stage boundary and at its end
     * (which leaves nothing). Before, its cover started at 0.6, its rain jumped from a half to all of it and its flash
     * rate from three tenths to all of it as it matured, and its anvil stayed whole until it vanished with the cell.
     */
    @Test
    void everyValueOfACellIsContinuousInItsAge() {
        for (long seed = 1; seed <= 5; seed++) {
            StormSimulation storms = new StormSimulation(StormConfig.defaults().withDaughterRate(0),
                LightningConfig.defaults(), new LightningEvents(4096), WeatherDomain.flat(), seed);
            int s = storms.spawn(0, 0, 0);
            double[] last = {0, 0, 0, 0, storms.top(s)};
            double flashes = 0;
            for (int t = 0; storms.active(s); t++) {
                double[] now = {storms.coverage(s), storms.precipitation(s), storms.flashRate(s), storms.anvil(s),
                    storms.top(s)};
                flashes = Math.max(flashes, now[2]);
                assertThat(Math.abs(now[0] - last[0])).as("cover at %d s", t).isLessThan(0.02);
                assertThat(Math.abs(now[1] - last[1])).as("rain at %d s", t).isLessThan(0.02);
                assertThat(Math.abs(now[2] - last[2])).as("flash rate at %d s", t).isLessThan(0.1);
                assertThat(Math.abs(now[3] - last[3])).as("anvil at %d s", t).isLessThan(0.02);
                assertThat(Math.abs(now[4] - last[4])).as("top at %d s", t).isLessThan(40);
                last = now;
                storms.step(t, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
            }
            assertThat(flashes).isPositive();
            assertThat(last[0]).as("nothing left of its cover when it ends").isLessThan(0.02);
            assertThat(last[1]).as("nor of its rain").isLessThan(0.02);
            assertThat(last[3]).as("nor of its anvil").isLessThan(0.02);
        }
    }

    /** A cell's top at any age, as it will be (for renderers drawing towers of its stages). */
    @Test
    void theTopAtAnAgeIsTheTopAtThatAge() {
        StormSimulation storms = simulation(4, new LightningEvents(256));
        int s = storms.spawn(0, 0, 0);
        double[] tops = new double[6000];
        for (int t = 0; t < tops.length && storms.active(s); t++) {
            tops[t] = storms.top(s);
            assertThat(storms.topAt(s, storms.age(s))).isEqualTo(tops[t]);
            storms.step(t, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
        }
        assertThat(storms.topAt(s, -10)).isEqualTo(storms.baseAltitude());
    }

    @Test
    void cellsDriftWithTheSteeringWindAndAnvilsLeadDownwind() {
        StormSimulation storms = simulation(2, new LightningEvents(64));
        int s = storms.spawn(0, 0, 0);
        WindProfile westerly = new WindProfile(new WindProfile.Layer(0, 10, 270, 0));
        for (int t = 0; t < 1500; t++) {
            storms.step(t, 1, 0, westerly, 1, 0, 0, 0);
        }
        assertThat(storms.position(s, 0)).isCloseTo(15_000, within(1.0));
        assertThat(storms.position(s, 2)).isCloseTo(0, within(1e-6));
        assertThat(storms.anvilPosition(s, 0)).isGreaterThan(storms.position(s, 0));
        assertThat(storms.age(s)).isEqualTo(1500);
        assertThat(storms.flashRate(s)).isPositive();
        assertThat(storms.anvil(s)).isPositive();
        assertThat(storms.coverage(s)).isPositive();
        // Cells far from the focus are dropped.
        storms.step(1500, 1, 0, westerly, 1, 1e7, 0, 0);
        assertThat(storms.activeCount()).isZero();
    }

    @Test
    void stormsFormAtTheirRateUpToTheCapacity() {
        StormSimulation storms = new StormSimulation(StormConfig.defaults().withMaxCells(4), LightningConfig.defaults()
            .withTier(LightningConfig.Tier.OFF), new LightningEvents(16), WeatherDomain.flat(), 3);
        // 1000 cells per 10,000 km² per hour over π·60² km² for an hour: far more than four.
        for (int t = 0; t < 3600; t += 10) {
            storms.step(t, 10, 1000, WindProfile.calm(), 1, 0, 0, 0);
        }
        assertThat(storms.activeCount()).isEqualTo(4);
        assertThat(storms.spawn(0, 0, 0)).isEqualTo(-1);
        assertThat(storms.capacity()).isEqualTo(4);
    }

    /** Runs a storm scenario and returns the state hash combined with every flash's values. */
    private static long scenario(long seed) {
        LightningEvents events = new LightningEvents(1 << 14);
        StormSimulation storms = simulation(seed, events);
        WindProfile wind = WindProfile.westerlies();
        for (int t = 0; t < 7200; t++) {
            storms.step(t, 1, 3.0, wind, 1.2, 0, 0, 0);
        }
        long[] h = {storms.stateHash()};
        events.forEachSince(-1, (e, slot, id) -> {
            h[0] = h[0] * 31 + Double.doubleToLongBits(e.time(slot));
            h[0] = h[0] * 31 + Double.doubleToLongBits(e.origin(slot, 0));
            h[0] = h[0] * 31 + Double.doubleToLongBits(e.end(slot, 2));
            h[0] = h[0] * 31 + Float.floatToIntBits(e.peakCurrent(slot));
            h[0] = h[0] * 31 + e.seed(slot) + e.strokes(slot) + e.type(slot).ordinal();
        });
        return h[0] * 31 + events.nextId();
    }

    @Test
    void theSimulationIsDeterministicOnEveryMachine() {
        long first = scenario(42);
        assertThat(scenario(42)).isEqualTo(first);
        assertThat(scenario(43)).isNotEqualTo(first);
        // Pinned: a different machine, JIT or JDK computing anything differently fails here (CI runs on Linux).
        assertThat(first).isEqualTo(GOLDEN);
    }

    // Changed with the continuous life cycle (rain, flashes, cover and anvil without steps; daughters on the flank).
    private static final long GOLDEN = -3028369275414425372L;

    @Test
    void matureCellsGrowDaughters() {
        int multicell = 0;
        for (long seed = 1; seed <= 10; seed++) {
            StormSimulation storms = simulation(seed, new LightningEvents(256));
            storms.spawn(0, 0, 0);
            int most = 0;
            for (int t = 0; t < 8000; t++) {
                storms.step(t, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
                most = Math.max(most, storms.activeCount());
            }
            assertThat(most).isLessThanOrEqualTo(12); // a handful of cells, not a cascade
            multicell += most > 1 ? 1 : 0;
        }
        assertThat(multicell).isGreaterThanOrEqualTo(4); // most single cells seed at least one daughter
    }
}
