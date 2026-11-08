package com.truesim.effects.weather.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT;
import static org.lwjgl.vulkan.VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;
import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.math.Maths;
import com.truesim.effects.weather.Brushes;
import com.truesim.effects.weather.StormSimulation;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherBrush;
import com.truesim.effects.weather.WeatherConfig;
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
import com.truesim.effects.weather.render.layout.OccupancyPush;
import com.truesim.effects.weather.render.layout.SkyLightPush;
import com.truesim.effects.weather.render.layout.StormCellLayout;
import com.truesim.effects.atmosphere.render.SkyViewLut;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Request;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.TextureFormat;
import com.truesim.engine.render.Textures;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Barriers;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.FrameSlots;
import com.truesim.engine.vulkan.Pipeline;
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


    /** Storm cells and brushes the GPU's field takes (field.slang's MAX_CELLS, MAX_BRUSHES). */
    static final int MAX_CELLS = 64;
    static final int MAX_BRUSHES = 32;


    private final Textures textures;
    private final Weather weather;
    private final CloudTypes types;
    private final FrameBuffer frame;
    private final Arena arena = Arena.ofShared();
    private final int nearTexture;
    private final int farTexture;
    private final int nearTypes;
    private final int farTypes;
    private final int nearCells;
    private final int farCells;
    /** Map texels per occupancy texel (occupancy.slang): the near map's, the far map's. */
    static final int NEAR_BLOCK = 8;
    static final int FAR_BLOCK = 2;
    private final int nearBlocks;
    private final int nearOccupancy;
    private final int farBlocks;
    private final int farOccupancy;
    private final StormTowers towers;
    private int cellCount;
    private float mammaFactor;
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
    private double frameSeconds;
    private final double[] motion = new double[3];
    private double lastDriftEast = Double.NaN;
    private double lastDriftNorth;
    private boolean flashLit;
    private double units = 1;
    private com.truesim.effects.atmosphere.render.Atmosphere tables;
    private float anvilTop;
    private double anvilColumn;
    private double anvilThickness;
    private boolean rainAny;

    /**
     * The anvil's mean density at full cover (clouds.slang's anvil profile, smoothstep(0, 0.12, h) times
     * 1 - smoothstep(0.92, 1, h), times its fibres' mean, lerp(0.65, 1, fibre) with the Worley fbm's mean of about
     * 0.5).
     */
    static final double ANVIL_MEAN_DENSITY = anvilMeanDensity();

    private static double anvilMeanDensity() {
        double sum = 0;
        int n = 256;
        for (int i = 0; i < n; i++) {
            double h = (i + 0.5) / n;
            double a = Math.clamp(h / 0.12, 0, 1);
            double b = Math.clamp((h - 0.92) / 0.08, 0, 1);
            sum += a * a * (3 - 2 * a) * (1 - b * b * (3 - 2 * b));
        }
        return 0.825 * sum / n;
    }
    private final double[] skyLight = new double[3];
    private final double[] sunAbove = new double[3]; // the atmosphere's transmittance to the sun at the clouds, 2 km
    private boolean skyLightRead;

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
        nearCells = textures.dynamic(TextureFormat.RGBA16F, nearSize, nearSize, 1, true);
        farCells = textures.dynamic(TextureFormat.RGBA16F, farSize, farSize, 1, true);
        nearBlocks = textures.dynamic(TextureFormat.RGBA16F, nearSize / NEAR_BLOCK, nearSize / NEAR_BLOCK, 1, true);
        nearOccupancy = textures.dynamic(TextureFormat.RGBA16F, nearSize / NEAR_BLOCK, nearSize / NEAR_BLOCK, 1, true);
        farBlocks = textures.dynamic(TextureFormat.RGBA16F, farSize / FAR_BLOCK, farSize / FAR_BLOCK, 1, true);
        farOccupancy = textures.dynamic(TextureFormat.RGBA16F, farSize / FAR_BLOCK, farSize / FAR_BLOCK, 1, true);
        towers = new StormTowers(MAX_CELLS);
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

    /**
     * The sky's illuminance on a horizontal surface at the camera from {@code atmosphere}'s sky-view table (a shared
     * request: weather/skylight.slang), read back into the next {@link #update}s' sky light: the clouds' ambient and
     * the light under them, by day and in twilight.
     */
    public Request<Buffer> skyLight(com.truesim.effects.atmosphere.render.Atmosphere atmosphere) {
        return new SkyLight(this, atmosphere);
    }

    /** {@link #skyLight}: one dispatch of 64 threads and a readback of 16 bytes. */
    private record SkyLight(WeatherGpu gpu, com.truesim.effects.atmosphere.render.Atmosphere atmosphere)
        implements Request<Buffer> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Buffer build(GraphBuilder g) {
            SkyViewLut.Images sky = g.request(new SkyViewLut(atmosphere));
            Pipeline program = g.computeProgram("shaders/weather/skylight.spv", "skyLightMain", SkyLightPush.BYTES);
            SkyLightPush push = new SkyLightPush();
            Buffer out = g.createBuffer("cloud sky light", 16);
            int width = sky.skyView().desc().width();
            int height = sky.skyView().desc().height();
            g.pass("cloud sky light").read(sky.skyView(), Access.SAMPLED_COMPUTE)
                .write(out, BufferAccess.STORAGE_WRITE).record(ctx -> ctx.dispatch(program,
                    push.atmosphere(atmosphere.frameAddress()).output(ctx.address(out))
                        .skyView(ctx.sampled(sky.skyView())).size(width | height << 16), 1, 1, 1));
            WeatherGpu w = gpu;
            g.readback("cloud sky light readback", out, 0, 16, (data, frame) -> {
                for (int c = 0; c < 3; c++) {
                    w.skyLight[c] = data.getAtIndex(ValueLayout.JAVA_FLOAT, c);
                }
                w.skyLightRead = true;
            });
            return out;
        }
    }

    /** The weather maps of the frame, evaluated on the GPU (a shared request); read its buffer to come after them. */
    public Request<Buffer> maps() {
        return new Maps(this);
    }

    /**
     * {@link #maps}: the field evaluated over the near and far maps, every frame (field.slang), then where clouds can be
     * over blocks of them for the march's empty-space skipping (occupancy.slang: each block's lowest base and highest
     * top, dilated by a block).
     */
    private record Maps(WeatherGpu gpu) implements Request<Buffer> {

        @Override
        @AllowAllocation("building the graph, when it changes")
        public Buffer build(GraphBuilder g) {
            WeatherGpu w = gpu;
            Pipeline program = g.computeProgram("shaders/weather/field.spv", "fieldMain", FieldPush.BYTES);
            Pipeline blocks = g.computeProgram("shaders/weather/occupancy.spv", "blockMain", OccupancyPush.BYTES);
            Pipeline dilate = g.computeProgram("shaders/weather/occupancy.spv", "dilateMain", OccupancyPush.BYTES);
            FieldPush push = new FieldPush();
            OccupancyPush occupancy = new OccupancyPush();
            Buffer ready = g.createBuffer("weather maps ready", 16);
            g.pass("weather maps").write(ready, BufferAccess.TRANSFER_DST).record(ctx -> {
                ctx.fill(ready, 1);
                // Outside the graph: the frame before's readers first, this frame's writes visible to the readers.
                Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
                ctx.dispatch(program, push.inputs(w.inputs.deviceAddress()).map(w.textures.storageSlot(w.nearTexture))
                    .types(w.textures.storageSlot(w.nearTypes)).cells(w.textures.storageSlot(w.nearCells))
                    .size(w.nearSize).extent((float) w.nearExtent).centre(0, 0).period(WeatherField.noisePeriod()),
                    (w.nearSize + 7) / 8, (w.nearSize + 7) / 8, 1);
                ctx.dispatch(program, push.inputs(w.inputs.deviceAddress()).map(w.textures.storageSlot(w.farTexture))
                    .types(w.textures.storageSlot(w.farTypes)).cells(w.textures.storageSlot(w.farCells))
                    .size(w.farSize).extent((float) w.farExtent)
                    .centre((float) (w.farCentreX - w.anchorX), (float) (w.farCentreZ - w.anchorZ))
                    .period(WeatherField.noisePeriod()), (w.farSize + 7) / 8, (w.farSize + 7) / 8, 1);
                Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                int nb = w.nearSize / NEAR_BLOCK;
                int fb = w.farSize / FAR_BLOCK;
                ctx.dispatch(blocks, occupancy.frame(w.address()).map(w.nearTexture & 0xffff)
                    .types(w.nearTypes & 0xffff).cells(w.nearCells & 0xffff)
                    .output(w.textures.storageSlot(w.nearBlocks)).block(NEAR_BLOCK).size(nb), (nb + 7) / 8,
                    (nb + 7) / 8, 1);
                ctx.dispatch(blocks, occupancy.frame(w.address()).map(w.farTexture & 0xffff)
                    .types(w.farTypes & 0xffff).cells(w.farCells & 0xffff)
                    .output(w.textures.storageSlot(w.farBlocks)).block(FAR_BLOCK).size(fb), (fb + 7) / 8,
                    (fb + 7) / 8, 1);
                Barriers.memory(ctx.unsafe().commands(), VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
                    VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);
                ctx.dispatch(dilate, occupancy.input(w.nearBlocks & 0xffff)
                    .output(w.textures.storageSlot(w.nearOccupancy)).size(nb), (nb + 7) / 8, (nb + 7) / 8, 1);
                ctx.dispatch(dilate, occupancy.input(w.farBlocks & 0xffff)
                    .output(w.textures.storageSlot(w.farOccupancy)).size(fb), (fb + 7) / 8, (fb + 7) / 8, 1);
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

    /**
     * The sky's illuminance on a horizontal surface the last {@link #update} lit the clouds with (scene units, RGB,
     * into {@code out}): effects-atmosphere's sky integrated over the hemisphere where it lights them, else the
     * lighting's estimate.
     */
    public void skyLight(double[] out) {
        System.arraycopy(sky, 0, out, 0, 3);
    }

    /** The weather seconds the last {@link #update}'s frame spans. */
    public double frameSeconds() {
        return frameSeconds;
    }

    /** How far the clouds moved over the last frame (metres, world axis {@code axis}: the wind, their evolution). */
    public double motion(int axis) {
        return motion[axis];
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

    /** Scene units per cd/m² of this frame's lighting ({@link CloudLighting#units}). */
    public double units() {
        return units;
    }

    /**
     * effects-atmosphere's atmosphere lighting the clouds through its tables ({@link CloudLighting#tables}), as the
     * last {@link #update} was given it, or null. Views built after the first update use it.
     */
    public com.truesim.effects.atmosphere.render.Atmosphere tables() {
        return tables;
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
        this.frameSeconds = frameSeconds;
        // How far the clouds moved since the frame before: the wind's drift of the noise and the maps, and the
        // noise's evolution upwards (the clouds' history is read where they were).
        double driftEast = weather.driftEast();
        double driftNorth = weather.driftNorth();
        boolean first = Double.isNaN(lastDriftEast);
        motion[0] = first ? 0 : driftEast - lastDriftEast;
        motion[1] = first ? 0 : frameSeconds * settings.get(CloudSettings.EVOLUTION);
        motion[2] = first ? 0 : -(driftNorth - lastDriftNorth);
        lastDriftEast = driftEast;
        lastDriftNorth = driftNorth;
        WeatherConfig config = weather.config();
        // The maps centred on the camera's near-map tile, snapped to their texels so texels keep their ground.
        WeatherGrid near = weather.nearMap();
        double nearTile = near.tileTexels() * near.texelSize();
        anchorX = near.centreTileX() * nearTile;
        anchorZ = near.centreTileZ() * nearTile;
        double farTexel = farExtent / farSize;
        farCentreX = Math.floor(anchorX / farTexel) * farTexel;
        farCentreZ = Math.floor(anchorZ / farTexel) * farTexel;
        FrameBuffer f = frame;
        float top = writeInputs(settings);

        tables = lighting.tables();
        lighting.sun(sun, sunLight);
        lighting.sky(sky);
        if (tables != null) {
            tables.transmittance(0, 2, sunAbove);
        } else {
            CloudLighting.Atmosphere air = lighting.atmosphere();
            for (int c = 0; c < 3; c++) {
                sunAbove[c] = sun[1] > -0.2 ? air.transmittance(c, 2000, sun[1]) : 0;
            }
        }
        if (tables != null && skyLightRead) {
            // effects-atmosphere's sky integrated over the hemisphere (skyLight), read back a frame or two late.
            System.arraycopy(skyLight, 0, sky, 0, 3);
        }
        lighting.horizon(horizon);
        put3(f, CloudFrameLayout.SUN_DIRECTION, sun[0], sun[1], sun[2]);
        f.putFloat(CloudFrameLayout.SUN_VISIBLE, sun[1] > -0.2 ? 1 : 0);
        put3(f, CloudFrameLayout.SUN_ILLUMINANCE, sunLight[0], sunLight[1], sunLight[2]);
        put3(f, CloudFrameLayout.SKY_ILLUMINANCE, sky[0], sky[1], sky[2]);
        put3(f, CloudFrameLayout.AP_RADIANCE, horizon[0], horizon[1], horizon[2]);
        f.putFloat(CloudFrameLayout.GROUND_ALBEDO, settings.get(CloudSettings.GROUND_ALBEDO));
        f.putFloat(CloudFrameLayout.MAX_ALTITUDE, top);
        // Around the focus (the camera), not the anchor: the anchor jumps a tile at a time, and with it the map, so
        // that clouds near its edge changed between its shadows and the far estimates from one frame to the next.
        placeShadowMap(weather.focus(0) - anchorX, weather.focus(2) - anchorZ,
            settings.get(CloudSettings.SHADOW_EXTENT), shadowSize);

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
        f.putFloat(CloudFrameLayout.RAIN_SLANT, settings.get(CloudSettings.RAIN_SLANT));
        f.putFloat(CloudFrameLayout.RAIN_CURVE, settings.get(CloudSettings.RAIN_CURVE));
        f.putFloat(CloudFrameLayout.RAIN_EVAPORATION, settings.get(CloudSettings.RAIN_EVAPORATION));
        f.putFloat(CloudFrameLayout.RAIN_TAPER, settings.get(CloudSettings.RAIN_TAPER));
        f.putInt(CloudFrameLayout.RAIN_ANY, rainAny ? 1 : 0);
        f.putFloat(CloudFrameLayout.BILLOW_SCALE, settings.get(CloudSettings.BILLOW_SCALE));
        // The anvils: ice of the settings' water content and crystal size, sigma = 3 IWC / (2 rho_ice r_eff), over
        // the anvil profile's mean density at full cover (as the types' extinction at density 1 is).
        double diameter = settings.get(CloudSettings.ANVIL_DIAMETER);
        double ice = 3 * settings.get(CloudSettings.ANVIL_ICE) * 1e-3 / (2 * 917.0 * diameter * 0.5e-6);
        float thickness = settings.get(CloudSettings.ANVIL_THICKNESS);
        f.putFloat(CloudFrameLayout.ANVIL_TOP, anvilTop);
        f.putFloat(CloudFrameLayout.ANVIL_THICKNESS, thickness);
        f.putFloat(CloudFrameLayout.ANVIL_EXTINCTION, (float) (ice / ANVIL_MEAN_DENSITY));
        f.putFloat(CloudFrameLayout.ANVIL_COLUMN, (float) ice);
        anvilColumn = ice;
        anvilThickness = thickness;
        f.putFloat(CloudFrameLayout.ANVIL_DIAMETER, (float) diameter);
        // The storm cells' towers: liquid water of the cumulonimbus type's (art applied), glaciated tops of the
        // settings' ice in the anvil's crystals.
        CloudProfile cb = types.profile(CloudType.CUMULONIMBUS);
        f.putFloat(CloudFrameLayout.TOWER_WATER, (float) types.art().extinction(cb.extinction()));
        f.putFloat(CloudFrameLayout.TOWER_ICE, (float) (3 * settings.get(CloudSettings.TOWER_ICE) * 1e-3
            / (2 * 917.0 * diameter * 0.5e-6)));
        f.putFloat(CloudFrameLayout.TOWER_LEAN, settings.get(CloudSettings.TOWER_LEAN));
        f.putFloat(CloudFrameLayout.TURRET_PERIOD, settings.get(CloudSettings.TURRET_PERIOD));
        f.putFloat(CloudFrameLayout.TURRETS, settings.get(CloudSettings.TURRETS));
        f.putFloat(CloudFrameLayout.GLACIATION_ALTITUDE, settings.get(CloudSettings.GLACIATION));
        // Mamma hang from anvils that have spread (older than half their spread): the frame's most spread anvil's.
        f.putFloat(CloudFrameLayout.MAMMATUS, settings.get(CloudSettings.MAMMATUS) * mammaFactor);
        f.putFloat(CloudFrameLayout.LOD_BAND, settings.get(CloudSettings.LOD_BAND));
        f.putFloat(CloudFrameLayout.VIRGA, settings.get(CloudSettings.VIRGA));
        f.putInt(CloudFrameLayout.DEBUG, settings.get(CloudSettings.DEBUG) | (settings.get(CloudSettings.AIR_SHADOW)
            ? 32 : 0));
        f.putFloat(CloudFrameLayout.VIRGA_DEPTH, settings.get(CloudSettings.VIRGA_DEPTH));
        f.putInt(CloudFrameLayout.NEAR_CELLS, nearCells & 0xffff);
        f.putInt(CloudFrameLayout.FAR_CELLS, farCells & 0xffff);
        f.putInt(CloudFrameLayout.CELL_COUNT, cellCount);
        f.putInt(CloudFrameLayout.NEAR_OCCUPANCY, nearOccupancy & 0xffff);
        f.putInt(CloudFrameLayout.FAR_OCCUPANCY, farOccupancy & 0xffff);
        f.putInt(CloudFrameLayout.NEAR_BLOCK, NEAR_BLOCK);
        f.putInt(CloudFrameLayout.FAR_BLOCK, FAR_BLOCK);

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
        units = lighting.units();
        flashes.select(events, config.lightning(), now - frameSeconds, now,
            settings.get(CloudSettings.FLASH_INTENSITY) * units,
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
        // Snapped along its own axes in world coordinates, so the texels stay put as the camera moves and as the
        // anchor jumps.
        double wx = cx + anchorX;
        double wz = cz + anchorZ;
        double su = Math.floor((wx * ux + wz * uz) / texel) * texel - (anchorX * ux + anchorZ * uz);
        double sv = Math.floor((wx * vx + wz * vz) / texel) * texel - (anchorX * vx + anchorZ * vz);
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
     * storm cells and the brushes, from the anchor; and the storm cells' towers into the frame record (clouds.slang's
     * StormCell, in the same order: the maps' cell lists index them); returns the highest cloud top they can make,
     * metres.
     */
    private float writeInputs(EngineSettings settings) {
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
        in.putFloat(FieldInputsLayout.ANVIL_WIDTH, settings.get(CloudSettings.ANVIL_WIDTH));
        in.putFloat(FieldInputsLayout.ANVIL_LENGTH, settings.get(CloudSettings.ANVIL_LENGTH));
        put3(in, FieldInputsLayout.NOISE_ORIGIN, wrap((anchorX - field.offset(0)) / scale, period),
            wrap(-field.offset(1) / scale, period), wrap((anchorZ - field.offset(2)) / scale, period));
        float top = (float) Math.max(state.topHeight(), Math.max(state.from().cloudType() == null ? 0
            : types.profile(state.from().cloudType()).top(), types.profile(state.target().cloudType()).top()));
        StormSimulation storms = weather.storms();
        int cells = 0;
        float anvils = 0;
        boolean rain = state.precipitation() > 0;
        towers.highest = 0;
        mammaFactor = 0;
        if (storms != null) {
            WeatherConfig config = weather.config();
            // The steering wind's direction, for the flanking lines (x east, z south).
            double east = config.wind().east(config.advectionAltitude());
            double north = config.wind().north(config.advectionAltitude());
            double speed = Math.sqrt(east * east + north * north);
            double wx = speed > 0.5 ? east / speed : 0;
            double wz = speed > 0.5 ? -north / speed : 0;
            // The frame's time between the simulation's steps: the towers move on with it.
            double ahead = Math.clamp(time - weather.time(), 0, config.step());
            double spawn = config.storms().spawnRadius();
            double turretPeriod = settings.get(CloudSettings.TURRET_PERIOD);
            for (int s = 0; s < storms.capacity() && cells < MAX_CELLS; s++) {
                if (!storms.active(s)) {
                    continue;
                }
                double dx = storms.position(s, 0) - anchorX;
                double dz = storms.position(s, 2) - anchorZ;
                // Far from the focus, where the simulation drops cells, they fade out first.
                double fade = 1 - smooth((Math.sqrt(dx * dx + dz * dz) - 1.2 * spawn) / (0.25 * spawn));
                towers.update(cells, storms, s, anchorX, anchorZ, ahead, wx, wz, fade,
                    settings.get(CloudSettings.GLACIATION), settings.get(CloudSettings.ANVIL_OVERSHOOT),
                    settings.get(CloudSettings.TOWER_LEAN), settings.get(CloudSettings.TURRET_RISE) / turretPeriod);
                long o = FieldInputsLayout.CELL + (long) cells * FieldCellLayout.BYTES;
                in.putFloat(o + FieldCellLayout.CORE, (float) dx);
                in.putFloat(o + FieldCellLayout.CORE + 4, (float) dz);
                in.putFloat(o + FieldCellLayout.CORE_RADIUS, (float) storms.coreRadius(s));
                in.putFloat(o + FieldCellLayout.COVERAGE, (float) storms.coverage(s));
                in.putFloat(o + FieldCellLayout.PRECIPITATION, (float) storms.precipitation(s));
                in.putFloat(o + FieldCellLayout.REACH, towers.reach[cells]);
                in.putFloat(o + FieldCellLayout.TOP, towers.anvilTop[cells]);
                in.putFloat(o + FieldCellLayout.ANVIL, towers.anvil[cells]);
                in.putFloat(o + FieldCellLayout.ANVIL_CENTRE, (float) (storms.anvilPosition(s, 0) - anchorX));
                in.putFloat(o + FieldCellLayout.ANVIL_CENTRE + 4, (float) (storms.anvilPosition(s, 2) - anchorZ));
                in.putFloat(o + FieldCellLayout.ANVIL_RADIUS, (float) storms.anvilRadius(s));
                writeCell(cells);
                if (towers.anvil[cells] > 0) {
                    anvils = Math.max(anvils, towers.anvilTop[cells]);
                }
                mammaFactor = (float) Math.max(mammaFactor, smooth((towers.anvil[cells] - 0.5) / 0.4));
                rain |= storms.precipitation(s) > 0;
                cells++;
            }
            top = Math.max(top, towers.highest);
        }
        in.putInt(FieldInputsLayout.CELLS, cells);
        cellCount = cells;
        anvilTop = anvils;
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
            rain |= b.mode() != WeatherBrush.Mode.CLEAR && b.precipitation() > 0;
            if (b.mode() != WeatherBrush.Mode.CLEAR) {
                top = (float) Math.max(top, Math.max(b.topHeight(), types.profile(b.cloudType()).top()));
            }
        }
        in.putInt(FieldInputsLayout.BRUSHES, count);
        rainAny = rain;
        return top;
    }

    /** Cell {@code c} of {@link #towers} into the frame record's cells. */
    private void writeCell(int c) {
        FrameBuffer f = frame;
        long o = CloudFrameLayout.CELLS + (long) c * StormCellLayout.BYTES;
        int t = 4 * StormTowers.TOWERS * c;
        f.putFloat(o + StormCellLayout.CORE, towers.towers[t]);
        f.putFloat(o + StormCellLayout.CORE + 4, towers.towers[t + 1]);
        f.putFloat(o + StormCellLayout.BASE, towers.base[c]);
        f.putFloat(o + StormCellLayout.REACH, towers.reach[c]);
        f.putFloat(o + StormCellLayout.GLACIATION, towers.glaciation[c]);
        f.putFloat(o + StormCellLayout.FIBRES, towers.fibres[c]);
        f.putFloat(o + StormCellLayout.WATER, towers.water[c]);
        f.putFloat(o + StormCellLayout.OVERSHOOT, towers.overshoot[c]);
        put3(f, o + StormCellLayout.NOISE, towers.noise[3 * c], fraction(towers.noise[3 * c + 1]),
            towers.noise[3 * c + 2]);
        f.putInt(o + StormCellLayout.TOWERS, towers.count[c]);
        f.putFloat(o + StormCellLayout.CEILING, towers.ceiling[c]);
        f.putFloat(o + StormCellLayout.FLARE, towers.flare[c]);
        for (int k = 0; k < StormTowers.TOWERS; k++) {
            long q = o + StormCellLayout.TOWER + 16L * k;
            for (int i = 0; i < 4; i++) {
                f.putFloat(q + 4L * i, towers.towers[t + 4 * k + i]);
            }
        }
    }

    private static double smooth(double x) {
        double t = Math.clamp(x, 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    /** The optical depth of a column of {@code type} at {@code coverage} under a top channel of {@code top}. */
    private double column(CloudType type, double coverage, double top) {
        CloudProfile p = types.profile(type);
        if (type == CloudType.CIRRUS && anvilTop > 0 && top > p.top() + 500) {
            // The field's anvil (gameplay's cirrus under a storm's top): the anvil's ice, as the clouds draw it.
            return coverage * anvilColumn * anvilThickness;
        }
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
            out[c] = sky[c] * (1 - cover) + (sunLight[c] * sunAbove[c] * mu + sky[c]) * diffuse;
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
        textures.remove(nearCells);
        textures.remove(farCells);
        textures.remove(nearBlocks);
        textures.remove(nearOccupancy);
        textures.remove(farBlocks);
        textures.remove(farOccupancy);
        textures.remove(atlasTexture);
        frame.close();
        inputs.close();
        arena.close();
    }
}
