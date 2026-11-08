package com.truesim.effects.weather;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.atmosphere.ephemeris.Planet;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.scene.core.SceneGraph;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Presets, wind, brushes, the field, the maps, both domains, components. */
class WeatherTest {

    /** Small maps, so tests run quickly. */
    static WeatherConfig small(WeatherConfig.Simulation tier) {
        return WeatherConfig.forQuality(Quality.HIGH).withSimulation(tier).withMaps(32, 32_000, 16, 400_000, 8, 8, 64);
    }

    @Test
    void presetsBlendSmoothlyAndTypesSwitchHalfway() {
        WeatherState state = new WeatherState(WeatherPreset.CLEAR);
        assertThat(state.progress()).isEqualTo(1.0);
        state.transitionTo(WeatherPreset.OVERCAST, 100);
        state.advance(25);
        assertThat(state.type()).isEqualTo(CloudType.CUMULUS.channelValue());
        state.advance(25);
        assertThat(state.coverage()).isCloseTo(0.475, within(1e-9));
        assertThat(state.type()).isEqualTo(CloudType.STRATUS.channelValue());
        double before = state.coverage();
        state.transitionTo(WeatherPreset.CLEAR, 100); // interrupted: starts where it was
        assertThat(state.coverage()).isEqualTo(before);
        assertThat(state.from().name()).isEqualTo("transition");
        state.advance(1000);
        assertThat(state.coverage()).isZero();
        assertThat(state.target()).isEqualTo(WeatherPreset.CLEAR);
        state.transitionTo(WeatherPreset.STORM, 0);
        assertThat(state.stormRate()).isEqualTo(2.0);
        assertThat(state.windScale()).isEqualTo(1.5);
        assertThat(state.cirrus()).isEqualTo(0.3);
        assertThat(state.noiseScale()).isEqualTo(10000);
        assertThat(state.variation()).isEqualTo(0.4);
        assertThat(state.topHeight()).isZero();
        assertThat(state.precipitation()).isEqualTo(0.1);
        assertThat(WeatherPreset.builtIn("rain")).isEqualTo(WeatherPreset.RAIN);
        assertThat(WeatherPreset.builtIn("snow")).isNull();
        assertThatIllegalArgumentException().isThrownBy(() -> state.transitionTo(WeatherPreset.FAIR, -1));
    }

    @Test
    void theWindBlowsFromItsDirectionAndInterpolates() {
        WindProfile wind = new WindProfile(new WindProfile.Layer(1000, 10, 270, 0), new WindProfile.Layer(0, 0, 0, 0));
        assertThat(wind.layer(0).altitude()).isZero();
        assertThat(wind.layerCount()).isEqualTo(2);
        assertThat(wind.east(1000)).isCloseTo(10, within(1e-9)); // a westerly blows east
        assertThat(wind.north(1000)).isCloseTo(0, within(1e-9));
        assertThat(wind.east(500)).isCloseTo(5, within(1e-9));
        assertThat(wind.east(5000)).isCloseTo(10, within(1e-9));
        assertThat(wind.east(-100)).isZero();
        assertThat(new WindProfile.Layer(0, 5, 0, 0).north()).isCloseTo(-5, within(1e-9)); // a northerly blows south
        assertThat(WindProfile.calm().east(3000)).isZero();
        assertThat(WindProfile.westerlies().east(10000)).isGreaterThan(30);
        assertThatIllegalArgumentException().isThrownBy(WindProfile::new);
    }

    @Test
    void brushesWeighAgeAndDrift() {
        WeatherBrush brush = WeatherBrush.set(1000, CloudType.CUMULUS, 1, 0);
        assertThat(brush.weight(0)).isEqualTo(1);
        assertThat(brush.weight(700)).isEqualTo(1);
        assertThat(brush.weight(850)).isCloseTo(0.5, within(1e-9));
        assertThat(brush.weight(1000)).isZero();
        Brushes brushes = new Brushes(2);
        WeatherDomain flat = WeatherDomain.flat();
        int a = brushes.add(brush.withVelocity(10, 0).withLifetime(5), 0, 0, 0);
        int b = brushes.add(WeatherBrush.clear(500), 100, 0, 100);
        assertThat(brushes.add(brush, 0, 0, 0)).isEqualTo(-1);
        brushes.step(1, flat);
        assertThat(brushes.position(0, 0)).isCloseTo(10, within(1e-9));
        brushes.step(4, flat);
        assertThat(brushes.count()).isEqualTo(1); // expired
        assertThat(brushes.id(0)).isEqualTo(b);
        assertThat(brushes.remove(a)).isFalse();
        assertThat(brushes.remove(b)).isTrue();
        assertThat(brushes.count()).isZero();
        assertThat(brushes.capacity()).isEqualTo(2);
        assertThatIllegalArgumentException().isThrownBy(() -> brush.withLifetime(0));
    }

