package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.atmosphere.Quality;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The clouds' model and lighting tunables shared by every tier, as settings ({@code render.clouds.*},
 * {@code render.rain.*}, {@code render.lightning.*}): physical defaults, art overrides off. The tiers' own tunables are
 * their kinds' configs ({@link Clouds}). {@link WeatherGpu} reads these every frame, so they change without a rebuild.
 */
public final class CloudSettings {

    /** Metres per tile of the base shape volume. */
    public static final Setting<Float> SHAPE_PERIOD = Setting.decimal("render.clouds.shapePeriod", 9000, 500, 200_000,
        "metres per tile of the clouds' base shape noise");
    /** Metres per tile of the detail volume. */
    public static final Setting<Float> DETAIL_PERIOD = Setting.decimal("render.clouds.detailPeriod", 700, 20, 20_000,
        "metres per tile of the clouds' detail noise");
    /** Metres per tile of the curl texture. */
    public static final Setting<Float> CURL_PERIOD = Setting.decimal("render.clouds.curlPeriod", 5000, 50, 100_000,
        "metres per tile of the curl noise distorting the detail");
    /** How far curl displaces the detail lookups at the base of a layer. */
    public static final Setting<Float> CURL_STRENGTH = Setting.decimal("render.clouds.curlStrength", 120, 0, 5000,
        "metres the curl noise displaces the detail at the base of a layer");
    /** How much the detail erodes the base shape (1: Schneider 2015's). */
    public static final Setting<Float> DETAIL_STRENGTH = Setting.decimal("render.clouds.detailStrength", 1, 0, 4,
        "how much the detail noise erodes the clouds' base shape");
    /** How fast the shapes evolve (the noise drifting through the layer), metres per second. */
    public static final Setting<Float> EVOLUTION = Setting.decimal("render.clouds.evolution", 0.4f, 0, 100,
        "metres per second the clouds' noise moves through the layers: the shapes' slow evolution");
    /** Wrenninge's octaves of multiple scattering. */
    public static final Setting<Integer> OCTAVES = Setting.integer("render.clouds.octaves", 3, 1, 8,
        "octaves of multiple scattering (Wrenninge 2013)");
    /** Each octave's scattering factor a. */
    public static final Setting<Float> MS_SCATTERING = Setting.decimal("render.clouds.octaveScattering", 0.5f, 0, 1,
        "each multiple-scattering octave's scattering, a (at most b)");
    /** Each octave's extinction factor b. */
    public static final Setting<Float> MS_EXTINCTION = Setting.decimal("render.clouds.octaveExtinction", 0.5f, 0, 1,
        "each multiple-scattering octave's extinction, b");
    /** Each octave's phase eccentricity factor c. */
    public static final Setting<Float> MS_PHASE = Setting.decimal("render.clouds.octavePhase", 0.5f, 0, 1,
        "each multiple-scattering octave's phase eccentricity, c");
    /**
     * The optical depth to a layer's top (bottom), for the diffused light inside it, as a fraction of the sample's
     * extinction times the distance: the layer's mean density relative to the sample's.
     */
    public static final Setting<Float> AMBIENT_DEPTH = Setting.decimal("render.clouds.ambientDepth", 1, 0, 4,
        "multiplies the optical depth to a cloud layer's top (its mean extinction) for the light diffused inside it");
    /** The ground's albedo, for the light it bounces up into cloud bases. */
    public static final Setting<Float> GROUND_ALBEDO = Setting.decimal("render.clouds.groundAlbedo", 0.2f, 0, 1,
        "the ground's albedo, lighting the clouds' bases from below");
    /** Art: multiplies the extinction. */
    public static final Setting<Float> DENSITY_SCALE = Setting.decimal("render.clouds.art.density", 1, 0, 16,
        "art override: multiplies the clouds' physical extinction (1 physical)");
    /** Art: added to every coverage. */
    public static final Setting<Float> COVERAGE_OFFSET = Setting.decimal("render.clouds.art.coverage", 0, -1, 1,
        "art override: added to the weather map's coverage (0 physical)");
    /** Art: Schneider's Beer-powder term. */
    public static final Setting<Float> POWDER = Setting.decimal("render.clouds.art.powder", 0, 0, 1,
        "art override: Schneider 2015's Beer-powder darkening of edges facing the sun (0 physical)");
    /** Rain drops' Henyey-Greenstein g. */
    public static final Setting<Float> RAIN_FORWARD = Setting.decimal("render.rain.forward", 0.85f, -0.99f, 0.99f,
        "how much rain scatters forwards: Henyey-Greenstein g of large drops");
    /** Multiplies the rain's Marshall-Palmer extinction. */
    public static final Setting<Float> RAIN_EXTINCTION = Setting.decimal("render.rain.extinction", 1, 0, 16,
        "multiplies the rain shafts' extinction from Marshall-Palmer drops (1 physical)");
    /** Metres downwind the rain falls per metre: the wind's shear over the drops' fall speed. */
    public static final Setting<Float> RAIN_SLANT = Setting.decimal("render.rain.slant", 0.25f, 0, 4,
        "metres downwind rain shafts slant per metre fallen (the wind's shear over the drops' fall speed)");
    /** More slant towards the ground: the shaft curves. */
    public static final Setting<Float> RAIN_CURVE = Setting.decimal("render.rain.curve", 0.35f, 0, 4,
        "how much more rain shafts slant towards the ground (they curve downwind), per fallen fraction of the base");
    /** The fraction of the rain evaporated before it reaches the ground. */
    public static final Setting<Float> RAIN_EVAPORATION = Setting.decimal("render.rain.evaporation", 0.35f, 0, 1,
        "the fraction of the rain that evaporates before reaching the ground (shafts thin downwards)");
    /** How much of the shaft's edge evaporates before the ground. */
    public static final Setting<Float> RAIN_TAPER = Setting.decimal("render.rain.taper", 0.15f, 0, 0.9f,
        "how much of a rain shaft's lighter edge evaporates before the ground (shafts narrow downwards)");
    /** Precipitation falling from an anvil's base, mm/h where it covers fully. */
    public static final Setting<Float> VIRGA = Setting.decimal("render.rain.virga", 0.3f, 0, 50,
        "mm/h of precipitation falling from a thunderstorm anvil's base where it covers fully, evaporating (virga)");
    /** Metres below an anvil's base by which its precipitation has evaporated. */
    public static final Setting<Float> VIRGA_DEPTH = Setting.decimal("render.rain.virgaDepth", 3000, 100, 10_000,
        "metres below a thunderstorm anvil's base by which its precipitation has evaporated (virga)");
    /** The turrets of towering clouds brushed onto the maps, times the base shape's frequency. */
    public static final Setting<Float> BILLOW_SCALE = Setting.decimal("render.clouds.billowScale", 2.5f, 0, 16,
        "the frequency of the billows (turrets) of towering clouds brushed onto the weather maps, times the base "
            + "shape's (0: none; storm cells have their own)");
    /** The anvils' ice water content, g/m³. */
    public static final Setting<Float> ANVIL_ICE = Setting.decimal("render.clouds.anvil.ice", 0.25f, 0, 2,
        "grams of ice per cubic metre in thunderstorm anvils (tenths near the core: optical depths of tens)");
    /** The anvils' crystals' effective diameter, µm. */
    public static final Setting<Float> ANVIL_DIAMETER = Setting.decimal("render.clouds.anvil.diameter", 60, 10, 500,
        "the effective diameter of thunderstorm anvils' ice crystals, micrometres");
    /** An anvil's depth at full cover, metres. */
    public static final Setting<Float> ANVIL_THICKNESS = Setting.decimal("render.clouds.anvil.thickness", 2000, 0,
        6000, "metres deep a thunderstorm's anvil is where it covers fully (0: no anvils)");
    /** The overshooting top over a storm's updraft, metres above its tower at most. */
    public static final Setting<Float> ANVIL_OVERSHOOT = Setting.decimal("render.clouds.anvil.overshoot", 1200, 0,
        5000, "metres the overshooting top of a storm's updraft rises above its tower and anvil at most (early in "
            + "its mature stage)");
    /** An anvil's half width at its far end, over its radius. */
    public static final Setting<Float> ANVIL_WIDTH = Setting.decimal("render.clouds.anvil.width", 1.2f, 0.1f, 4,
        "a thunderstorm anvil's half width at its far end, over its radius (the storm's anvil factor times its core's)");
    /** How far an anvil reaches downwind, over its radius. */
    public static final Setting<Float> ANVIL_LENGTH = Setting.decimal("render.clouds.anvil.length", 3.5f, 0.2f, 10,
        "how far a thunderstorm anvil reaches downwind once spread, over its radius");
    /** How far the pouches under an anvil hang down (mamma), metres. */
    public static final Setting<Float> MAMMATUS = Setting.decimal("render.clouds.anvil.mammatus", 500, 0, 3000,
        "metres the pouches under a thunderstorm anvil's older part hang down at most, once it has spread (mamma; 0: "
            + "none)");
    /** Metres per tile of a storm's turret noise. */
    public static final Setting<Float> TURRET_PERIOD = Setting.decimal("render.clouds.storm.turretPeriod", 8000, 500,
        100_000, "metres per tile of storm towers' turret noise (its billows a quarter, an eighth and a sixteenth)");
    /** How far turrets bulge out of a storm's towers. */
    public static final Setting<Float> TURRETS = Setting.decimal("render.clouds.storm.turrets", 1.5f, 0, 4,
        "how far turrets bulge out of storm towers, times a fifth of a tower's radius (0: smooth towers)");
    /** Where a storm's tops turn to ice. */
    public static final Setting<Float> GLACIATION = Setting.decimal("render.clouds.storm.glaciation", 8000, 0, 20_000,
        "metres above which a cumulonimbus's tops turn to ice (about -35 C; at most 1.5 km below a cell's top)");
    /** The ice water content of a storm's glaciated tops, g/m³. */
    public static final Setting<Float> TOWER_ICE = Setting.decimal("render.clouds.storm.ice", 0.6f, 0, 4,
        "grams of ice per cubic metre in thunderstorms' glaciated tops (crystals of the anvil's diameter)");
    /** Metres a storm's towers lean downwind per metre up. */
    public static final Setting<Float> TOWER_LEAN = Setting.decimal("render.clouds.storm.lean", 0.12f, 0, 1,
        "metres storm towers lean downwind per metre up (the wind's shear)");
    /** Metres per second a storm's turrets rise. */
    public static final Setting<Float> TURRET_RISE = Setting.decimal("render.clouds.storm.rise", 3, 0, 50,
        "metres per second the turrets of storm towers rise through them (their updraft's thermals)");
    /** The march's level-of-detail switches fade over this fraction of their distances. */
    public static final Setting<Float> LOD_BAND = Setting.decimal("render.clouds.lodBand", 0.2f, 0, 0.9f,
        "the fraction of their distances over which the cloud march's level-of-detail switches fade (no steps)");
    /** Terms of the clouds' light left out, for looking at the lighting (march.slang's DEBUG_* bits). */
    public static final Setting<Integer> DEBUG = Setting.integer("render.clouds.debug", 0, 0, 31,
        "debugging: terms of the clouds' light left out (1 the air in front, 2 the sky's light, 4 the sun diffused "
            + "from above, 8 the sun's octaves); 16 the march's texels (transmittance, depth marched to, mean depth)");
    /** The clouds' shadow on the air in front of them. */
    public static final Setting<Boolean> AIR_SHADOW = Setting.bool("render.clouds.airShadow", false,
        "the clouds' shadow on the air in front of them (off: the atmosphere's aerial perspective on the scene behind "
            + "has none, and the two would not match at the horizon)");
    /** A median flash's first stroke's peak luminous intensity, candela. */
    public static final Setting<Float> FLASH_INTENSITY = Setting.decimal("render.lightning.intensity", 2.0e10f, 0, 1e13f,
        "the peak luminous intensity of a median flash's first stroke, candela (scaled by each flash's current)");
    /** The channel's glow radius. */
    public static final Setting<Float> FLASH_RADIUS = Setting.decimal("render.lightning.radius", 40, 1, 2000,
        "metres around a lightning channel lit as the channel itself");
    /** The glow's leak: the effective absorption of its diffusion through the cloud. */
    public static final Setting<Float> FLASH_LEAK = Setting.decimal("render.lightning.leak", 2.0e-4f, 0, 1,
        "per metre: light of a flash leaving the cloud on its way, the diffusion's effective absorption");
    /** Flashes lighting the clouds at once. */
    public static final Setting<Integer> FLASHES = Setting.integer("render.lightning.flashes", 8, 0, 8,
        "flashes lighting the clouds at once, the brightest");

