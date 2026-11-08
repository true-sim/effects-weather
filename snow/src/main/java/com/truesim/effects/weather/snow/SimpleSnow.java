package com.truesim.effects.weather.snow;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT;

import com.truesim.annotations.AllowAllocation;
import com.truesim.annotations.AllowImpure;
import com.truesim.common.graph.Id;
import com.truesim.effects.weather.snow.hooks.SnowMapPush;
import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.PassContext;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.MaterialField;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadingHookParameters;
import com.truesim.engine.render.ShadingHookSource;
import com.truesim.engine.render.ShadingHooks;
import com.truesim.engine.render.SurfaceOutput;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.settings.EngineSettings;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.FrameBuffer;
import com.truesim.engine.vulkan.Pipeline;
import com.truesim.engine.vulkan.VulkanDevice;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * Simple snow for a view (effects-weather#4, part 1): the {@code weather.snow} surface hook (weather/snow.slang) with
 * the scene's values for it ({@link SnowParameters}: coverage, noise, slopes, the snow's look, snowfall and melt;
 * engine-scene's {@code ShadingHookComponent}, {@link SnowContent}), and what only the frame knows:
 * <ul>
 *   <li>a top-down occlusion map: a depth-only draw of the scene's geometry by a render view of its own, looking along
 *   the fall direction over a square of {@link SnowSettings#AREA} round the camera, every frame; a point below the
 *   topmost surface is under a roof, an overhang or a tree, and gets no snow;</li>
 *   <li>an accumulation, a texel per world column of that square, kept while the column stays in it: snow fills in
 *   while it snows ({@link SnowSettings#FILL_SECONDS}), stays on what something drives over, is bare where a parked
 *   car leaves, melts ({@link SnowSettings#MELT_SECONDS}).</li>
 * </ul>
 *
 * <pre>{@code
 * SimpleSnow snow = new SimpleSnow(geometry, main);
 * port.add(snow);
 * // each frame, before the frame records:
 * snow.update(frameSeconds);
 * }</pre>
 *
 * <p>The map follows the view's camera and the fall direction of the last frame's values. One per view; closing it
 * frees its render view and buffer.
 */
public final class SimpleSnow implements Technique, AutoCloseable {

    /** The hook's name: its {@code [ShadingHook]}. */
    public static final String HOOK = "weather.snow";
    /** The technique's id. */
    public static final Id<SimpleSnow> ID = Id.of("truesim.weather.snow", SimpleSnow.class);

    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    /** The map's frame data (snowmap.slang's SnowMapFrame): five float4 rows. */
    private static final int FRAME_BYTES = 80;

    private final SceneGeometry geometry;
    private final SceneView view;
    private final MeshletRenderer renderer;
    private final VulkanDevice device;
    private final Camera map = new Camera();
    private RenderView mapView;
    private FrameBuffer frame;
    private EngineSettings settings;
    private boolean active = true;
    // As built
    private float area;
    private int mapSize;
    private int size;
    // The map as drawn: its origin (world, and the accumulation cell it is in)
    private boolean drawn;
    private double originX;
    private double originY;
    private double originZ;
    private int originCellX;
    private int originCellZ;
    private final float[] fall = {0, -1, 0};
    // The accumulation: its window (first cell), last frame's, whether there was one; seconds not yet accumulated
    private int windowX;
    private int windowZ;
    private int previousX;
    private int previousZ;
    private boolean fresh = true;
    private double pending;
    // The scene's values, as the last frame wrote them
    private float snowfall;
    private float melt;

    /** Snow on {@code view}, its map drawing {@code geometry} (as the view's shading does). */
    public SimpleSnow(SceneGeometry geometry, SceneView view) {
        this.geometry = Objects.requireNonNull(geometry, "geometry");
        this.view = Objects.requireNonNull(view, "view");
        this.renderer = view.view().renderer();
        this.device = renderer.device();
    }

    @Override
    public Id<SimpleSnow> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    /** It adds its hook to the view's: built before the view's shading. */
    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.SHADING_HOOKS);
    }

    /** Turns the snow on or off from the next frame (its bit of the view's hook block: no rebuild). */
    public SimpleSnow active(boolean on) {
        active = on;
        return this;
    }

    /**
     * The next frame: {@code seconds} of game time passed (snow accumulating and melting); the map follows the view's
     * camera. Call it each frame before the frame records.
     */
    public void update(double seconds) {
        if (!(seconds >= 0)) {
            throw new IllegalArgumentException("Time runs forwards: " + seconds);
        }
        pending += seconds;
        if (settings == null) {
            return; // not built yet: the map waits for the first build
        }
        Camera camera = view.camera();
        double texel = texel();
        // The accumulation's window: the cells round the camera's
        windowX = (int) Math.floor(camera.position(0) / texel) - size / 2;
        windowZ = (int) Math.floor(camera.position(2) / texel) - size / 2;
        // The map's axes as the camera builds them: back (against the fall), right, up; any up not along the fall
        double fx = fall[0];
        double fy = fall[1];
        double fz = fall[2];
        boolean alongZ = Math.abs(fz) > 0.9;
        double ux = alongZ ? 1 : 0;
        double uz = alongZ ? 0 : -1;
        double rx = -uz * -fy;
        double ry = uz * -fx - ux * -fz;
        double rz = ux * -fy;
        double rl = Math.sqrt(rx * rx + ry * ry + rz * rz);
        rx /= rl;
        ry /= rl;
        rz /= rl;
        double tx = -fy * rz + fz * ry;
        double ty = -fz * rx + fx * rz;
        double tz = -fx * ry + fy * rx;
        // The origin: the camera snapped to whole map texels across the fall, so that the map's texels lie on a grid
        // fixed in the world whatever the camera does (it moving finds the same surfaces in the same texels), and to
        // whole metres along it
        double mapTexel = 2.0 * area / mapSize;
        double cx = camera.position(0);
        double cy = camera.position(1);
        double cz = camera.position(2);
        double across = Math.rint((rx * cx + ry * cy + rz * cz) / mapTexel) * mapTexel;
        double up = Math.rint((tx * cx + ty * cy + tz * cz) / mapTexel) * mapTexel;
        double along = Math.rint(fx * cx + fy * cy + fz * cz);
        originX = across * rx + up * tx + along * fx;
        originY = across * ry + up * ty + along * fy;
        originZ = across * rz + up * tz + along * fz;
        originCellX = (int) Math.floor(originX / texel);
        originCellZ = (int) Math.floor(originZ / texel);
        double height = settings.get(SnowSettings.MAP_HEIGHT);
        map.position(originX, originY, originZ).setOrthographic(-fx * height, -fy * height, -fz * height, fx, fy, fz,
            ux, 0, uz, area, area, settings.get(SnowSettings.MAP_DEPTH), mapSize, mapSize);
        float[] vp = map.viewProjection();
        float[] inverse = map.inverseViewProjection();
        for (int column = 0; column < 4; column++) {
            frame.putFloat(4L * column, vp[4 * column]);
            frame.putFloat(16 + 4L * column, vp[4 * column + 1]);
            frame.putFloat(32 + 4L * column, inverse[4 * column + 1]);
            frame.putFloat(48 + 4L * column, inverse[4 * column]);
            frame.putFloat(64 + 4L * column, inverse[4 * column + 2]);
        }
        drawn = true;
    }

    private double texel() {
        return 2.0 * area / size;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    @AllowImpure("keeps what it was built with (settings, sizes, its render view and buffer): update and the passes use them")
    public void build(GraphBuilder g) {
        settings = g.settings();
        float newArea = g.setting(SnowSettings.AREA);
        int newSize = g.setting(SnowSettings.ACCUMULATION_RESOLUTION);
        if (newArea != area || newSize != size) {
            drawn = false; // another grid: the accumulation starts again
            fresh = true;
        }
        area = newArea;
        size = newSize;
        mapSize = g.setting(SnowSettings.MAP_RESOLUTION);
        if (mapView == null) {
            ViewOptions options = view.view().options();
            mapView = renderer.createView(ViewOptions.of(options.draws(), options.triangles()));
        }
        if (frame == null) {
            frame = FrameBuffer.create(device, FRAME_BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                | VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT, renderer.scene().frameSlots());
        }
        Image occlusion = g.persistent("snow occlusion", ImageDesc.of(Format.R32_SFLOAT, mapSize, mapSize));
        Image accumulation = g.persistent("snow accumulation", ImageDesc.of(Format.R32G32B32A32_SFLOAT, size,
            size));
        String shader = "shaders/weather/snowmap.spv";
        Pipeline capture = g.computeProgram(shader, "captureMain", SnowMapPush.BYTES);
        Pipeline gather = g.computeProgram(shader, "columnsMain", SnowMapPush.BYTES);
        Pipeline accumulate = g.computeProgram(shader, "accumulateMain", SnowMapPush.BYTES);
        int maps = mapSize;
        // Drawn every frame: what moves (cars, people) shelters the ground under it at once. (Skipping the draw on
        // frames where nothing moved, with GraphBuilder.masked, left the main view drawing black once the draw came
        // back: engine 0.192.0, see the PR.)
        SceneDraw draw = new SceneDraw(geometry, mapView, map, SurfaceOutput.depth(), renderer.depthFormat(), maps,
            maps, false);
        Image depth = g.request(draw).depth();
        SnowMapPush capturePush = new SnowMapPush();
        g.pass("snow occlusion").read(depth, Access.SAMPLED_COMPUTE).write(occlusion, Access.STORAGE_WRITE)
            .when(() -> drawn).record(ctx -> ctx.dispatch(capture, capturePush.depth(ctx.sampled(depth))
                .occlusion(ctx.storage(occlusion)).mapSize(maps), (maps + 7) / 8, (maps + 7) / 8, 1));
        // Each column's topmost reached surface: cleared, then every map texel's taken to its column
        Buffer columns = g.createBuffer("snow columns", 4L * size * size);
        g.pass("snow columns clear").write(columns, BufferAccess.TRANSFER_DST).when(() -> drawn)
            .record(ctx -> ctx.fill(columns, 0));
        SnowMapPush columnsPush = new SnowMapPush();
        g.pass("snow columns").read(occlusion, Access.SAMPLED_COMPUTE).write(columns, BufferAccess.STORAGE_WRITE)
            .when(() -> drawn).record(ctx -> ctx.dispatch(gather, columnsPush(ctx, columnsPush, occlusion, columns),
                (maps + 7) / 8, (maps + 7) / 8, 1));
        SnowMapPush push = new SnowMapPush();
        int texels = size;
        g.pass("snow accumulation").read(occlusion, Access.SAMPLED_COMPUTE).read(columns, BufferAccess.STORAGE_READ)
            .write(accumulation, Access.STORAGE_WRITE).when(() -> drawn).record(ctx -> {
                accumulate(ctx, push, occlusion, accumulation, columns);
                ctx.dispatch(accumulate, push, (texels + 7) / 8, (texels + 7) / 8, 1);
            });
        ShadingHooks.of(device).add(g, HOOK, new ShadingHookSource() {
            @Override
            public int bytes() {
                return SnowParameters.BYTES;
            }

            @Override
            public boolean active() {
                return active;
            }

            @Override
            public void write(PassContext ctx, ShadingHookParameters authored, MemorySegment into, long offset) {
                authored.write(into, offset);
                take((SnowParameters) authored);
                if (!drawn) {
                    // No map yet (the first frame): no snow rather than snow under every roof
                    into.set(F32, offset + SnowParameters.COVERAGE.offset(), 0f);
                    return;
                }
                into.set(I32, offset + SnowParameters.MAP.offset(), ctx.sampled(occlusion));
                into.set(I32, offset + SnowParameters.MAP_SIZE.offset(), mapSize);
                into.set(I32, offset + SnowParameters.ACCUMULATION.offset(), ctx.sampled(accumulation));
                into.set(I32, offset + SnowParameters.ACCUMULATION_SIZE.offset(), size);
                into.set(F32, offset + SnowParameters.BIAS.offset(),
                    settings.get(SnowSettings.BIAS) / settings.get(SnowSettings.MAP_DEPTH));
                into.set(F32, offset + SnowParameters.ACCUMULATION_TEXEL.offset(), (float) texel());
                // The origin's cell and its offset in it, in texels: the shading's position over the texel is then the
                // world's, whatever the origin
                double texel = texel();
                put(into, offset, SnowParameters.ACCUMULATION_OFFSET, 0,
                    (float) (Math.floorMod(originCellX, size) + (originX - originCellX * texel) / texel));
                put(into, offset, SnowParameters.ACCUMULATION_OFFSET, 1,
                    (float) (Math.floorMod(originCellZ, size) + (originZ - originCellZ * texel) / texel));
                origin(into, offset, 0, originX);
                origin(into, offset, 1, originY);
                origin(into, offset, 2, originZ);
                float[] vp = map.viewProjection();
                for (int column = 0; column < 4; column++) {
                    put(into, offset, SnowParameters.TO_MAP_X, column, vp[4 * column]);
                    put(into, offset, SnowParameters.TO_MAP_Y, column, vp[4 * column + 1]);
                    put(into, offset, SnowParameters.TO_MAP_Z, column, vp[4 * column + 2]);
                }
            }
        }).sample(occlusion).sample(accumulation);
    }

    /** The origin's coordinate {@code axis} as a high and a low float. */
    private static void origin(MemorySegment into, long offset, int axis, double value) {
        float high = (float) value;
        put(into, offset, SnowParameters.ORIGIN_HIGH, axis, high);
        put(into, offset, SnowParameters.ORIGIN_LOW, axis, (float) (value - high));
    }

    private static void put(MemorySegment into, long offset, MaterialField field, int i, float value) {
        into.set(F32, offset + field.offset() + 4L * i, value);
    }

    /** The scene's values as written this frame: the fall direction the next map follows, snowfall and melt. */
    @AllowImpure("keeps the scene's values for the next frame's map and accumulation")
    private void take(SnowParameters authored) {
        double x = authored.fallDirection().x();
        double y = authored.fallDirection().y();
        double z = authored.fallDirection().z();
        double length = Math.sqrt(x * x + y * y + z * z);
        if (length > 0) {
            fall[0] = (float) (x / length);
            fall[1] = (float) (y / length);
            fall[2] = (float) (z / length);
        }
        snowfall = authored.snowfall();
        melt = authored.melt();
    }

    /** The map's frame, the window and the origin, for the columns and the accumulation. */
    private SnowMapPush columnsPush(PassContext ctx, SnowMapPush push, Image occlusion, Buffer columns) {
        double texel = texel();
        return push.frame(frame.deviceAddress()).occlusion(ctx.sampled(occlusion)).mapSize(mapSize)
            .columns(ctx.address(columns)).size(size).windowX(windowX).windowZ(windowZ).originX(originCellX)
            .originZ(originCellZ).originFractionX((float) (originX - originCellX * texel))
            .originFractionZ((float) (originZ - originCellZ * texel)).texel((float) texel).originY((float) originY);
    }

    /** The accumulation's push for this frame; the time since the last one is taken. */
    @AllowImpure("takes the time passed and the window's last position: the next frame accumulates from there")
    private void accumulate(PassContext ctx, SnowMapPush push, Image occlusion, Image accumulation, Buffer columns) {
        float fill = 1 / settings.get(SnowSettings.FILL_SECONDS);
        float melting = 1 / settings.get(SnowSettings.MELT_SECONDS);
        columnsPush(ctx, push, occlusion, columns).accumulation(ctx.storage(accumulation)).fresh(fresh ? 1 : 0)
            .previousX(previousX).previousZ(previousZ).seconds((float) pending).fill(fill * Math.clamp(snowfall, 0, 1))
            .melt(melting * Math.clamp(melt, 0, 1)).coverSeconds(settings.get(SnowSettings.COVER_SECONDS))
            .surfaceChange(settings.get(SnowSettings.SURFACE_CHANGE)).initial(settings.get(SnowSettings.INITIAL));
        pending = 0;
        fresh = false;
        previousX = windowX;
        previousZ = windowZ;
    }

    @Override
    public void close() {
        if (mapView != null) {
            mapView.close();
            mapView = null;
        }
        if (frame != null) {
            frame.close();
            frame = null;
        }
    }
}
