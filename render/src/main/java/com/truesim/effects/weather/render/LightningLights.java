package com.truesim.effects.weather.render;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.lightning.LightningEvents;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.SceneGraph;
import java.util.Objects;

/**
 * Lightning lighting the scene through the scene: a few nodes with a point {@link LightSource} each (candela, the
 * scene's units), one per flash lit this frame ({@link WeatherGpu#flashes()}, the same selection and intensity the
 * clouds' glow uses), placed on the flash's channel at the cloud base's height or halfway along an intracloud channel,
 * dark (intensity 0) while unused. The engine's {@code LightSync} places them in its lights like any other: the ground,
 * the fog and everything shaded see the flash. Their range is {@code range} metres. {@link #update} once a frame after
 * {@link WeatherGpu#update}; allocates nothing.
 */
public final class LightningLights {

    private final SceneGraph scene;
    private final long[] nodes;
    private final LightSource[] lights;

    /**
     * {@code count} flash lights under {@code parent} (a node of {@code scene}, NodeHandle.NONE for the root), reaching
     * {@code range} metres.
     */
    @AllowAllocation("made once")
    public LightningLights(SceneGraph scene, long parent, int count, double range) {
        this.scene = Objects.requireNonNull(scene, "scene");
        nodes = new long[count];
        lights = new LightSource[count];
        for (int i = 0; i < count; i++) {
            nodes[i] = parent == com.truesim.scene.core.NodeHandle.NONE ? scene.create() : scene.create(parent);
            lights[i] = LightSource.point(0.9f, 0.95f, 1f, 0).range(range);
            scene.components().set(nodes[i], LightSource.TYPE, lights[i]);
        }
    }

    /** The node of light {@code i}. */
    public long node(int i) {
        return nodes[i];
    }

    /** Light {@code i}'s component. */
    public LightSource light(int i) {
        return lights[i];
    }

    /** Places the flashes {@code gpu} selected this frame, the brightest first; the rest go dark. */
    public void update(WeatherGpu gpu) {
        ActiveFlashes flashes = gpu.flashes();
        LightningEvents events = gpu.weather().lightning();
        for (int i = 0; i < nodes.length; i++) {
            if (i < flashes.count()) {
                int slot = flashes.slot(i);
                double ground = gpu.anchorY();
                double t = events.end(slot, 1) - ground <= 1 ? channelBase(events, slot, ground) : 0.5;
                double x = events.origin(slot, 0) + t * (events.end(slot, 0) - events.origin(slot, 0));
                double y = events.origin(slot, 1) + t * (events.end(slot, 1) - events.origin(slot, 1));
                double z = events.origin(slot, 2) + t * (events.end(slot, 2) - events.origin(slot, 2));
                scene.setTranslation(nodes[i], x, y, z);
                lights[i].intensity(flashes.intensity(i));
            } else if (lights[i].intensity() != 0) {
                lights[i].intensity(0);
            }
        }
    }

    /** Where along a ground flash's channel (0 its origin in the cloud, 1 the ground) the light goes: below the cloud. */
    private static double channelBase(LightningEvents events, int slot, double ground) {
        double top = events.origin(slot, 1) - ground;
        return top > 0 ? Math.min(0.8, Math.max(0.3, 1 - 1500 / top)) : 0.5;
    }
}
