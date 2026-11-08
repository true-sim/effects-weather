package com.truesim.effects.weather.render;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.Weather;
import com.truesim.effects.weather.WeatherConfig;
import com.truesim.effects.weather.WeatherGrid;
import com.truesim.effects.weather.clouds.CloudProfile;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.clouds.CloudTypes;
import com.truesim.effects.weather.clouds.CloudsConfig;
import com.truesim.effects.weather.lightning.LightningEvents;
import com.truesim.effects.weather.render.layout.CloudFrameLayout;
import com.truesim.effects.weather.render.layout.CloudTypeLayout;
import com.truesim.effects.weather.render.layout.CloudsConstants;
import com.truesim.effects.weather.render.layout.FlashLayout;
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

    private static final ValueLayout.OfShort HALF = ValueLayout.JAVA_SHORT_UNALIGNED;

    private final Textures textures;
    private final Weather weather;
    private final CloudTypes types;
    private final FrameBuffer frame;
    private final Arena arena = Arena.ofShared();
    private final int nearTexture;
    private final int farTexture;
    private final int atlasTexture;
    private final MemorySegment tile;
    private final int[] nearUploaded;
    private final int[] farUploaded;
    private final float[] nearTops;
    private final float[] farTops;
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
        nearTexture = textures.dynamic(TextureFormat.RGBA16F, config.nearSize(), config.nearSize(), 1, false);
        farTexture = textures.dynamic(TextureFormat.RGBA16F, config.farSize(), config.farSize(), 1, false);
        int tt = config.tileTexels();
        tile = arena.allocate(8L * tt * Math.max(config.nearSize(), config.farSize()), 8);
        WeatherGrid near = weather.nearMap();
        WeatherGrid far = weather.farMap();
        nearUploaded = new int[near.tilesPerEdge() * near.tilesPerEdge()];
        farUploaded = new int[far.tilesPerEdge() * far.tilesPerEdge()];
        nearTops = new float[nearUploaded.length];
        farTops = new float[farUploaded.length];
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
     * Uploads the map tiles that changed and writes the frame's record: the weather at {@code now} seconds of its time
     * (usually {@link Weather#time()}), the frame lasting {@code frameSeconds} (the flashes' mean over it), lit by
     * {@code lighting}, tuned by {@code settings}.
     */
    public void update(double now, double frameSeconds, CloudLighting lighting, EngineSettings settings) {
        time = now;
        WeatherConfig config = weather.config();
        WeatherGrid near = weather.nearMap();
        WeatherGrid far = weather.farMap();
        upload(near, nearTexture, nearUploaded, nearTops);
        upload(far, farTexture, farUploaded, farTops);
        double nearTile = near.tileTexels() * near.texelSize();
        double farTile = far.tileTexels() * far.texelSize();
        anchorX = near.centreTileX() * nearTile;
        anchorZ = near.centreTileZ() * nearTile;
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
        float top = 0;
        for (float t : nearTops) {
            top = Math.max(top, t);
        }
        for (float t : farTops) {
            top = Math.max(top, t);
        }
        f.putFloat(CloudFrameLayout.MAX_ALTITUDE, top);
        placeShadowMap(0, 0, settings.get(CloudSettings.SHADOW_EXTENT), shadowSize);

        // The maps: their slots, where they hold the weather and where the anchor falls in them (toroidal).
        f.putInt(CloudFrameLayout.NEAR_MAP, nearTexture & 0xffff);
        f.putInt(CloudFrameLayout.FAR_MAP, farTexture & 0xffff);
        f.putInt(CloudFrameLayout.PROFILE_ATLAS, atlasTexture & 0xffff);
        f.putInt(CloudFrameLayout.PROFILE_HEIGHTS, PROFILE_HEIGHTS);
        bounds(f, CloudFrameLayout.NEAR_BOUNDS, near, nearTile, 0, 0);
        double farCentreX = far.centreTileX() * farTile;
        double farCentreZ = far.centreTileZ() * farTile;
        bounds(f, CloudFrameLayout.FAR_BOUNDS, far, farTile, farCentreX - anchorX, farCentreZ - anchorZ);
        double nearExtent = near.size() * near.texelSize();
        double farExtent = far.size() * far.texelSize();
        f.putFloat(CloudFrameLayout.NEAR_ANCHOR, (float) fraction(anchorX / nearExtent));
        f.putFloat(CloudFrameLayout.NEAR_ANCHOR + 4, (float) fraction(anchorZ / nearExtent));
        f.putFloat(CloudFrameLayout.FAR_ANCHOR, (float) fraction(anchorX / farExtent));
        f.putFloat(CloudFrameLayout.FAR_ANCHOR + 4, (float) fraction(anchorZ / farExtent));
        f.putFloat(CloudFrameLayout.NEAR_EXTENT, (float) nearExtent);
        f.putFloat(CloudFrameLayout.FAR_EXTENT, (float) farExtent);
        f.putInt(CloudFrameLayout.NEAR_SIZE, near.size());
        f.putInt(CloudFrameLayout.FAR_SIZE, far.size());

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
            f.putFloat(o + CloudTypeLayout.EXTINCTION, (float) types.art().extinction(p.extinction()));
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

    private void bounds(FrameBuffer f, long o, WeatherGrid grid, double tileMetres, double cx, double cz) {
        int tiles = grid.tilesPerEdge();
        double lo = -(tiles / 2) * tileMetres + grid.texelSize();
        double hi = (tiles - tiles / 2) * tileMetres - grid.texelSize();
        f.putFloat(o, (float) (cx + lo));
        f.putFloat(o + 4, (float) (cz + lo));
        f.putFloat(o + 8, (float) (cx + hi));
        f.putFloat(o + 12, (float) (cz + hi));
    }

    /**
     * Uploads {@code grid}'s tiles whose versions changed into dynamic texture {@code texture}: runs of changed tiles
     * along a row of tiles as one region each (a slot's tile lies at the slot's place in the texture, as the grid
     * wraps), at most {@link #MAX_REGIONS} regions a frame, the rest the next frames.
     */
    private void upload(WeatherGrid grid, int texture, int[] uploaded, float[] tops) {
        int tiles = grid.tilesPerEdge();
        int tt = grid.tileTexels();
        int size = grid.size();
        float[] data = grid.data();
        int regions = 0;
        for (int sz = 0; sz < tiles && regions < MAX_REGIONS; sz++) {
            int sx = 0;
            while (sx < tiles && regions < MAX_REGIONS) {
                if (!changed(grid, uploaded, sx, sz, tiles)) {
                    sx++;
                    continue;
                }
                int first = sx;
                while (sx < tiles && changed(grid, uploaded, sx, sz, tiles)) {
                    sx++;
                }
                int run = sx - first;
                int x0 = first * tt;
                int z0 = sz * tt;
                int texels = run * tt;
                for (int j = 0; j < tt; j++) {
                    int row = 4 * ((z0 + j) * size + x0);
                    long to = (long) j * 4 * texels;
                    for (int i = 0; i < 4 * texels; i++) {
                        tile.setAtIndex(HALF, to + i, Float.floatToFloat16(data[row + i]));
                    }
                }
                if (!textures.update(texture, x0, z0, 0, texels, tt, 1, tile, 0, 8L * texels, 8L * texels * tt)) {
                    return; // no room this frame: the rest next frame
                }
                regions++;
                for (int t = first; t < first + run; t++) {
                    int slot = sz * tiles + t;
                    uploaded[slot] = grid.tileVersion(t, sz);
                    tops[slot] = top(data, size, t * tt, z0, tt);
                }
            }
        }
    }

    private static boolean changed(WeatherGrid grid, int[] uploaded, int sx, int sz, int tiles) {
        int version = grid.tileVersion(sx, sz);
        return version != 0 && version != uploaded[sz * tiles + sx];
    }

    /** The highest cloud top in the tile at texel (x0, z0): its top channel, or its type's top, where it has cover. */
    private float top(float[] data, int size, int x0, int z0, int tt) {
        float top = 0;
        for (int j = 0; j < tt; j++) {
            for (int i = 0; i < tt; i++) {
                int o = 4 * ((z0 + j) * size + x0 + i);
                if (data[o] > 0) {
                    float typeTop = (float) types.profile(CloudType.fromChannel(data[o + 1])).top();
                    top = Math.max(top, Math.max(data[o + 3], typeTop));
                }
            }
        }
        return top;
    }

    private static int wanted(int slotCoord, int centre, int tiles) {
        int base = centre - tiles / 2;
        return base + Math.floorMod(slotCoord - base, tiles);
    }

    private static double fraction(double v) {
        return v - Math.floor(v);
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
        textures.remove(farTexture);
        textures.remove(atlasTexture);
        frame.close();
        arena.close();
    }
}
