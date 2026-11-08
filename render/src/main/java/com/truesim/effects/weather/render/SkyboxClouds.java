package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.render.layout.CompositePush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;
import java.util.function.Supplier;

/**
 * The 2D tier of {@link Clouds} ({@link Clouds#SKYBOX}): one compute pass over the view's pixels (composite.slang's
 * {@code skyboxMain}) that finds, for each pixel's ray, the layer of the type the weather map has where the ray crosses
 * it, and lays the map's coverage over the scene as that layer's optical depth, lit analytically (the approximate Mie
 * phase for the sun's single scattering, an octave of multiple scattering, the sky's ambient). It reads only the
 * weather map and the type table: no noise volumes, no march, no history.
 */
final class SkyboxClouds implements Technique {

    private final WeatherGpu gpu;
    private final SceneView view;
    private final Supplier<Clouds.Flat> config;

    SkyboxClouds(Clouds.Context context, Supplier<Clouds.Flat> config) {
        this.gpu = context.gpu();
        this.view = context.view();
        this.config = config;
    }

    @Override
    public List<Key<?>> provides() {
        return List.of();
    }

    @Override
    @AllowAllocation("building the graph, when it changes")
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        Pipeline program = g.computeProgram("shaders/weather/composite.spv", "skyboxMain", CompositePush.BYTES);
        CompositePush push = new CompositePush();
        Buffer maps = g.request(gpu.maps());
        g.pass("clouds 2d").read(depth, Access.SAMPLED_COMPUTE).read(maps, BufferAccess.STORAGE_READ).write(color, Access.STORAGE_WRITE).record(ctx -> {
            Camera camera = view.camera();
            int width = ctx.regionWidth(color);
            int height = ctx.regionHeight(color);
            ctx.dispatch(program, push.view(view.view().address()).frame(gpu.address()).color(ctx.storage(color))
                .depth(ctx.sampled(depth)).size(width | height << 16)
                .camera((float) (camera.position(0) - gpu.anchorX()), (float) camera.position(1),
                    (float) (camera.position(2) - gpu.anchorZ()))
                .scale(config.get().aerialPerspective() ? 1 : 0), (width + 7) / 8, (height + 7) / 8, 1);
        });
    }
}