    @Test
    void nodeBrushesComeFromTheScene() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        long node = scene.create();
        scene.setTranslation(node, 500, 300, -200);
        scene.components().set(node, WeatherComponents.BRUSH, WeatherBrush.clear(2000));
        scene.update();
        Brushes brushes = new Brushes(4);
        brushes.add(WeatherBrush.set(100, CloudType.STRATUS, 1, 0), 0, 0, 0);
        WeatherDomain flat = WeatherDomain.flat();
        brushes.syncFromScene(scene, flat);
        brushes.syncFromScene(scene, flat); // again: replaced, not added twice
        assertThat(brushes.count()).isEqualTo(2);
        assertThat(brushes.node(1)).isEqualTo(node);
        assertThat(brushes.node(0)).isZero();
        assertThat(brushes.position(1, 0)).isEqualTo(500);
        assertThat(brushes.position(1, 1)).isZero(); // on the ground
        brushes.step(1e6, flat); // node brushes never expire
        assertThat(brushes.count()).isEqualTo(2);
        Brushes none = new Brushes(1);
        none.syncFromScene(new SceneGraph(new ChangeClock()), flat);
        assertThat(none.count()).isZero();
        WeatherComponents.Settings settings = new WeatherComponents.Settings(WeatherConfig.forQuality(Quality.LOW),
            WeatherPreset.FAIR);
        scene.components().set(node, WeatherComponents.WEATHER, settings);
        assertThat(scene.components().get(node, WeatherComponents.WEATHER)).isEqualTo(settings);
    }

    @Test
    void theFieldCombinesPresetStormsAndBrushes() {
        Weather weather = new Weather(small(WeatherConfig.Simulation.STORMS), WeatherDomain.flat(),
            WeatherPreset.CLEAR);
        WeatherSample s = new WeatherSample();
        weather.field().evaluate(0, 0, 0, s);
        assertThat(s.coverage()).isZero();
        weather.addBrush(WeatherBrush.set(3000, CloudType.STRATOCUMULUS, 0.8, 0.2), 10_000, 50, 0);
        weather.field().evaluate(10_000, 0, 0, s);
        assertThat(s.coverage()).isCloseTo(0.8f, within(1e-6f));
        assertThat(s.cloudType()).isEqualTo(CloudType.STRATOCUMULUS);
        assertThat(s.precipitation()).isCloseTo(0.2f, within(1e-6f));
        assertThat(s.precipitationRate(100)).isCloseTo(20, within(1e-4));
        int cell = weather.spawnStorm(-20_000, 0, 0);
        StormSimulation storms = weather.storms();
        for (int i = 0; i < 1500; i++) {
            storms.step(i, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
        }
        assertThat(storms.stage(cell)).isEqualTo(StormSimulation.Stage.MATURE);
        weather.field().evaluate(-20_000, 0, 0, s);
        assertThat(s.coverage()).isEqualTo(1f);
        assertThat(s.cloudType()).isEqualTo(CloudType.CUMULONIMBUS);
        assertThat(s.topHeight()).isEqualTo((float) storms.top(cell));
        assertThat(s.precipitation()).isPositive();
        weather.addBrush(WeatherBrush.clear(3000), -20_000, 0, 0);
        weather.field().evaluate(-20_000, 0, 0, s);
        assertThat(s.coverage()).isZero();
        Weather overcast = new Weather(small(WeatherConfig.Simulation.PRESETS), WeatherDomain.flat(),
            WeatherPreset.OVERCAST);
        overcast.addBrush(new WeatherBrush(WeatherBrush.Mode.ADD, 1000, 0, 1, 0.5, CloudType.NIMBOSTRATUS, 0.9, 3000, 0,
            0, Double.POSITIVE_INFINITY), 0, 0, 0);
        overcast.field().evaluate(0, 0, 0, s);
        assertThat(s.coverage()).isEqualTo(1f);
        assertThat(s.topHeight()).isEqualTo(3000f);
        assertThat(s.cloudType()).isEqualTo(CloudType.NIMBOSTRATUS);
        assertThat(s.precipitation()).isLessThanOrEqualTo(s.coverage());
    }

    @Test
    void theNearMapHoldsTheFieldAndFollowsTheFocus() {
        Weather weather = new Weather(small(WeatherConfig.Simulation.PRESETS), WeatherDomain.flat(),
            WeatherPreset.SCATTERED);
        WeatherGrid near = weather.nearMap();
        WeatherSample fromMap = new WeatherSample();
        WeatherSample direct = new WeatherSample();
        assertThat(near.sample(0, 0, fromMap)).isFalse();
        weather.step();
        assertThat(near.version()).isEqualTo(2 * near.tilesPerEdge() * near.tilesPerEdge()); // new ones, then each again
        assertThat(near.tileVersion(0, 0)).isPositive();
        // At a texel centre the map is the field (the wind has moved it by the same step).
        double centre = 0.5 * near.texelSize();
        assertThat(near.sample(centre, centre, fromMap)).isTrue();
        weather.field().evaluate(centre, 0, centre, direct);
        assertThat(fromMap.coverage()).isCloseTo(direct.coverage(), within(1e-6f));
        weather.sample(centre, 500, centre, fromMap);
        assertThat(fromMap.coverage()).isCloseTo(direct.coverage(), within(1e-6f));
        // Far outside both maps: evaluated directly.
        weather.sample(5e6, 0, 5e6, fromMap);
        weather.field().evaluate(5e6, 0, 5e6, direct);
        assertThat(fromMap.coverage()).isEqualTo(direct.coverage());
        // Moving the focus brings new tiles; they are filled at the next step.
        weather.setFocus(100_000, 0, 0);
        weather.step();
        assertThat(near.centreTileX()).isEqualTo((int) Math.floor(100_000 / near.texelSize()) / near.tileTexels());
        assertThat(near.sample(100_000, 0, fromMap)).isTrue();
        assertThat(near.data()).hasSize(4 * 32 * 32);
        assertThat(near.size()).isEqualTo(32);
        assertThat(weather.farMap().size()).isEqualTo(16);
        assertThat(weather.globalMap()).isNull();
        near.invalidate();
        assertThat(near.sample(100_000, 0, fromMap)).isFalse();
        weather.refreshAll();
        assertThat(near.sample(100_000, 0, fromMap)).isTrue();
        assertThatIllegalArgumentException().isThrownBy(() -> new WeatherGrid(30, 1000, 8));
    }

    @Test
    void windCarriesThePattern() {
        Weather weather = new Weather(small(WeatherConfig.Simulation.PRESETS).withWind(new WindProfile(
            new WindProfile.Layer(0, 10, 270, 0))), WeatherDomain.flat(), WeatherPreset.SCATTERED);
        WeatherSample before = new WeatherSample();
        WeatherSample after = new WeatherSample();
        weather.step();
        weather.field().evaluate(0, 0, 0, before);
        assertThat(weather.advance(100.5)).isEqualTo(100);
        assertThat(weather.driftEast()).isCloseTo(101 * 10 * 1.0, within(1e-6)); // scattered: wind scale 1
        assertThat(weather.driftNorth()).isCloseTo(0, within(1e-9));
        weather.field().evaluate(1010, 0, 0, after);
        assertThat(after.coverage()).isCloseTo(before.coverage(), within(1e-5f)); // moved 1 km east
        Weather still = new Weather(small(WeatherConfig.Simulation.STATIC), WeatherDomain.flat(), WeatherPreset.FAIR);
        still.setPreset(WeatherPreset.RAIN, 600);
        assertThat(still.state().progress()).isEqualTo(1.0); // static: at once
        still.step();
        assertThat(still.driftEast()).isZero();
        assertThat(still.storms()).isNull();
        assertThat(still.spawnStorm(0, 0, 0)).isEqualTo(-1);
    }

    @Test
    void onAPlanetTheWeatherIsGlobal() {
        Planet planet = Planet.EARTH;
        WeatherDomain sphere = WeatherDomain.sphere(planet);
        double r = planet.equatorialRadius();
        double[] f = new double[9];
        sphere.frame(r, 0, 0, f); // on the equator at +x
        assertThat(f[0] * f[0] + f[1] * f[1] + f[2] * f[2]).isCloseTo(1, within(1e-12));
        assertThat(f[0] * f[3] + f[1] * f[4] + f[2] * f[5]).isCloseTo(0, within(1e-12));
        assertThat(f[4]).isCloseTo(1, within(1e-12)); // north is +y at the equator
        double[] p = {r, 0, 0};
        sphere.move(p, 0, 0, 1000, f);
        assertThat(Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2])).isCloseTo(r, within(1e-6));
        assertThat(p[1]).isCloseTo(1000, within(0.01));
        assertThat(sphere.altitude(r + 500, 0, 0)).isCloseTo(500, within(1e-6));
        double[] ground = new double[3];
        double[] plane = new double[2];
        sphere.reanchor(0, r, 0);
        sphere.planeToGround(3000, -4000, ground, 0);
        assertThat(sphere.groundToPlane(ground[0], ground[1], ground[2], plane, 0)).isTrue();
        assertThat(plane[0]).isCloseTo(3000, within(1e-6));
        assertThat(plane[1]).isCloseTo(-4000, within(1e-6));
        assertThat(sphere.groundToPlane(0, -r, 0, plane, 0)).isFalse(); // the far side
        assertThat(sphere.anchor(1)).isEqualTo(1);
        sphere.frame(0, r, 0, f); // at the pole: east is +x by convention
        assertThat(f[0]).isEqualTo(1);

        Weather weather = new Weather(small(WeatherConfig.Simulation.STORMS).withMaps(16, 32_000, 16, 400_000, 4, 4,
            64), sphere, WeatherPreset.BROKEN);
        GlobalWeatherGrid global = weather.globalMap();
        assertThat(weather.farMap()).isNull();
        for (int i = 0; i < 2; i++) {
            weather.step(); // 16 global tiles a step: 6 faces of 2×2 tiles fill at once
        }
        assertThat(global.tileVersion(5)).isPositive();
        WeatherSample fromMap = new WeatherSample();
        WeatherSample direct = new WeatherSample();
        // At a texel centre of face 0 (+x): u = v = −1 + 1/faceSize.
        double u = -1 + 1.0 / global.faceSize();
        double len = Math.sqrt(1 + 2 * u * u);
        assertThat(global.sample(1 / len, u / len, u / len, fromMap)).isTrue();
        weather.field().evaluate(r / len, r * u / len, r * u / len, direct);
        assertThat(fromMap.coverage()).isCloseTo(direct.coverage(), within(1e-5f));
        assertThat(global.sample(0, 0, 0, fromMap)).isFalse();
        weather.refreshAll();
        assertThat(global.data()).hasSize(6 * 4 * 4 * 4);
        assertThat(global.tilesPerEdge()).isEqualTo(1);
        // Far from the anchor the near map re-anchors at the focus.
        weather.setFocus(r, 0, 0);
        assertThat(sphere.anchor(0)).isCloseTo(1, within(1e-12));
        weather.sample(r, 0, 0, fromMap); // near map empty again: from the global map
        assertThat(fromMap.coverage()).isBetween(0f, 1f);
        weather.sample(-r, 0, 0, fromMap); // the far side: global map
        assertThat(fromMap.coverage()).isBetween(0f, 1f);
        assertThat(weather.domain().isSphere()).isTrue();
        assertThat(WeatherDomain.flat().radius()).isInfinite();
    }

    @Test
    void everyCubeFaceMapsAsDocumented() {
        Planet planet = Planet.EARTH;
        WeatherDomain sphere = WeatherDomain.sphere(planet);
        Weather weather = new Weather(small(WeatherConfig.Simulation.STATIC).withMaps(16, 32_000, 16, 400_000, 8, 4,
            64), sphere, WeatherPreset.SCATTERED); // static: the field does not move between the two steps
        weather.step(); // 16 of the 24 global tiles
        weather.step(); // the other 8, then 8 refreshed again
        GlobalWeatherGrid global = weather.globalMap();
        WeatherSample fromMap = new WeatherSample();
        WeatherSample direct = new WeatherSample();
        double r = planet.equatorialRadius();
        int n = global.faceSize();
        for (int face = 0; face < 6; face++) {
            int a = face / 2;
            double sign = face % 2 == 0 ? 1 : -1;
            for (int j = 0; j < n; j += 3) {
                for (int i = 0; i < n; i += 3) {
                    double u = -1 + 2 * (i + 0.5) / n;
                    double v = -1 + 2 * (j + 0.5) / n;
                    double[] d = new double[3];
                    d[a] = sign;
                    d[(a + 1) % 3] = u;
                    d[(a + 2) % 3] = v;
                    double len = Math.sqrt(1 + u * u + v * v);
                    assertThat(global.sample(d[0], d[1], d[2], fromMap)).isTrue();
                    weather.field().evaluate(d[0] * r / len, d[1] * r / len, d[2] * r / len, direct);
                    assertThat(fromMap.coverage()).isCloseTo(direct.coverage(), within(1e-5f));
                    assertThat(fromMap.type()).isEqualTo(direct.type());
                }
            }
        }
        assertThat(global.version()).isEqualTo(32);
        GlobalWeatherGrid empty = new GlobalWeatherGrid(8, 4, 1000);
        assertThat(empty.sample(0, 0, 1, fromMap)).isFalse();
        assertThat(empty.sample(0, -1, 0, fromMap)).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new GlobalWeatherGrid(10, 4, 1000));
    }

    @Test
    void steppingAndSamplingAllocateNothing() {
        // Tiny maps: the measurement repeats the step until the JIT has compiled it.
        Weather weather = new Weather(small(WeatherConfig.Simulation.STORMS).withMaps(16, 16_000, 8, 400_000, 4, 4, 1),
            WeatherDomain.flat(),
            WeatherPreset.STORM);
        weather.spawnStorm(1000, 0, 0);
        weather.addBrush(WeatherBrush.clear(1000).withVelocity(1, 1), 0, 0, 0);
        for (int i = 0; i < 100; i++) {
            weather.step();
        }
        WeatherSample s = new WeatherSample();
        assertAllocationFree(() -> {
            weather.step();
            weather.sample(500, 0, 500, s);
        });
    }

    @Test
    void presetsMapToTiers() {
        assertThat(WeatherConfig.forQuality(Quality.LOW).simulation()).isEqualTo(WeatherConfig.Simulation.PRESETS);
        assertThat(WeatherConfig.forQuality(Quality.MEDIUM).nearSize()).isEqualTo(256);
        assertThat(WeatherConfig.forQuality(Quality.HIGH).tilesPerStep()).isEqualTo(4);
        assertThat(WeatherConfig.forQuality(Quality.HIGH).nearSize()).isEqualTo(512);
        assertThat(WeatherConfig.forQuality(Quality.ULTRA).nearExtent()).isEqualTo(150_000);
        assertThat(WeatherConfig.forQuality(Quality.HIGH).withSeed(9).seed()).isEqualTo(9);
        assertThat(WeatherConfig.forQuality(Quality.HIGH).withLightning(LightningConfig.defaults()).lightning())
            .isEqualTo(LightningConfig.defaults());
        assertThat(StormConfig.forQuality(Quality.LOW).maxCells()).isEqualTo(8);
        assertThat(StormConfig.forQuality(Quality.MEDIUM).maxCells()).isEqualTo(24);
        assertThat(StormConfig.forQuality(Quality.ULTRA).maxCells()).isEqualTo(128);
        assertThatIllegalArgumentException().isThrownBy(() -> StormConfig.defaults().withRateScale(-1));
        assertThatIllegalArgumentException().isThrownBy(() -> new WeatherPreset("x", 2, 0, CloudType.CUMULUS, 0, 0, 1,
            0, 1, 0));
        assertThat(FlashType.values()).hasSize(3);
        List<Object> sink = new ArrayList<>();
        sink.add(WeatherComponents.BRUSH.name());
        assertThat(sink).containsExactly("truesim.weather.brush");
    }

    @Test
    void theFieldTellsItsInputs() {
        Weather weather = new Weather(WeatherConfig.forQuality(Quality.LOW), WeatherDomain.flat(), WeatherPreset.FAIR);
        for (int i = 0; i < 10; i++) {
            weather.step();
        }
        WeatherField field = weather.field();
        assertThat(field.state()).isSameAs(weather.state());
        assertThat(field.offset(0)).isCloseTo(weather.driftEast(), within(1e-9));
        assertThat(field.offset(1)).isZero();
        assertThat(field.offset(2)).isCloseTo(-weather.driftNorth(), within(1e-9));
        assertThat(field.octaves()).isEqualTo(weather.config().noiseOctaves());
        assertThat(field.seed()).isEqualTo((int) weather.config().seed());
        assertThat(WeatherField.noisePeriod()).isEqualTo(1 << 16);
    }
}
