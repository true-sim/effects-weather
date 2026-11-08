package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.render.layout.ShadowMapPush;
import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.Buffer;
import com.truesim.engine.graph.BufferAccess;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Request;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.Pipeline;

/**
 * The clouds' Beer shadow map (Hillaire 2020): an orthographic map along the sun over {@code extent} metres of ground
 * around the weather's anchor, {@code size}² texels of RGBA16F (front depth along the light, mean extinction behind
 * it, maximum optical depth), each texel marched through the cloud slab in {@code steps} steps on the base shape
 * (shadowmap.slang), every frame. A shared request: the clouds' march (rain shafts and the ground's bounce under
 * them), the surfaces' shadow mask ({@link CloudShadowMask}) and anything else in the frame read one map. Where it is
 * placed is part of {@link WeatherGpu}'s frame record.
 *
 * @param gpu the weather
 * @param noiseReady the noise's generation ({@link CloudNoise#generate}): read, so the map comes after it
 * @param size texels per edge
 * @param steps steps through the slab per texel
 */
public record CloudShadowMap(WeatherGpu gpu, Buffer noiseReady, int size, int steps) implements Request<Image> {

    @Override
    @AllowAllocation("building the graph, when it changes")
    public Image build(GraphBuilder g) {
        Image map = g.create("cloud shadow map", ImageDesc.of(Format.R16G16B16A16_SFLOAT, size, size));
        Pipeline program = g.computeProgram("shaders/weather/shadowmap.spv", "shadowMapMain", ShadowMapPush.BYTES);
        ShadowMapPush push = new ShadowMapPush();
        g.pass("cloud shadow map").read(noiseReady, BufferAccess.STORAGE_READ).write(map, Access.STORAGE_WRITE)
            .record(ctx -> ctx.dispatch(program, push.frame(gpu.address()).output(ctx.storage(map))
                .shape(gpu.noise().shape()).size(size).steps(steps), (size + 7) / 8, (size + 7) / 8, 1));
        return map;
    }
}
