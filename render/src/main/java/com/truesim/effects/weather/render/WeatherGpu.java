package com.truesim.effects.weather.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.common.math.Maths;
import com.truesim.effects.weather.Brushes;
import com.truesim.effects.weather.StormSimulation;
import com.truesim.effects.weather.WeatherBrush;
import com.truesim.effects.weather.WeatherField;
import com.truesim.effects.weather.WeatherGrid;
import com.truesim.effects.weather.WeatherSample;
import com.truesim.effects.weather.WeatherState;
import com.truesim.effects.weather.clouds.CloudProfile;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.clouds.CloudTypes;
import com.truesim.effects.weather.clouds.CloudsConfig;
import com.truesim.effects.weather.lightning.LightningEvents;
import com.truesim.effects.weather.render.layout.CloudFrameLayout;
import com.truesim.effects.weather.render.layout.CloudTypeLayout;
import com.truesim.effects.weather.render.layout.CloudsConstants;
import com.truesim.effects.weather.render.layout.FieldBrushLayout;
import com.truesim.effects.weather.render.layout.FieldCellLayout;
import com.truesim.effects.weather.render.layout.FieldInputsLayout;
import com.truesim.effects.weather.render.layout.FieldPush;
import com.truesim.effects.weather.render.layout.FlashLayout;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * A world's weather on the GPU, for every cloud tier, the cloud shadows, the rain and the lightning glow: the weather
 * model's near and far maps as dynamic RGBA16F textures (each map tile uploaded when its version changes, within the
 * textures' update budget), the cloud types' profile atlas and layer table, and the frame's {@code CloudFrame} record
 * (clouds.slang): where the maps and the noise lie around an anchor, the sun and the sky ({@link CloudLighting}), the
 * atmosphere, the lighting model's tunables ({@link CloudSettings}) and the flashes lit this frame
 * ({@link ActiveFlashes}). Positions on the GPU are metres from the anchor, the near map's centre on the ground, so
 * they stay small however far the world reaches.
 *
 * <p>Flat worlds ({@code WeatherDomain.flat()}); a planet's maps (the gnomonic near map, the global cube) are not drawn
 * yet. {@link #update} once a frame on the thread that writes the scene's frame data, before the frame records;
 * it allocates nothing.
 */
public final class WeatherGpu implements AutoCloseable {

    /** Heights per type in the profile atlas. */
    public static final int PROFILE_HEIGHTS = 64;

    /**
     * Map regions uploaded a frame at most: each takes the engine's texture updates some of the render thread's memory
     * stack at the next frame's start, and a few hundred overflow it.
     */
    static final int MAX_REGIONS = 24;

    /** Storm cells and brushes the GPU's field takes (field.slang's MAX_CELLS, MAX_BRUSHES). */
    static final int MAX_CELLS = 64;
    static final int MAX_BRUSHES = 32;

    /** Seconds a storm cell takes to turn from towering cumulus into cumulonimbus on screen. */
    static final double MATURING = 180;

    private static final ValueLayout.OfShort HALF = ValueLayout.JAVA_SHORT_UNALIGNED;

    private final Textures textures;
    private final Weather weather;
    private final CloudTypes types;
    private final FrameBuffer frame;
    private final Arena arena = Arena.ofShared();
    private final int nearTexture;
    private final int farTexture;
    private final int nearTypes;
    private final int farTypes;
    private final int[] cellIds;
    private final float[] cellMature;
    private double matureTime = Double.NaN;
    private final int atlasTexture;
    private final FrameBuffer inputs;
    private final int nearSize;
    private final int farSize;
    private final double nearExtent;
    private final double farExtent;
    private final WeatherSample sample = new WeatherSample();
    private double farCentreX;
    private double farCentreZ;
    private final ActiveFlashes flashes;
    private final CloudNoise noise;
    private final int shadowSize;
    private final int shadowSteps;
    private final double[] sun = new double[3];
    private final double[] sunLight = new double[3];
    private final double[] sky = new double[3];
    private final double[] horizon = new double[3];
    private double anchorX;
    private double anchorZ;
    private double time;
    private boolean flashLit;

    /**
     * {@code weather}'s maps in {@code textures} (the scene's resources'), drawn with {@code types}' profiles; the
     * frame record a copy per frame in flight of {@code slots}.
     */
    @AllowAllocation("made once")
    public WeatherGpu(VulkanDevice device, FrameSlots slots, Textures textures, Weather weather, CloudTypes types,
        CloudsConfig clouds, EngineSettings settings) {
        this.textures = Objects.requireNonNull(textures, "textures");
        noise = new CloudNoise(textures, Objects.requireNonNull(clouds, "clouds"));
        shadowSize = settings.get(CloudSettings.SHADOW_SIZE);
        shadowSteps = settings.get(CloudSettings.SHADOW_STEPS);
        this.weather = Objects.requireNonNull(weather, "weather");
        this.types = Objects.requireNonNull(types, "types");
        if (weather.domain().isSphere()) {
            throw new UnsupportedOperationException("The clouds draw flat worlds' weather so far, not a planet's");
        }
        WeatherConfig config = weather.config();
        frame = FrameBuffer.create(device, CloudFrameLayout.BYTES,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        nearSize = config.nearSize();
        farSize = config.farSize();
        nearExtent = config.nearExtent();
        farExtent = config.farExtent();
        nearTexture = textures.dynamic(TextureFormat.RGBA16F, nearSize, nearSize, 1, true);
        farTexture = textures.dynamic(TextureFormat.RGBA16F, farSize, farSize, 1, true);
        nearTypes = textures.dynamic(TextureFormat.RGBA16F, nearSize, nearSize, 1, true);
        farTypes = textures.dynamic(TextureFormat.RGBA16F, farSize, farSize, 1, true);
        int capacity = weather.storms() != null ? weather.storms().capacity() : 0;
        cellIds = new int[capacity];
        cellMature = new float[capacity];
        java.util.Arrays.fill(cellIds, -1);
        inputs = FrameBuffer.create(device, FieldInputsLayout.BYTES,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, slots);
        float[] atlas = new float[4 * CloudType.count() * PROFILE_HEIGHTS];
        types.bakeProfileAtlas(PROFILE_HEIGHTS, atlas);
        MemorySegment texels = arena.allocate(2L * atlas.length, 8);
        for (int i = 0; i < atlas.length; i++) {
            texels.setAtIndex(ValueLayout.JAVA_SHORT, i, Float.floatToFloat16(atlas[i]));
        }
        atlasTexture = textures.add(TextureFormat.RGBA16F, PROFILE_HEIGHTS, CloudType.count(), 1, texels);
        flashes = new ActiveFlashes(CloudsConstants.MAX_FLASHES);
    }

    /** The noise volumes its clouds are made of. */
    public CloudNoise noise() {
        return noise;
    }

    /** The Beer shadow map of its clouds (a shared request), ordered after {@code noiseReady}. */
    public CloudShadowMap shadowMap(com.truesim.engine.graph.Buffer noiseReady) {
        return new CloudShadowMap(this, noiseReady, shadowSize, shadowSteps);
    }

    /** The weather maps of the frame, evaluated on the GPU (a shared request); read its buffer to come after them. */
    public Request<Buffer> maps() {
        return new Maps(this);
    }

    /** {@link #maps}: the field evaluated over the near and far maps, every frame (field.slang). */
    private record Maps(WeatherGpu gpu) implements Request<Buffer> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Buffer build(GraphBuilder g) {
            WeatherGpu w = gpu;
            Pipeline program = g.computeProgram("shaders/weather/field.spv", "fieldMain", FieldPush.BYTES);
            FieldPush push = new FieldPush();
            Buffer ready = g.createBuffer("weather maps ready", 16);
            g.pass("weather maps").write(ready, BufferAccess.TRANSFER_DST).record(ctx -> {
                ctx.fill(ready, 1);
                // Outside the graph: the frame before's readers first, this frame's writes visible to the readers.
                Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                ctx.dispatch(program, push.inputs(w.inputs.deviceAddress()).map(w.textures.storageSlot(w.nearTexture))
                    .types(w.textures.storageSlot(w.nearTypes)).size(w.nearSize).extent((float) w.nearExtent).centre(0, 0).period(WeatherField.noisePeriod()),
                    (w.nearSize + 7) / 8, (w.nearSize + 7) / 8, 1);
                ctx.dispatch(program, push.inputs(w.inputs.deviceAddress()).map(w.textures.storageSlot(w.farTexture))
                    .types(w.textures.storageSlot(w.farTypes)).size(w.farSize).extent((float) w.farExtent)
                    .centre((float) (w.farCentreX - w.anchorX), (float) (w.farCentreZ - w.anchorZ))
                    .period(WeatherField.noisePeriod()), (w.farSize + 7) / 8, (w.farSize + 7) / 8, 1);
                Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
            });
            return ready;
        }
    }

    /** The near map's sampled slot (RGBA16F: coverage, type A, rain, top), centred on the anchor. */
    public int nearMap() {
        return nearTexture & 0xffff;
    }

    /** The near map's second types' sampled slot (type B, its weight). */
    public int nearTypes() {
        return nearTypes & 0xffff;
    }

        /** The weather it draws. */
    public Weather weather() {
        return weather;
    }

    /** The cloud types it draws. */
    public CloudTypes types() {
        return types;
    }

    /** The frame record's device address, of the frame recording. */
    public long address() {
        return frame.deviceAddress();
    }

    /** The anchor's ground position, x (metres, world). */
    public double anchorX() {
        return anchorX;
    }

    /** The anchor's ground position, z. */
    public double anchorZ() {
        return anchorZ;
    }

    /** The weather's time this frame's record is for, seconds. */
    public double time() {
        return time;
    }

    /** Whether a flash lit the clouds this frame (the clouds' history gives way to it). */
    public boolean flashLit() {
        return flashLit;
    }

    /** The flashes lit this frame. */
    public ActiveFlashes flashes() {
        return flashes;
    }

    /** The camera's position from the anchor: x and z on the ground, y its altitude; into {@code out[0..2]}. */
    public void fromAnchor(Camera camera, float[] out) {
        out[0] = (float) (camera.position(0) - anchorX);
        out[1] = (float) camera.position(1);
        out[2] = (float) (camera.position(2) - anchorZ);
    }

    /**
     * Writes the frame's record and the weather field's inputs (the maps are evaluated on the GPU: {@link #maps}): the weather at {@code now} seconds of its time
     * (usually {@link Weather#time()}), the frame lasting {@code frameSeconds} (the flashes' mean over it), lit by
     * {@code lighting}, tuned by {@code settings}.
     */
    public void update(double now, double frameSeconds, CloudLighting lighting, EngineSettings settings) {
        time = now;
        WeatherConfig config = weather.config();
        // The maps centred on the camera's near-map tile, snapped to their texels so texels keep their ground.
        WeatherGrid near = weather.nearMap();
        double nearTile = near.tileTexels() * near.texelSize();
        anchorX = near.centreTileX() * nearTile;
        anchorZ = near.centreTileZ() * nearTile;
        double farTexel = farExtent / farSize;
        farCentreX = Math.floor(anchorX / farTexel) * farTexel;
        farCentreZ = Math.floor(anchorZ / farTexel) * farTexel;
        float top = writeInputs();
        FrameBuffer f = frame;

        lighting.sun(sun, sunLight);
        lighting.sky(sky);
        lighting.horizon(horizon);
        put3(f, CloudFrameLayout.SUN_DIRECTION, sun[0], sun[1], sun[2]);
        f.putFloat(CloudFrameLayout.SUN_VISIBLE, sun[1] > -0.2 ? 1 : 0);
        put3(f, CloudFrameLayout.SUN_ILLUMINANCE, sunLight[0], sunLight[1], sunLight[2]);
        put3(f, CloudFrameLayout.SKY_ILLUMINANCE, sky[0], sky[1], sky[2]);
        put3(f, CloudFrameLayout.AP_RADIANCE, horizon[0], horizon[1], horizon[2]);
        f.putFloat(CloudFrameLayout.GROUND_ALBEDO, settings.get(CloudSettings.GROUND_ALBEDO));
        f.putFloat(CloudFrameLayout.MAX_ALTITUDE, top);
        placeShadowMap(0, 0, settings.get(CloudSettings.SHADOW_EXTENT), shadowSize);

        // The maps: their slots, where they hold the weather and where the anchor falls in them (toroidal).
        f.putInt(CloudFrameLayout.NEAR_MAP, nearTexture & 0xffff);
        f.putInt(CloudFrameLayout.FAR_MAP, farTexture & 0xffff);
        f.putInt(CloudFrameLayout.NEAR_TYPES, nearTypes & 0xffff);
        f.putInt(CloudFrameLayout.FAR_TYPES, farTypes & 0xffff);
        f.putInt(CloudFrameLayout.PROFILE_ATLAS, atlasTexture & 0xffff);
        f.putInt(CloudFrameLayout.PROFILE_HEIGHTS, PROFILE_HEIGHTS);
        double nearTexel = nearExtent / nearSize;
        double nearHalf = nearExtent / 2 - nearTexel;
        double farHalf = farExtent / 2 - farTexel;
        double fx = farCentreX - anchorX;
        double fz = farCentreZ - anchorZ;
        put4(f, CloudFrameLayout.NEAR_BOUNDS, -nearHalf, -nearHalf, nearHalf, nearHalf);
        put4(f, CloudFrameLayout.FAR_BOUNDS, fx - farHalf, fz - farHalf, fx + farHalf, fz + farHalf);
        // Texture coordinates: 0.5 at each map's centre.
        f.putFloat(CloudFrameLayout.NEAR_ANCHOR, 0.5f);
        f.putFloat(CloudFrameLayout.NEAR_ANCHOR + 4, 0.5f);
        f.putFloat(CloudFrameLayout.FAR_ANCHOR, (float) (0.5 - fx / farExtent));
        f.putFloat(CloudFrameLayout.FAR_ANCHOR + 4, (float) (0.5 - fz / farExtent));
        f.putFloat(CloudFrameLayout.NEAR_EXTENT, (float) nearExtent);
        f.putFloat(CloudFrameLayout.FAR_EXTENT, (float) farExtent);
        f.putInt(CloudFrameLayout.NEAR_SIZE, nearSize);
        f.putInt(CloudFrameLayout.FAR_SIZE, farSize);

        // The wind the anvils and upper parts lean along, in the ground plane (x east, z south).
        double east = config.wind().east(config.advectionAltitude());
        double north = config.wind().north(config.advectionAltitude());
        double speed = Math.sqrt(east * east + north * north);
        f.putFloat(CloudFrameLayout.WIND, speed > 1e-6 ? (float) (east / speed) : 1);
        f.putFloat(CloudFrameLayout.WIND + 4, speed > 1e-6 ? (float) (-north / speed) : 0);
        f.putFloat(CloudFrameLayout.RAIN_MAX_RATE, (float) config.maxPrecipitationRate());
        f.putFloat(CloudFrameLayout.RAIN_FORWARD, settings.get(CloudSettings.RAIN_FORWARD));
        f.putFloat(CloudFrameLayout.RAIN_EXTINCTION_SCALE, settings.get(CloudSettings.RAIN_EXTINCTION));

        // The noise: carried by the same wind as the coverage, evolving upwards through the layers.
        double shapePeriod = settings.get(CloudSettings.SHAPE_PERIOD);
        double detailPeriod = settings.get(CloudSettings.DETAIL_PERIOD);
        double curlPeriod = settings.get(CloudSettings.CURL_PERIOD);
        double x = anchorX - weather.driftEast();
        double z = anchorZ + weather.driftNorth();
        double rise = -now * settings.get(CloudSettings.EVOLUTION);
        f.putFloat(CloudFrameLayout.SHAPE_PERIOD, (float) shapePeriod);
        f.putFloat(CloudFrameLayout.DETAIL_PERIOD, (float) detailPeriod);
        f.putFloat(CloudFrameLayout.CURL_PERIOD, (float) curlPeriod);
        f.putFloat(CloudFrameLayout.CURL_STRENGTH, settings.get(CloudSettings.CURL_STRENGTH));
        f.putFloat(CloudFrameLayout.DETAIL_STRENGTH, settings.get(CloudSettings.DETAIL_STRENGTH));
        put3(f, CloudFrameLayout.SHAPE_ANCHOR, fraction(x / shapePeriod), fraction(rise / shapePeriod),
            fraction(z / shapePeriod));
        put3(f, CloudFrameLayout.DETAIL_ANCHOR, fraction(x / detailPeriod), fraction(rise / detailPeriod),
            fraction(z / detailPeriod));
        f.putFloat(CloudFrameLayout.CURL_ANCHOR, (float) fraction(x / curlPeriod));
        f.putFloat(CloudFrameLayout.CURL_ANCHOR + 4, (float) fraction(z / curlPeriod));
        f.putFloat(CloudFrameLayout.DENSITY_SCALE, settings.get(CloudSettings.DENSITY_SCALE));
        f.putFloat(CloudFrameLayout.COVERAGE_OFFSET, settings.get(CloudSettings.COVERAGE_OFFSET));
        f.putFloat(CloudFrameLayout.POWDER, settings.get(CloudSettings.POWDER));

        // The atmosphere, the lighting model.
        CloudLighting.Atmosphere a = lighting.atmosphere();
        f.putFloat(CloudFrameLayout.PLANET_RADIUS, (float) a.radius());
        f.putFloat(CloudFrameLayout.RAYLEIGH_HEIGHT, (float) a.rayleighHeight());
        f.putFloat(CloudFrameLayout.MIE_HEIGHT, (float) a.mieHeight());
        f.putFloat(CloudFrameLayout.MIE_SCATTERING, (float) a.mieScattering());
        put3(f, CloudFrameLayout.RAYLEIGH_SCATTERING, a.rayleighRed(), a.rayleighGreen(), a.rayleighBlue());
        f.putFloat(CloudFrameLayout.MIE_EXTINCTION, (float) a.mieExtinction());
        float b = settings.get(CloudSettings.MS_EXTINCTION);
        f.putFloat(CloudFrameLayout.MS_SCATTERING, Math.min(settings.get(CloudSettings.MS_SCATTERING), b));
        f.putFloat(CloudFrameLayout.MS_EXTINCTION, b);
        f.putFloat(CloudFrameLayout.MS_PHASE, settings.get(CloudSettings.MS_PHASE));
        f.putInt(CloudFrameLayout.OCTAVES, settings.get(CloudSettings.OCTAVES));
        f.putFloat(CloudFrameLayout.AMBIENT_DEPTH, settings.get(CloudSettings.AMBIENT_DEPTH));
        f.putFloat(CloudFrameLayout.FLASH_LEAK, settings.get(CloudSettings.FLASH_LEAK));

        // The types' layers and microphysics, art applied.
        for (int t = 0; t < CloudType.count(); t++) {
            CloudProfile p = types.profile(CloudType.of(t));
            long o = CloudFrameLayout.TYPES + (long) t * CloudTypeLayout.BYTES;
            f.putFloat(o + CloudTypeLayout.BASE, (float) p.base());
            f.putFloat(o + CloudTypeLayout.TOP, (float) p.top());
            // The physical extinction is the layer's mean (its water content's); the density's mean over the layer
            // at full cover is half the profile's mean (the base shape spread over [0, 1]): the extinction at density
            // 1 makes the mean the physical one.
            double physical = types.art().extinction(p.extinction());
            f.putFloat(o + CloudTypeLayout.EXTINCTION, (float) (physical / meanDensity(p)));
            f.putFloat(o + CloudTypeLayout.COLUMN, (float) physical);
            f.putFloat(o + CloudTypeLayout.SHEAR, (float) p.shear());
            f.putFloat(o + CloudTypeLayout.DROPLET, (float) p.dropletDiameter());
            f.putFloat(o + CloudTypeLayout.ABSORPTION, (float) p.absorption());
            f.putFloat(o + CloudTypeLayout.EROSION, (float) types.art().erosion(p.erosion()));
            f.putFloat(o + CloudTypeLayout.ICE, p.ice() ? 1 : 0);
        }

        // The flashes lit over the frame.
        LightningEvents events = weather.lightning();
        flashes.select(events, config.lightning(), now - frameSeconds, now, settings.get(CloudSettings.FLASH_INTENSITY),
            settings.get(CloudSettings.FLASHES));
        float radius = settings.get(CloudSettings.FLASH_RADIUS);
        for (int i = 0; i < flashes.count(); i++) {
            int slot = flashes.slot(i);
            long o = CloudFrameLayout.FLASHES + (long) i * FlashLayout.BYTES;
            put3(f, o + FlashLayout.A, events.origin(slot, 0) - anchorX, events.origin(slot, 1),
                events.origin(slot, 2) - anchorZ);
            f.putFloat(o + FlashLayout.INTENSITY, (float) flashes.intensity(i));
            put3(f, o + FlashLayout.B, events.end(slot, 0) - anchorX, events.end(slot, 1),
                events.end(slot, 2) - anchorZ);
            f.putFloat(o + FlashLayout.RADIUS, radius);
        }
        f.putInt(CloudFrameLayout.FLASH_COUNT, flashes.count());
        flashLit = flashes.count() > 0;
    }

    /**
     * Places the Beer shadow map: centred on the ground under {@code cx}, {@code cz} (from the anchor), snapped to its
     * texels, {@code extent} metres across, {@code size} texels, its plane above every cloud along the sun.
     */
    void placeShadowMap(double cx, double cz, double extent, int size) {
        double lx = sun[0];
        double ly = Math.max(sun[1], 0.02);
        double lz = sun[2];
        double l = Math.sqrt(lx * lx + ly * ly + lz * lz);
        lx /= l;
        ly /= l;
        lz /= l;
        // U across the light (horizontal), V = L x U.
        double ux = -lz;
        double uz = lx;
        double u = Math.sqrt(ux * ux + uz * uz);
        if (u < 1e-6) {
            ux = 1;
            uz = 0;
        } else {
            ux /= u;
            uz /= u;
        }
        double vx = ly * uz;
        double vy = lz * ux - lx * uz;
        double vz = -ly * ux;
        double texel = extent / size;
        // Snapped along its own axes, so the texels stay put as the camera moves.
        double su = Math.floor((cx * ux + cz * uz) / texel) * texel;
        double sv = Math.floor((cx * vx + cz * vz) / texel) * texel;
        double px = su * ux + sv * vx;
        double py = sv * vy;
        double pz = su * uz + sv * vz;
        // Down the light to the ground: the centre on the ground below the snapped point.
        double back = py / ly;
        FrameBuffer f = frame;
        put3(f, CloudFrameLayout.SHADOW_CENTRE, px - lx * back, 0, pz - lz * back);
        put3(f, CloudFrameLayout.SHADOW_U, ux, 0, uz);
        put3(f, CloudFrameLayout.SHADOW_V, vx, vy, vz);
        f.putFloat(CloudFrameLayout.SHADOW_EXTENT, (float) extent);
        f.putInt(CloudFrameLayout.SHADOW_SIZE, size);
        float top = f.getFloat(CloudFrameLayout.MAX_ALTITUDE);
        f.putFloat(CloudFrameLayout.SHADOW_DEPTH, (float) ((top + 100) / ly));
    }

    private static double fraction(double v) {
        return v - Math.floor(v);
    }

    private static void put4(FrameBuffer f, long o, double x, double y, double z, double w) {
        f.putFloat(o, (float) x);
        f.putFloat(o + 4, (float) y);
        f.putFloat(o + 8, (float) z);
        f.putFloat(o + 12, (float) w);
    }

    /**
     * Writes the field's inputs for the GPU (field.slang's FieldInputs): the preset in effect, the noise's origin, the
     * storm cells and the brushes, from the anchor; returns the highest cloud top they can make, metres.
     */
    private float writeInputs() {
        FrameBuffer in = inputs;
        WeatherField field = weather.field();
        WeatherState state = field.state();
        double scale = state.noiseScale();
        int period = WeatherField.noisePeriod();
        in.putFloat(FieldInputsLayout.COVERAGE, (float) state.coverage());
        in.putFloat(FieldInputsLayout.VARIATION, (float) state.variation());
        in.putFloat(FieldInputsLayout.PRECIPITATION, (float) state.precipitation());
        // A transition's types blend over it as its parameters do (smoothstep of the progress).
        double t = state.progress();
        in.putFloat(FieldInputsLayout.TYPE, state.from().cloudType().channelValue());
        in.putFloat(FieldInputsLayout.TO_TYPE, state.target().cloudType().channelValue());
        in.putFloat(FieldInputsLayout.TO_WEIGHT, (float) (t * t * (3 - 2 * t)));
        in.putFloat(FieldInputsLayout.TOP, (float) state.topHeight());
        in.putFloat(FieldInputsLayout.NOISE_SCALE, (float) scale);
        in.putInt(FieldInputsLayout.OCTAVES, field.octaves());
        in.putInt(FieldInputsLayout.SEED, field.seed());
        put3(in, FieldInputsLayout.NOISE_ORIGIN, wrap((anchorX - field.offset(0)) / scale, period),
            wrap(-field.offset(1) / scale, period), wrap((anchorZ - field.offset(2)) / scale, period));
        float top = (float) Math.max(state.topHeight(), Math.max(state.from().cloudType() == null ? 0
            : types.profile(state.from().cloudType()).top(), types.profile(state.target().cloudType()).top()));
        StormSimulation storms = weather.storms();
        int cells = 0;
        // A cell becomes a cumulonimbus over MATURING seconds of the weather's time, not at once.
        double dt = Double.isNaN(matureTime) ? 0 : Math.max(0, time - matureTime);
        matureTime = time;
        if (storms != null) {
            for (int s = 0; s < storms.capacity() && cells < MAX_CELLS; s++) {
                if (!storms.active(s)) {
                    continue;
                }
                long o = FieldInputsLayout.CELL + (long) cells * FieldCellLayout.BYTES;
                in.putFloat(o + FieldCellLayout.CORE, (float) (storms.position(s, 0) - anchorX));
                in.putFloat(o + FieldCellLayout.CORE + 4, (float) (storms.position(s, 2) - anchorZ));
                in.putFloat(o + FieldCellLayout.CORE_RADIUS, (float) storms.coreRadius(s));
                in.putFloat(o + FieldCellLayout.COVERAGE, (float) storms.coverage(s));
                in.putFloat(o + FieldCellLayout.PRECIPITATION, (float) storms.precipitation(s));
                if (cellIds[s] != storms.id(s)) {
                    cellIds[s] = storms.id(s);
                    cellMature[s] = storms.cloudType(s) == CloudType.CUMULONIMBUS ? 1 : 0;
                }
                float target = storms.cloudType(s) == CloudType.CUMULONIMBUS ? 1 : 0;
                float step = (float) (dt / MATURING);
                cellMature[s] = target > cellMature[s] ? Math.min(target, cellMature[s] + step)
                    : Math.max(target, cellMature[s] - step);
                in.putFloat(o + FieldCellLayout.TYPE, CloudType.TOWERING_CUMULUS.channelValue());
                in.putFloat(o + FieldCellLayout.MATURE_TYPE, CloudType.CUMULONIMBUS.channelValue());
                in.putFloat(o + FieldCellLayout.MATURE, cellMature[s]);
                in.putFloat(o + FieldCellLayout.TOP, (float) storms.top(s));
                in.putFloat(o + FieldCellLayout.ANVIL, (float) storms.anvil(s));
                in.putFloat(o + FieldCellLayout.ANVIL_CENTRE, (float) (storms.anvilPosition(s, 0) - anchorX));
                in.putFloat(o + FieldCellLayout.ANVIL_CENTRE + 4, (float) (storms.anvilPosition(s, 2) - anchorZ));
                in.putFloat(o + FieldCellLayout.ANVIL_RADIUS, (float) (storms.anvilRadius(s) * storms.anvil(s)));
                top = Math.max(top, (float) storms.top(s));
                cells++;
            }
        }
        in.putInt(FieldInputsLayout.CELLS, cells);
        Brushes brushes = weather.brushes();
        int count = Math.min(brushes.count(), MAX_BRUSHES);
        for (int i = 0; i < count; i++) {
            WeatherBrush b = brushes.brush(i);
            long o = FieldInputsLayout.BRUSH + (long) i * FieldBrushLayout.BYTES;
            in.putFloat(o + FieldBrushLayout.CENTRE, (float) (brushes.position(i, 0) - anchorX));
            in.putFloat(o + FieldBrushLayout.CENTRE + 4, (float) (brushes.position(i, 2) - anchorZ));
            in.putFloat(o + FieldBrushLayout.RADIUS, (float) b.radius());
            in.putFloat(o + FieldBrushLayout.FALLOFF, (float) b.falloff());
            in.putFloat(o + FieldBrushLayout.STRENGTH, (float) b.strength());
            in.putFloat(o + FieldBrushLayout.COVERAGE, (float) b.coverage());
            in.putFloat(o + FieldBrushLayout.PRECIPITATION, (float) b.precipitation());
            in.putFloat(o + FieldBrushLayout.TYPE, b.cloudType().channelValue());
            in.putFloat(o + FieldBrushLayout.TOP, (float) b.topHeight());
            in.putInt(o + FieldBrushLayout.MODE, b.mode().ordinal());
            if (b.mode() != WeatherBrush.Mode.CLEAR) {
                top = (float) Math.max(top, Math.max(b.topHeight(), types.profile(b.cloudType()).top()));
            }
        }
        in.putInt(FieldInputsLayout.BRUSHES, count);
        return top;
    }

    /** The optical depth of a column of {@code type} at {@code coverage} under a top channel of {@code top}. */
    private double column(CloudType type, double coverage, double top) {
        CloudProfile p = types.profile(type);
        double thickness = (top > p.base() + 100 ? top : p.top()) - p.base();
        return coverage * p.extinction() * thickness;
    }

    /** A layer's mean density at full cover: half its profile's mean (the base shape's mean, stretched, is a half). */
    static double meanDensity(CloudProfile p) {
        double sum = 0;
        int n = 64;
        for (int i = 0; i < n; i++) {
            sum += p.density((i + 0.5) / n);
        }
        return Math.max(0.5 * sum / n, 0.05);
    }

    private static double wrap(double v, int period) {
        return v - Math.floor(v / period) * period;
    }

    /**
     * The sky's illuminance on the ground under the clouds around (x, z) (lux, RGB, into {@code out}): the clear sky's
     * where there are no clouds, and where there are, the sun's and the sky's light on their tops diffused through
     * them (the two-stream transmittance of the type's column, as the clouds' march has it). The mean of 13 points of
     * the field within {@code radius} metres. What lights the ground in the clouds' shadow (an environment's light).
     */
    public void skyBelowClouds(double x, double z, double radius, double[] out) {
        double cover = 0;
        double diffuse = 0;
        int n = 0;
        for (int ring = 0; ring < 3; ring++) {
            int points = ring == 0 ? 1 : 6;
            double r = radius * ring / 2;
            for (int k = 0; k < points; k++) {
                double angle = k * Math.PI / 3 + ring * 0.5;
                weather.field().evaluate(x + r * Maths.cos(angle), 0, z + r * Maths.sin(angle), sample);
                double c = sample.coverage();
                double tau;
                WeatherState state = weather.state();
                if (sample.cloudType() == CloudType.fromChannel(state.type())) {
                    // The preset's: its transition's two types blended, as the GPU's map has them.
                    double t = state.progress();
                    double s = t * t * (3 - 2 * t);
                    tau = (1 - s) * column(state.from().cloudType(), c, sample.topHeight())
                        + s * column(state.target().cloudType(), c, sample.topHeight());
                } else {
                    tau = column(sample.cloudType(), c, sample.topHeight());
                }
                cover += c;
                diffuse += c / (1 + 0.75 * (1 - 0.85) * tau);
                n++;
            }
        }
        cover /= n;
        diffuse /= n;
        double mu = Math.max(sun[1], 0);
        for (int c = 0; c < 3; c++) {
            out[c] = sky[c] * (1 - cover) + (sunLight[c] * mu + sky[c]) * diffuse;
        }
    }

    private static void put3(FrameBuffer f, long o, double x, double y, double z) {
        f.putFloat(o, (float) x);
        f.putFloat(o + 4, (float) y);
        f.putFloat(o + 8, (float) z);
    }

    @Override
    public void close() {
        noise.close();
        textures.remove(nearTexture);
        textures.remove(nearTypes);
        textures.remove(farTypes);
        textures.remove(farTexture);
        textures.remove(atlasTexture);
        frame.close();
        inputs.close();
        arena.close();
    }
}
