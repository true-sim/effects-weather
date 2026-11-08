package com.truesim.effects.weather.snow;

import com.truesim.effects.weather.snow.hooks.SnowParameters;
import com.truesim.engine.scene.ShadingHookComponent;
import com.truesim.scene.render.MaterialData;
import java.util.Objects;

/**
 * The snow as content holds it: {@link SnowParameters}' authored fields (what the technique writes is left out) as the
 * {@code weather.snow} hook's values on a scene node (engine-scene's {@link ShadingHookComponent}), saved with the
 * scene and handed to the renderer by engine-scene's {@code ShadingHookSync}.
 *
 * <pre>{@code
 * scene.components().set(weather, ShadingHookComponent.TYPE,
 *     SnowContent.component(SnowParameters.DEFAULTS.withCoverage(0.6f).withSnowfall(1), true));
 * }</pre>
 */
public final class SnowContent {

    private SnowContent() {
    }

    /** {@code snow}'s authored fields as the hook's component, on while {@code on}. */
    public static ShadingHookComponent component(SnowParameters snow, boolean on) {
        Objects.requireNonNull(snow, "snow");
        MaterialData data = MaterialData.builder(SimpleSnow.HOOK)
            .set("albedo", (float) snow.albedo().x(), (float) snow.albedo().y(), (float) snow.albedo().z())
            .set("coverage", snow.coverage())
            .set("fallDirection", (float) snow.fallDirection().x(), (float) snow.fallDirection().y(),
                (float) snow.fallDirection().z())
            .set("roughness", snow.roughness())
            .set("noiseScale", snow.noiseScale())
            .set("noiseSharpness", snow.noiseSharpness())
            .set("slopeStart", snow.slopeStart())
            .set("slopeEnd", snow.slopeEnd())
            .set("normalBlend", snow.normalBlend())
            .set("snowfall", snow.snowfall())
            .set("melt", snow.melt())
            .build();
        return new ShadingHookComponent(SimpleSnow.HOOK, data.values(), on);
    }
}
