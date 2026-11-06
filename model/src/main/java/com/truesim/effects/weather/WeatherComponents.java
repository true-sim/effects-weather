package com.truesim.effects.weather;

import com.truesim.scene.core.ComponentType;
import java.util.Objects;

/**
 * The weather's components on scene nodes (application-api.md D46). A world says how its weather runs with
 * {@link #WEATHER} on its environment node; brushes can sit on nodes ({@link #BRUSH}: the node's position places
 * them, {@link Brushes#syncFromScene} copies them in).
 */
public final class WeatherComponents {

    /**
     * A world's weather: the configuration and the preset it starts in (a built-in name or a game's).
     *
     * @param config the tunables
     * @param preset the preset in effect
     */
    public record Settings(WeatherConfig config, WeatherPreset preset) {

        public Settings {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(preset, "preset");
        }
    }

    /** The weather's settings; saved. */
    public static final ComponentType<Settings> WEATHER = ComponentType.of("truesim.weather", Settings.class);
    /** A brush placed by its node; saved. */
    public static final ComponentType<WeatherBrush> BRUSH = ComponentType.of("truesim.weather.brush",
        WeatherBrush.class);

    private WeatherComponents() {
    }
}
