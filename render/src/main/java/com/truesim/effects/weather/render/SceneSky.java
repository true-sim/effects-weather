package com.truesim.effects.weather.render;

import com.truesim.common.math.Maths;
import com.truesim.scene.core.LightSource;
import com.truesim.scene.core.NodeHandle;
import com.truesim.scene.core.SceneGraph;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Objects;

/**
 * The clouds' sun and sky from the scene: the sun is the directional {@link LightSource} on a node (its -Z, its colour
 * times intensity in lux: the illuminance it gives the ground, as the scene's lights do), and the atmosphere an
 * exponential one ({@link CloudLighting.Atmosphere#EARTH} by default). From them, as a stand-in for
 * effects-atmosphere's tables until they land (documented approximations, in physical units):
 * <ul>
 * <li>the sun outside the atmosphere is the scene's illuminance over the atmosphere's transmittance at the ground
 *     (at least 10⁻³ of it, so a sun at the horizon does not blow up);</li>
 * <li>the sky's illuminance above the clouds is the light the atmosphere scattered out of the sun's beam on its way to
 *     the ground, half of it downwards, plus a share of the beam for the higher orders and aerosols:
 *     E_top·μ·(0.5·(1 − T) + 0.12·T), per channel;</li>
 * <li>the horizon's radiance is the sun's light scattered once into a long horizontal path, E_top·T_ground·(1 −
 *     e^(−β·50 km))/(4π).</li>
 * </ul>
 * Without a sun node it is night: no sun, no sky. Reads allocate nothing.
 */
public final class SceneSky implements CloudLighting {

    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    /**
     * The share of the transmitted beam a clear sky's higher orders and aerosols add to its diffuse light (clear-sky
     * diffuse fractions of 15-25 % of the global horizontal irradiance at mid sun elevations).
     */
    private static final double DIFFUSE_FLOOR = 0.12;

    private final SceneGraph scene;
    private final Atmosphere atmosphere;
    private long sun = NodeHandle.NONE;
    private final double[] direction = new double[3];
    private final double[] top = new double[3];

    /** The sky of {@code scene} over {@code atmosphere}. */
    public SceneSky(SceneGraph scene, Atmosphere atmosphere) {
        this.scene = Objects.requireNonNull(scene, "scene");
        this.atmosphere = Objects.requireNonNull(atmosphere, "atmosphere");
    }

    /** The node whose directional light is the sun (NodeHandle.NONE: none). */
    public SceneSky sun(long node) {
        sun = node;
        return this;
    }

    /** Reads the sun's node: its direction and its illuminance outside the atmosphere. */
    private void read() {
        top[0] = 0;
        top[1] = 0;
        top[2] = 0;
        direction[0] = 0;
        direction[1] = 1;
        direction[2] = 0;
        if (sun == NodeHandle.NONE || !scene.contains(sun)) {
            return;
        }
        var store = scene.components().store(LightSource.TYPE);
        if (!store.has(sun)) {
            return;
        }
        LightSource light = store.get(sun);
        MemorySegment s = scene.worldTransforms().segment();
        long o = (long) NodeHandle.index(sun) * scene.worldTransforms().stride();
        double qx = s.get(DOUBLE, o + 24);
        double qy = s.get(DOUBLE, o + 32);
        double qz = s.get(DOUBLE, o + 40);
        double qw = s.get(DOUBLE, o + 48);
        // The light shines along the node's -Z: the sun lies along +Z rotated.
        double x = 2 * (qx * qz + qw * qy);
        double y = 2 * (qy * qz - qw * qx);
        double z = 1 - 2 * (qx * qx + qy * qy);
        double length = Math.sqrt(x * x + y * y + z * z);
        direction[0] = x / length;
        direction[1] = y / length;
        direction[2] = z / length;
        double mu = direction[1];
        double intensity = light.intensity();
        for (int c = 0; c < 3; c++) {
            double t = Math.max(atmosphere.transmittance(c, 0, mu), 1e-3);
            top[c] = (c == 0 ? light.red() : c == 1 ? light.green() : light.blue()) * intensity / t;
        }
    }

    @Override
    public void sun(double[] towards, double[] illuminance) {
        read();
        System.arraycopy(direction, 0, towards, 0, 3);
        System.arraycopy(top, 0, illuminance, 0, 3);
    }

    @Override
    public void sky(double[] illuminance) {
        read();
        double mu = Math.max(direction[1], 0);
        for (int c = 0; c < 3; c++) {
            double t = atmosphere.transmittance(c, 0, Math.max(mu, 0.02));
            illuminance[c] = top[c] * mu * (0.5 * (1 - t) + DIFFUSE_FLOOR * t);
        }
    }

    @Override
    public void horizon(double[] radiance) {
        read();
        double mu = direction[1];
        for (int c = 0; c < 3; c++) {
            double beta = (c == 0 ? atmosphere.rayleighRed() : c == 1 ? atmosphere.rayleighGreen()
                : atmosphere.rayleighBlue()) + atmosphere.mieScattering();
            radiance[c] = top[c] * atmosphere.transmittance(c, 0, mu) * (1 - Maths.exp(-beta * 50_000)) / (4 * Math.PI);
        }
    }

    @Override
    public Atmosphere atmosphere() {
        return atmosphere;
    }
}