    /** The Beer shadow map's texels per edge (read when the weather's GPU side is made). */
    public static final Setting<Integer> SHADOW_SIZE = Setting.integer("render.clouds.shadow.size", 512, 16, 4096,
        "texels per edge of the clouds' Beer shadow map");
    /** The metres it spans. */
    public static final Setting<Float> SHADOW_EXTENT = Setting.decimal("render.clouds.shadow.extent", 32_000, 1000,
        1_000_000, "metres of ground the clouds' shadow map spans around the camera");
    /** Steps through the cloud slab per texel (read when the weather's GPU side is made). */
    public static final Setting<Integer> SHADOW_STEPS = Setting.integer("render.clouds.shadow.steps", 24, 2, 256,
        "steps through the clouds per texel of their shadow map");

    /** Every one, for registering. */
    public static final List<Setting<?>> ALL = List.of(SHAPE_PERIOD, DETAIL_PERIOD, CURL_PERIOD, CURL_STRENGTH,
        DETAIL_STRENGTH, EVOLUTION, OCTAVES, MS_SCATTERING, MS_EXTINCTION, MS_PHASE, AMBIENT_DEPTH, GROUND_ALBEDO,
        DENSITY_SCALE, COVERAGE_OFFSET, POWDER, RAIN_FORWARD, RAIN_EXTINCTION, RAIN_SLANT, RAIN_CURVE,
        RAIN_EVAPORATION, RAIN_TAPER, BILLOW_SCALE, ANVIL_ICE, ANVIL_DIAMETER, ANVIL_THICKNESS, ANVIL_OVERSHOOT,
        ANVIL_WIDTH, ANVIL_LENGTH, MAMMATUS, TURRET_PERIOD, TURRETS, GLACIATION, TOWER_ICE, TOWER_LEAN, TURRET_RISE,
        LOD_BAND, VIRGA, VIRGA_DEPTH, DEBUG, AIR_SHADOW,
        FLASH_INTENSITY, FLASH_RADIUS,
        FLASH_LEAK, FLASHES, SHADOW_SIZE, SHADOW_EXTENT, SHADOW_STEPS);

    private CloudSettings() {
    }

    /**
     * Sets the clouds' tier for {@code quality} (weather-clouds' {@code CloudsConfig.forQuality}: LOW the 2D sky,
     * MEDIUM and HIGH ray-marched layers, ULTRA fly-through volumes) as the player's choice.
     */
    @AllowAllocation("set when the quality changes")
    public static EngineSettings forQuality(EngineSettings settings, Quality quality) {
        Clouds.SLOT.choose(settings, switch (quality) {
            case LOW -> Clouds.SKYBOX;
            case MEDIUM, HIGH -> Clouds.LAYERED;
            case ULTRA -> Clouds.VOLUMETRIC;
        });
        settings.set(OCTAVES, quality == Quality.LOW ? 1 : quality == Quality.MEDIUM ? 2 : 3);
        return settings;
    }
}
