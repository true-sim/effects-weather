package com.truesim.effects.weather.render;

import com.truesim.effects.weather.render.layout.SkyPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Order;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.render.Keys;
import com.truesim.engine.render.SceneView;
import com.truesim.engine.vulkan.Pipeline;
import java.util.List;

/** A clear sky behind a view's scene for the screenshots (test/sky.slang), until effects-atmosphere's sky lands. */
record TestSky(WeatherGpu gpu, SceneView view) implements Technique {

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
        Pipeline program = g.computeProgram("shaders/test/sky.spv", "skyMain", SkyPush.BYTES);
        SkyPush push = new SkyPush();
        g.pass("test sky").read(depth, Access.SAMPLED_COMPUTE).write(color, Access.STORAGE_WRITE).record(ctx -> {
            int w = ctx.regionWidth(color);
            int h = ctx.regionHeight(color);
            ctx.dispatch(program, push.view(view.view().address()).frame(gpu.address()).color(ctx.storage(color))
                .depth(ctx.sampled(depth)).size(w | h << 16).altitude((float) view.camera().position(1)),
                (w + 7) / 8, (h + 7) / 8, 1);
        });
    }
}
