package com.truesim.effects.weather.snow;

import com.truesim.engine.settings.Setting;
import java.util.List;

/**
 * The snow's settings, read from the frame graph's settings: the occlusion map's area and resolution when the graph
 * builds (a change rebuilds), the rest when frames record.
 */
public final class SnowSettings {

    /** Half the side of the square round the camera the occlusion map and the accumulation cover, metres. */
    public static final Setting<Float> AREA = Setting.decimal("weather.snow.area", 64, 4, 4096,
        "half the side of the square round the camera snow knows shelter and accumulation in, metres");
    /** The occlusion map's texels a side. */
    public static final Setting<Integer> MAP_RESOLUTION = Setting.integer("weather.snow.mapResolution", 1024, 16,
        8192, "texels a side of the snow's top-down occlusion map");
    /** How far above the camera's height the map looks from, along the fall direction, metres. */
    public static final Setting<Float> MAP_HEIGHT = Setting.decimal("weather.snow.mapHeight", 200, 1, 100_000,
        "how far above the camera the snow's occlusion map looks down from, metres");
    /** How deep the map sees along the fall direction from there, metres. */
    public static final Setting<Float> MAP_DEPTH = Setting.decimal("weather.snow.mapDepth", 400, 1, 100_000,
        "how deep the snow's occlusion map sees along the fall direction, metres");
    /** How far below the topmost surface a point still counts as reached by the snow, metres. */
    public static final Setting<Float> BIAS = Setting.decimal("weather.snow.bias", 0.15f, 0, 100,
        "how far below the topmost surface a point still gets snow, metres");
    /** The accumulation's texels a side (its cells: the area's side over this). */
    public static final Setting<Integer> ACCUMULATION_RESOLUTION = Setting.integer(
        "weather.snow.accumulationResolution", 256, 4, 4096, "texels a side of the snow's accumulation");
    /** Seconds the snow takes to accumulate from bare to full at a snowfall of 1. */
    public static final Setting<Float> FILL_SECONDS = Setting.decimal("weather.snow.fillSeconds", 1800, 0.001f,
        1e7f, "seconds snow takes to accumulate from bare to full at a snowfall of 1");
    /** Seconds the snow takes to melt from full to bare at a melt of 1. */
    public static final Setting<Float> MELT_SECONDS = Setting.decimal("weather.snow.meltSeconds", 3600, 0.001f,
        1e7f, "seconds snow takes to melt from full to bare at a melt of 1");
    /** Seconds a surface stays covered before the cover counts as the new surface (bare). */
    public static final Setting<Float> COVER_SECONDS = Setting.decimal("weather.snow.coverSeconds", 60, 0, 1e7f,
        "seconds something stays over a surface before it counts as the new surface, bare");
    /** Height in metres between two surfaces of a column for them to count as different. */
    public static final Setting<Float> SURFACE_CHANGE = Setting.decimal("weather.snow.surfaceChange", 0.3f, 0.001f,
        1000, "metres of height that make the topmost surface of a column another one");
    /** The amount a column new to the area starts with, 0 to 1: 1, snow as the coverage says; 0, bare. */
    public static final Setting<Float> INITIAL = Setting.decimal("weather.snow.initial", 1, 0, 1,
        "how much snow a place new to the area starts with, 0 to 1 of the coverage");

    /** Every snow setting. */
    public static final List<Setting<?>> ALL = List.of(AREA, MAP_RESOLUTION, MAP_HEIGHT, MAP_DEPTH, BIAS,
        ACCUMULATION_RESOLUTION, FILL_SECONDS, MELT_SECONDS, COVER_SECONDS, SURFACE_CHANGE, INITIAL);

    private SnowSettings() {
    }
}
