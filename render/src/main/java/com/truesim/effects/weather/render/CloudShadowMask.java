package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.effects.weather.render.layout.MaskPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Ratio;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.Lights;
import com.truesim.engine.render.MeshletRenderer;
import com.truesim.engine.render.RenderView;
import com.truesim.engine.render.SceneDraw;
import com.truesim.engine.render.SceneGeometry;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.ShadowInputs;
import com.truesim.engine.render.ShadowTable;
import com.truesim.engine.render.SurfaceOutput;
import com.truesim.engine.render.ViewOptions;
import com.truesim.engine.render.VisibilityBufferShading;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.Objects;

/**
 * The clouds' shadow on a view's surfaces: the sun's screen-space shadow mask (mask.slang) from the Beer shadow map
 * ({@link CloudShadowMap}) at each pixel's point, given to shading through the sun's shadow table entry
 * ({@code ShadowTable.shadowMask}), as the engine's ray-traced masks are. It needs the view's depth before shading: the
 * visibility buffer's own IDs draw, or a depth draw of its own for forward shading.
 *
 * <p>This is the engine's screen-space mask path, the fallback the design names (effects-atmosphere's
 * volumetrics-design §7.2). The engine has no per-light transmittance term yet, so a light casts through one shadow
 * entry per point: while the scene's own shadows of the sun are on ({@code ShadowSpec} other than {@code NONE}), they
 * keep the entry and the clouds' shadow yields (scene shadows are never broken), until the engine multiplies a light's
 * transmittance into its shadow. Fog, transparents, particles and GI hits are not on the screen grid and do not get
 * the mask. Takes the shadow table's last entry ({@link #entry}). Add it to the view's port after the shadows owner.
 */
public final class CloudShadowMask implements Technique, AutoCloseable {

    /** Its id. */
    public static final Id<CloudShadowMask> ID = Id.of("weather.cloudShadowMask", CloudShadowMask.class);

    private final WeatherGpu gpu;
    private final SceneView view;
    private final MeshletRenderer renderer;
    private final Lights lights;
    private final int sun;
    private final RenderView depthView;

    /**
     * The clouds of {@code gpu} shadowing {@code sun} (a light of {@code lights}) on {@code view}'s surfaces, drawn by
     * {@code renderer}.
     */
    @AllowAllocation("made once")
    public CloudShadowMask(WeatherGpu gpu, SceneView view, MeshletRenderer renderer, Lights lights, int sun) {
        this.gpu = Objects.requireNonNull(gpu, "gpu");
        this.view = Objects.requireNonNull(view, "view");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.lights = Objects.requireNonNull(lights, "lights");
        this.sun = sun;
        depthView = renderer.createView(ViewOptions.of(view.view().options().draws(),
            view.view().options().triangles()));
    }

    @Override
    public Id<? extends Technique> id() {
        return ID;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    public List<Key<?>> modifies() {
        return List.of(Keys.LIGHT_LIST);
    }

    /** The shadow table entry it takes: the last of {@code table}'s. */
    public static int entry(ShadowTable table) {
        return table.capacity() - 1;
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Checks.inView(g, view, this);
        g.modify(Keys.LIGHT_LIST);
        ShadowTable table = lights.shadowTable(g.view());
        ShadowInputs.Set inputs = g.request(new ShadowInputs(g.view()));
        Image depth = g.request(depthSource(g)).depth();
        // The cheap tiers need no noise: their shadows are of the coverage alone (as their clouds are).
        boolean flat = g.technique(Clouds.SLOT) == Clouds.SKYBOX;
        Buffer ready = flat ? null : g.request(gpu.noise().generate());
        Image shadow = g.request(gpu.shadowMap(ready));
        Image mask = g.create("cloud shadow mask", ImageDesc.like(depth, Ratio.ONE).format(Format.R8_UNORM));
        Pipeline program = g.computeProgram("shaders/weather/mask.spv", "maskMain", MaskPush.BYTES);
        MaskPush push = new MaskPush();
        g.pass("cloud shadow mask").read(depth, Access.SAMPLED_COMPUTE).read(shadow, Access.SAMPLED_COMPUTE)
            .write(mask, Access.STORAGE_WRITE).record(ctx -> {
                Camera camera = view.camera();
                int width = ctx.regionWidth(depth);
                int height = ctx.regionHeight(depth);
                ctx.dispatch(program, push.view(view.view().address()).frame(gpu.address())
                    .output(ctx.storage(mask)).depth(ctx.sampled(depth)).shadow(ctx.sampled(shadow))
                    .size(width | height << 16)
                    .camera((float) (camera.position(0) - gpu.anchorX()), (float) camera.position(1),
                        (float) (camera.position(2) - gpu.anchorZ())), (width + 7) / 8, (height + 7) / 8, 1);
            });
        int entry = entry(table);
        inputs.sample(mask);
        inputs.setup(ctx -> {
            com.truesim.scene.core.ShadowSpec spec = sun < lights.count() ? lights.shadow(sun) : null;
            if (sun < lights.count() && (spec == null || !spec.casts())) {
                table.shadowMask(entry, ctx.sampled(mask), 0);
                table.light(sun, entry, 1);
            }
        });
    }

    /** The draw of the view's depth before shading: the visibility buffer's own, or one of its own. */
    @AllowAllocation("building the graph, when it changes")
    private SceneDraw depthSource(GraphBuilder g) {
        for (Technique technique : g.techniquesInView()) {
            if (technique instanceof VisibilityBufferShading shading) {
                return shading.idsDraw(g);
            }
        }
        return new SceneDraw(SceneGeometry.meshlets(renderer), depthView, view.camera(), SurfaceOutput.depth(),
            renderer.depthFormat(), view.width(), view.height(), false, view.port());
    }

    @Override
    public void close() {
        depthView.close();
    }
}
