package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.common.graph.Id;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.render.SkyBackground;
import com.truesim.engine.settings.TechniqueConfig;
import com.truesim.engine.settings.TechniqueConfig.Tunable;
import com.truesim.engine.settings.TechniqueKind;
import com.truesim.engine.settings.TechniqueSlot;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A view's clouds, the tier {@link #SLOT} ({@code render.clouds}) names, built in this one's place; the graph rebuilds
 * when the choice changes. Every tier draws the same weather ({@link WeatherGpu}) over the scene's colour at
 * {@link Order#SKY}, after the sky's background, so glass and particles drawn later show them:
 * <ul>
 * <li>{@link #VOLUMETRIC}: ray-marched volumes, fly-through: the march starts at the camera with detail for its first
 *     kilometres, wherever the camera is (inside a cloud too); most steps, light steps and octaves;</li>
 * <li>{@link #LAYERED} (the default): ray-marched layers (Schneider's 2.5D model) from outside: two-speed march with
 *     detail where it meets cloud, at half resolution with temporal accumulation;</li>
 * <li>{@link #SKYBOX}: 2D, the weather map's coverage on each type's layer, lit analytically, per pixel; no noise
 *     volumes, no march (a few hundredths of a millisecond);</li>
 * <li>off.</li>
 * </ul>
 *
 * <pre>{@code
 * view.port().add(new Clouds(gpu, view));
 * Clouds.SLOT.choose(graph.settings(), Clouds.VOLUMETRIC);
 * graph.settings().set(Clouds.VOLUMETRIC.setting("steps"), 192);
 * }</pre>
 */
public final class Clouds implements Technique {

    /** The id of a view's clouds. */
    public static final Id<Clouds> ID = Id.of("weather.clouds", Clouds.class);

    /**
     * A ray-marched tier's tunables.
     *
     * @param resolution the march's pixels per scene pixel along each axis: 1 full, 2 half (a quarter of the pixels), 4
     * @param steps coarse steps across the cloud slab (fine steps are a third as long)
     * @param range how far the march reaches, metres
     * @param near metres from the camera marched finely with detail from the start: inside clouds (0: none)
     * @param lightSteps steps of the cone march towards the sun, each twice as long as the one before
     * @param lightStep the first light step, metres
     * @param detail whether detail noise erodes the shapes
     * @param temporal whether frames accumulate (jittered start, reprojected history)
     * @param blend the history's weight
     * @param flashBlend the history's weight while a flash lights the clouds
     * @param rain whether rain falls below the bases (rain shafts)
     * @param ground whether the ground bounces sunlight into the bases (under the clouds' shadow)
     */
    public record March(
        @Tunable(value = "the cloud march's pixels per scene pixel along each axis (1 full, 2 half, 4 quarter)",
            min = 1, max = 4) int resolution,
        @Tunable(value = "coarse steps across the cloud slab", min = 8, max = 1024) int steps,
        @Tunable(value = "metres the clouds are marched to", min = 1000, max = 1_000_000) float range,
        @Tunable(value = "metres from the camera marched finely with detail (inside clouds)", min = 0, max = 50_000)
        float near,
        @Tunable(value = "steps towards the sun from each sample", min = 1, max = 16) int lightSteps,
        @Tunable(value = "the first light step, metres", min = 1, max = 2000) float lightStep,
        @Tunable("detail noise eroding the shapes") boolean detail,
        @Tunable("frames accumulated: jittered, reprojected") boolean temporal,
        @Tunable(value = "the history's weight", min = 0, max = 0.99) float blend,
        @Tunable(value = "the history's weight while lightning lights the clouds", min = 0, max = 0.99)
        float flashBlend,
        @Tunable("rain shafts below the cloud bases") boolean rain,
        @Tunable("sunlight bounced off the ground into the cloud bases") boolean ground) implements TechniqueConfig {
    }

    /** The 2D tier's tunables. */
    public record Flat(@Tunable("the aerial perspective over the cloud layer") boolean aerialPerspective)
        implements TechniqueConfig {
    }

    /** The context a kind's technique gets: the weather on the GPU and the view. */
    public record Context(WeatherGpu gpu, SceneView view) {
    }

    /** The slot; {@link #LAYERED} by default, off allowed. */
    public static final TechniqueSlot<Context, Technique> SLOT = TechniqueSlot.of("render.clouds", "layered", true,
        "clouds", techniques -> techniques.register(Clouds.SLOT, Clouds.VOLUMETRIC)
            .register(Clouds.SLOT, Clouds.LAYERED).register(Clouds.SLOT, Clouds.SKYBOX));

    /** Fly-through volumes. */
    public static final TechniqueKind<March, Context, Technique> VOLUMETRIC = TechniqueKind.of(SLOT, "volumetric",
        new March(2, 160, 120_000, 4000, 6, 40, true, true, 0.9f, 0.2f, true, true),
        "ray-marched volumes from the camera: fly through them",
        (context, config) -> new VolumetricClouds(context, config));

    /** Ray-marched layers. */
    public static final TechniqueKind<March, Context, Technique> LAYERED = TechniqueKind.of(SLOT, "layered",
        new March(2, 96, 80_000, 0, 4, 60, true, true, 0.9f, 0.2f, true, true),
        "ray-marched layers (2.5D), seen from outside",
        (context, config) -> new VolumetricClouds(context, config));

    /** The 2D sky. */
    public static final TechniqueKind<Flat, Context, Technique> SKYBOX = TechniqueKind.of(SLOT, "skybox",
        new Flat(true), "a 2D layer per type from the weather map's coverage, lit analytically",
        (context, config) -> new SkyboxClouds(context, config));

    private final Context context;
    private final Map<TechniqueKind<?, ?, ?>, Technique> made = new IdentityHashMap<>(); // per kind, once chosen

    /** {@code gpu}'s weather over {@code view}'s scene; added to the view's port. */
    public Clouds(WeatherGpu gpu, SceneView view) {
        context = new Context(Objects.requireNonNull(gpu, "gpu"), Objects.requireNonNull(view, "view"));
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
        return List.of(Keys.SCENE_COLOR);
    }

    @Override
    public List<Key<?>> reads() {
        return List.of(Keys.SCENE_DEPTH);
    }

    @Override
    public Order order() {
        return Order.at(Order.SKY).after(SkyBackground.ID);
    }

    /** The chosen kind's technique, built in this one's place (nothing when off). */
    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Checks.inView(g, context.view(), this);
        TechniqueKind<?, Context, Technique> kind = g.technique(SLOT);
        if (kind != null) {
            made.computeIfAbsent(kind, k -> make(kind)).build(g);
        }
    }

    @AllowAllocation("building the graph, when it changes")
    private <C extends Record & TechniqueConfig> Technique make(TechniqueKind<C, Context, Technique> kind) {
        return kind.create(context, () -> kind.config(context.view().port().settings()));
    }
}
