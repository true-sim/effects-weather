package com.truesim.effects.weather.clouds;

import static com.truesim.testing.AllocationAssertions.assertAllocationFree;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import com.truesim.effects.atmosphere.Quality;
import org.junit.jupiter.api.Test;

/** Cloud types, profiles and art overrides; the reference noises' definitions, tiling and fingerprints. */
class CloudsTest {

    @Test
    void typesRoundTripThroughTheWeatherMapChannel() {
        for (CloudType type : CloudType.values()) {
            assertThat(CloudType.fromChannel(type.channelValue())).isEqualTo(type);
            assertThat(CloudType.of(type.ordinal())).isEqualTo(type);
        }
        assertThat(CloudType.fromChannel(-1)).isEqualTo(CloudType.STRATUS);
        assertThat(CloudType.fromChannel(2)).isEqualTo(CloudType.CIRRUS);
        assertThat(CloudType.count()).isEqualTo(9);
    }

    @Test
    void profilesHaveTheirShapes() {
        CloudTypes types = CloudTypes.physical();
        CloudProfile cumulus = types.profile(CloudType.CUMULUS);
        // A flat base: full density 5 % into the layer; a rounded top.
        assertThat(cumulus.density(0)).isZero();
        assertThat(cumulus.density(1)).isZero();
        assertThat(cumulus.density(-0.1)).isZero();
        assertThat(cumulus.density(1.1)).isZero();
        assertThat(cumulus.density(0.05)).isGreaterThan(0.8);
        assertThat(cumulus.density(0.9)).isLessThan(cumulus.density(0.5));
        assertThat(cumulus.anvil(0.9)).isZero();
        CloudProfile cb = types.profile(CloudType.CUMULONIMBUS);
        assertThat(cb.anvil(0.7)).isZero();
        assertThat(cb.anvil(1.0)).isEqualTo(0.6); // the tower's top spreads a little; the anvil is drawn on its own
        assertThat(cb.thickness()).isEqualTo(11200);
        CloudProfile stratus = types.profile(CloudType.STRATUS);
        assertThat(stratus.density(0.3)).isGreaterThan(stratus.density(0.6)); // denser at the base
        assertThat(stratus.heightFraction(350)).isCloseTo(0.5, within(1e-12));
        assertThat(stratus.heightFraction(0)).isZero();
        assertThat(stratus.heightFraction(1e6)).isEqualTo(1);
        // σ = 3·LWC / (2ρr): 0.5 g/m³ of 10 µm radius droplets is 0.075 /m (a 1 km cumulus: optical depth ≈ 75).
        assertThat(cumulus.extinction()).isCloseTo(0.075, within(1e-9));
        assertThat(cumulus.extinctionOfWaterContent(0.5e-3)).isCloseTo(0.075, within(1e-9));
        assertThat(types.profile(CloudType.CIRRUS).ice()).isTrue();
        assertThat(cumulus.withLayer(1000, 3000).thickness()).isEqualTo(2000);
        assertThat(cumulus.withExtinction(0.1).extinction()).isEqualTo(0.1);
        assertThatIllegalArgumentException().isThrownBy(() -> cumulus.withLayer(3000, 1000));
        assertThatIllegalArgumentException().isThrownBy(() -> cumulus.withExtinction(-1));
    }

    @Test
    void theAtlasIsBakedFromTheProfiles() {
        CloudTypes types = CloudTypes.physical();
        int heights = 16;
        float[] atlas = new float[4 * CloudType.count() * heights];
        types.bakeProfileAtlas(heights, atlas);
        int row = CloudType.CUMULONIMBUS.ordinal();
        int last = 4 * (row * heights + heights - 1);
        CloudProfile cb = types.profile(CloudType.CUMULONIMBUS);
        assertThat(atlas[last]).isEqualTo((float) cb.density((heights - 0.5) / heights));
        assertThat(atlas[last + 1]).isEqualTo((float) cb.anvil((heights - 0.5) / heights));
        assertThat(atlas[last + 3]).isEqualTo(0.3f);
        float[] layers = new float[4 * CloudType.count()];
        types.bakeLayers(layers);
        assertThat(layers[4 * row + 1]).isEqualTo(12000f);
    }

