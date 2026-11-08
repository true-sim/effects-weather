package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.truesim.common.state.ChangeClock;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.effects.weather.lightning.LightningEvents;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import org.junit.jupiter.api.Test;

/** The CPU side of the weather's rendering: the flashes lit in a frame, the sky from the scene, the settings. */
class WeatherRenderTest {

    @Test
    void theFlashesOfAFrameAreTheBrightestStillLit() {
        LightningConfig config = LightningConfig.defaults();
        LightningEvents events = new LightningEvents(16);
        events.add(1.0, FlashType.NEGATIVE_GROUND, 0, 3000, 0, 0, 0, 0, -30, 3, 1, 0);   // over long before
        events.add(9.99, FlashType.INTRACLOUD, 0, 5000, 0, 2000, 5000, 0, -10, 1, 2, 0); // weak
        events.add(9.995, FlashType.NEGATIVE_GROUND, 0, 3000, 0, 0, 0, 0, -60, 1, 3, 0); // strong
        events.add(9.998, FlashType.POSITIVE_GROUND, 0, 3000, 0, 0, 0, 0, 35, 1, 4, 0);  // median positive
        events.add(20.0, FlashType.NEGATIVE_GROUND, 0, 3000, 0, 0, 0, 0, -30, 1, 5, 0);  // not yet
        ActiveFlashes flashes = new ActiveFlashes(8).select(events, config, 10 - 1.0 / 60, 10, 1e9, 8);
        assertThat(flashes.count()).isEqualTo(3);
        for (int i = 1; i < flashes.count(); i++) {
            assertThat(flashes.intensity(i)).isLessThanOrEqualTo(flashes.intensity(i - 1));
        }
        assertThat(events.seed(flashes.slot(0))).as("the strongest first").isEqualTo(3);
        assertThat(flashes.age(0)).isCloseTo(0.005, within(1e-9));
        assertThat(flashes.total()).isGreaterThan(flashes.intensity(0));
        ActiveFlashes two = new ActiveFlashes(2).select(events, config, 10 - 1.0 / 60, 10, 1e9, 8);
        assertThat(two.count()).as("at most its capacity: the brightest").isEqualTo(2);
        assertThat(two.intensity(0)).isEqualTo(flashes.intensity(0));
        assertThat(two.intensity(1)).isEqualTo(flashes.intensity(1));
        assertThat(new ActiveFlashes(4).select(events, config, 10 - 1.0 / 60, 10, 1e9, 0).count()).isZero();
        assertThat(new ActiveFlashes(4).select(events, config, 100, 101, 1e9, 4).count()).as("all over").isZero();
    }

    @Test
    void theSkyFollowsTheScenesSun() {
        SceneGraph scene = new SceneGraph(new ChangeClock());
        SceneSky sky = new SceneSky(scene, CloudLighting.Atmosphere.EARTH);
        double[] direction = new double[3];
        double[] light = new double[3];
        sky.sun(direction, light);
        assertThat(light).as("no sun: night").containsExactly(0, 0, 0);
        long sun = scene.create();
        scene.components().set(sun, LightSource.TYPE, LightSource.directional(1, 1, 1, 100_000));
        // +Z turned up 30 degrees towards the sun: about x by -30 degrees.
        double half = Math.toRadians(-30) / 2;
        scene.setRotation(sun, Math.sin(half), 0, 0, Math.cos(half));
        scene.update();
        sky.sun(sun);
        sky.sun(direction, light);
        assertThat(direction[1]).isCloseTo(Math.sin(Math.toRadians(30)), within(1e-9));
        assertThat(light[2]).as("outside the atmosphere: more than at the ground, blue the most").isGreaterThan(
            light[0]).isGreaterThan(100_000);
        double[] skyLight = new double[3];
        sky.sky(skyLight);
        assertThat(skyLight[2]).as("the sky is blue").isGreaterThan(skyLight[0]).isGreaterThan(1000);
        double[] horizon = new double[3];
        sky.horizon(horizon);
        assertThat(horizon[0]).isGreaterThan(0);
        assertThat(sky.atmosphere()).isSameAs(CloudLighting.Atmosphere.EARTH);
        sky.sun(NodeHandle.NONE);
        sky.sun(direction, light);
        assertThat(light[1]).isZero();
    }

    @Test
    void theAtmosphereDimsALowSunMore() {
        CloudLighting.Atmosphere earth = CloudLighting.Atmosphere.EARTH;
        double high = earth.transmittance(1, 0, 1);
        double low = earth.transmittance(1, 0, 0.1);
        double below = earth.transmittance(1, 0, -0.05);
        assertThat(high).isGreaterThan(low).isGreaterThan(below);
        assertThat(earth.transmittance(2, 0, 1)).as("blue scatters most").isLessThan(earth.transmittance(0, 0, 1));
        assertThat(earth.transmittance(1, 10_000, 0.1)).as("less air above").isGreaterThan(low);
        // At the zenith the optical depth is the column: beta H.
        double column = earth.rayleighGreen() * earth.rayleighHeight() + earth.mieExtinction() * earth.mieHeight();
        assertThat(high).isCloseTo(Math.exp(-column), within(5e-3));
    }

    @Test
    void qualityPicksTheTier() {
        EngineSettings settings = new EngineSettings();
        CloudSettings.forQuality(settings, Quality.LOW);
        assertThat(Clouds.SLOT.chosen(settings)).isSameAs(Clouds.SKYBOX);
        assertThat(settings.get(CloudSettings.OCTAVES)).isEqualTo(1);
        CloudSettings.forQuality(settings, Quality.HIGH);
        assertThat(Clouds.SLOT.chosen(settings)).isSameAs(Clouds.LAYERED);
        CloudSettings.forQuality(settings, Quality.ULTRA);
        assertThat(Clouds.SLOT.chosen(settings)).isSameAs(Clouds.VOLUMETRIC);
        assertThat(settings.get(CloudSettings.OCTAVES)).isEqualTo(3);
        assertThat(Clouds.VOLUMETRIC.config(settings).near()).as("fly-through: fine from the camera")
            .isGreaterThan(0);
        assertThat(Clouds.LAYERED.config(settings).near()).isZero();
        assertThat(CloudSettings.ALL).contains(CloudSettings.SHADOW_SIZE, CloudSettings.FLASH_INTENSITY);
    }
}
