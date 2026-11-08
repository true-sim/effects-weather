package com.truesim.effects.weather.render;

import com.truesim.effects.weather.render.layout.SkyPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Camera;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;

/**
 * The atmosphere behind and in front of a view's scene for the tests and pictures (test/sky.slang), until
 * effects-atmosphere's lands: the clear sky, dimmed under the clouds by their shadow and lit by their diffused light
 * ({@code below}, lux, set each frame), and the aerial perspective over the scene.
 */
record TestSky(WeatherGpu gpu, SceneView view, double[] below) implements Technique {

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
        return Order.at(Order.SKY).before(Clouds.ID);
    }

    @Override
    public void build(GraphBuilder g) {
        Image color = g.modify(Keys.SCENE_COLOR);
        Image depth = g.read(Keys.SCENE_DEPTH);
        boolean flat = g.technique(Clouds.SLOT) == Clouds.SKYBOX;
        Buffer ready = flat ? null : g.request(gpu.noise().generate());
        Image shadow = g.request(gpu.shadowMap(ready));
        Pipeline program = g.computeProgram("shaders/test/sky.spv", "skyMain", SkyPush.BYTES);
        SkyPush push = new SkyPush();
        g.pass("test sky").read(depth, Access.SAMPLED_COMPUTE).read(shadow, Access.SAMPLED_COMPUTE)
            .write(color, Access.STORAGE_WRITE).record(ctx -> {
                int w = ctx.regionWidth(color);
                int h = ctx.regionHeight(color);
                Camera camera = view.camera();
                ctx.dispatch(program, push.view(view.view().address()).frame(gpu.address()).color(ctx.storage(color))
                    .depth(ctx.sampled(depth)).shadow(ctx.sampled(shadow)).size(w | h << 16)
                    .camera((float) (camera.position(0) - gpu.anchorX()), (float) camera.position(1),
                        (float) (camera.position(2) - gpu.anchorZ()))
                    .below((float) below[0], (float) below[1], (float) below[2]), (w + 7) / 8, (h + 7) / 8, 1);
            });
    }
}