    @Test
    void artOverridesAreOffByDefault() {
        CloudTypes types = CloudTypes.physical();
        assertThat(types.art()).isEqualTo(CloudArt.OFF);
        CloudArt art = new CloudArt(false, 2, 2, 2, 0.5, 2, 0.3);
        assertThat(art.extinction(0.1)).isEqualTo(0.1);
        assertThat(art.erosion(0.6)).isEqualTo(0.6);
        assertThat(art.coverage(0.5)).isEqualTo(0.5);
        assertThat(art.powderInEffect()).isZero();
        CloudArt on = new CloudArt(true, 2, 2, 2, 0.5, 2, 0.3);
        assertThat(on.extinction(0.1)).isEqualTo(0.2);
        assertThat(on.erosion(0.6)).isEqualTo(1.0);
        assertThat(on.coverage(0.9)).isEqualTo(1.0);
        assertThat(on.powderInEffect()).isEqualTo(0.5);
        CloudTypes styled = types.with(on);
        float[] layers = new float[4 * CloudType.count()];
        styled.bakeLayers(layers);
        assertThat(layers[4 * CloudType.CUMULUS.ordinal() + 2]).isCloseTo(0.15f, within(1e-6f));
        CloudTypes changed = types.with(CloudType.STRATUS, types.profile(CloudType.STRATUS).withLayer(0, 300));
        assertThat(changed).isNotEqualTo(types);
        assertThat(CloudTypes.physical()).isEqualTo(types).hasSameHashCodeAs(types);
        assertThat(types).isNotEqualTo("clouds");
        assertThatIllegalArgumentException().isThrownBy(() -> new CloudArt(true, 1, 1, 1, 2, 1, 0));
    }

    @Test
    void presetsPickTiersThatNeedOnlyTheirData() {
        assertThat(CloudsConfig.forQuality(Quality.LOW).technique()).isEqualTo(CloudsConfig.Technique.SKYBOX);
        assertThat(CloudsConfig.forQuality(Quality.LOW).needsNoiseVolumes()).isFalse();
        assertThat(CloudsConfig.forQuality(Quality.MEDIUM).needsNoiseVolumes()).isTrue();
        assertThat(CloudsConfig.forQuality(Quality.HIGH).shapeSize()).isEqualTo(128);
        assertThat(CloudsConfig.forQuality(Quality.ULTRA).needsNoiseVolumes()).isTrue();
        assertThat(new CloudsConfig(CloudsConfig.Technique.OFF, 8, 1, 8, 1, 8, 1, 0).needsNoiseVolumes()).isFalse();
        assertThatIllegalArgumentException().isThrownBy(() -> new CloudsConfig(CloudsConfig.Technique.OFF, 2, 1, 8, 1,
            8, 1, 0));
    }

    @Test
    void perlinNoiseTilesAndVanishesOnTheLattice() {
        int period = 4;
        for (int i = 0; i < 50; i++) {
            float x = i * 0.137f;
            float y = i * 0.291f;
            float z = i * 0.073f;
            assertThat(CloudNoise.perlin(x + period, y, z, period, 9)).isCloseTo(CloudNoise.perlin(x, y, z, period, 9),
                within(1e-5f));
            assertThat(CloudNoise.perlin(x, y, z + period, period, 9)).isCloseTo(CloudNoise.perlin(x, y, z, period, 9),
                within(1e-5f));
            assertThat(CloudNoise.perlin(x, y, z, period, 9)).isBetween(-1.1f, 1.1f);
            assertThat(CloudNoise.worley(x + period, y, z, period, 9)).isCloseTo(CloudNoise.worley(x, y, z, period, 9),
                within(1e-5f));
            assertThat(CloudNoise.worleyFbm(x, y, z, period, 9)).isBetween(0f, 1f);
            assertThat(CloudNoise.perlinWorley(x, y, z, period, 4, 9)).isBetween(0f, 1f);
        }
        assertThat(CloudNoise.perlin(1, 2, 3, period, 9)).isZero();
        assertThat(CloudNoise.perlin(0.5f, 0.5f, 0.5f, period, 9)).isNotEqualTo(CloudNoise.perlin(0.5f, 0.5f, 0.5f,
            period, 10));
    }

    @Test
    void worleyIsOneAtItsFeaturePoints() {
        int h1 = CloudNoise.hash(2, 1, 3, 8, 5);
        int h2 = com.truesim.common.math.random.Hash.pcg(h1);
        int h3 = com.truesim.common.math.random.Hash.pcg(h2);
        float x = 2 + com.truesim.common.math.random.Hash.unit(h1);
        float y = 1 + com.truesim.common.math.random.Hash.unit(h2);
        float z = 3 + com.truesim.common.math.random.Hash.unit(h3);
        assertThat(CloudNoise.worley(x, y, z, 8, 5)).isCloseTo(1f, within(1e-6f));
    }

    @Test
    void curlNoiseIsDivergenceFree() {
        float h = 0.01f;
        for (int i = 0; i < 20; i++) {
            float x = 0.3f + i * 0.17f;
            float y = 0.7f + i * 0.11f;
            float div = (CloudNoise.curlX(x + h, y, 4, 3, 2) - CloudNoise.curlX(x - h, y, 4, 3, 2)) / (2 * h)
                + (CloudNoise.curlY(x, y + h, 4, 3, 2) - CloudNoise.curlY(x, y - h, 4, 3, 2)) / (2 * h);
            float scale = Math.abs(CloudNoise.curlX(x, y, 4, 3, 2)) + Math.abs(CloudNoise.curlY(x, y, 4, 3, 2)) + 1;
            assertThat(Math.abs(div) / scale).isLessThan(0.5f);
        }
    }

    @Test
    void texturesHaveStableFingerprints() {
        // Golden fingerprints: any change to the definitions (or a platform difference) changes them. The GPU
        // generators are checked against the same textures within one byte step.
        byte[] shape = new byte[4 * 8 * 8 * 8];
        CloudNoiseTextures.shape(8, 2, 3, 1, shape);
        byte[] detail = new byte[4 * 8 * 8 * 8];
        CloudNoiseTextures.detail(8, 2, 1, detail);
        byte[] curl = new byte[2 * 16 * 16];
        CloudNoiseTextures.curl(16, 4, 3, 4f, 1, curl);
        assertThat(detail[3]).isEqualTo((byte) 255);
        assertThat(CloudNoiseTextures.quantise(2f)).isEqualTo((byte) 255);
        assertThat(CloudNoiseTextures.quantise(-1f)).isZero();
        byte[] again = new byte[shape.length];
        CloudNoiseTextures.shape(8, 2, 3, 1, again);
        assertThat(again).isEqualTo(shape);
        assertThat(new long[] {CloudNoiseTextures.fingerprint(shape), CloudNoiseTextures.fingerprint(detail),
            CloudNoiseTextures.fingerprint(curl)}).containsExactly(SHAPE_FINGERPRINT, DETAIL_FINGERPRINT,
            CURL_FINGERPRINT);
    }

    private static final long SHAPE_FINGERPRINT = -864204815468843299L;
    private static final long DETAIL_FINGERPRINT = 3075880336383042369L;
    private static final long CURL_FINGERPRINT = 5888527015838273713L;

    @Test
    void samplingNoiseAllocatesNothing() {
        float[] sink = new float[1];
        assertAllocationFree(() -> {
            sink[0] += CloudNoise.perlinWorley(0.3f, 1.7f, 2.2f, 4, 4, 1) + CloudNoise.curlX(0.1f, 0.2f, 4, 3, 1);
        });
    }
}
