package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import com.truesim.effects.weather.lightning.LightningEvents;

/**
 * A world's weather, without the engine: the preset and its transitions, wind drift, brushes, the storm life cycle
 * with its lightning, and the weather maps every cloud tier reads (near map around the focus, far map or the global
 * map on a planet). Advance it on fixed steps ({@link #step}, or {@link #advance} with frame time); equal seeds,
 * configurations and calls give equal weather on every machine. Stepping allocates nothing.
 *
 * <pre>{@code
 * Weather weather = new Weather(WeatherConfig.forQuality(Quality.HIGH), WeatherDomain.flat(), WeatherPreset.FAIR);
 * weather.setPreset(WeatherPreset.STORM, 1800);         // a storm rolls in over half an hour
 * weather.addBrush(WeatherBrush.clear(5000), 0, 0, 0);  // but the airfield stays clear
 * // every frame:
 * weather.setFocus(camera.x, camera.y, camera.z);
 * weather.advance(frameSeconds);
 * weather.lightning().forEachSince(lastFlash, (events, slot, id) -> ...);
 * }</pre>
 */
public final class Weather {

    private final WeatherConfig config;
    private final WeatherDomain domain;
    private final WeatherState state;
    private final Brushes brushes;
    private final LightningEvents lightning;
    private final StormSimulation storms;
    private final WeatherField field;
    private final WeatherGrid near;
    private final WeatherGrid far;
    private final GlobalWeatherGrid global;
    private final double[] focus = new double[3];
    private final double[] plane = new double[2];
    private final double[] frame = new double[9];
    private double windEast;
    private double windNorth;
    private double time;
    private long steps;
    private double accumulator;

    /** Weather of {@code config} over {@code domain}, starting in {@code initial}. */
    @AllowAllocation("made once")
    public Weather(WeatherConfig config, WeatherDomain domain, WeatherPreset initial) {
        this.config = config;
        this.domain = domain;
        state = new WeatherState(initial);
        brushes = new Brushes(config.maxBrushes());
        lightning = new LightningEvents(config.lightningCapacity());
        storms = config.simulation() == WeatherConfig.Simulation.STORMS
            ? new StormSimulation(config.storms(), config.lightning(), lightning, domain, config.seed()) : null;
        field = new WeatherField(state, brushes, storms, config.noiseOctaves(), (int) config.seed());
        near = new WeatherGrid(config.nearSize(), config.nearExtent(), config.tileTexels());
        far = domain.isSphere() ? null : new WeatherGrid(config.farSize(), config.farExtent(), config.tileTexels());
        global = domain.isSphere() ? new GlobalWeatherGrid(config.globalFaceSize(),
            Math.min(config.tileTexels(), config.globalFaceSize()), domain.radius()) : null;
        domain.surface(0, domain.isSphere() ? domain.radius() : 0, 0, focus, 0);
    }

    /**
     * Moves to {@code preset} over {@code seconds} of game time; at once in the {@link WeatherConfig.Simulation#STATIC}
     * tier.
     */
    public void setPreset(WeatherPreset preset, double seconds) {
        state.transitionTo(preset, config.simulation() == WeatherConfig.Simulation.STATIC ? 0 : seconds);
    }

    /**
     * Where the maps centre and storms form: usually the camera. On a planet, moving more than the re-anchor distance
     * from the near map's anchor re-anchors it (the near map is then rebuilt over the next steps).
     */
    public void setFocus(double x, double y, double z) {
        domain.surface(x, y, z, focus, 0);
        if (domain.isSphere()) {
            boolean inside = domain.groundToPlane(focus[0], focus[1], focus[2], plane, 0);
            if (!inside || Math.sqrt(plane[0] * plane[0] + plane[1] * plane[1]) > config.reanchorDistance()) {
                domain.reanchor(focus[0], focus[1], focus[2]);
                near.invalidate();
            }
        }
    }

    /** The focus's ground point ({@link #setFocus}), coordinate {@code axis}. */
    public double focus(int axis) {
        return focus[axis];
    }

    /** Adds a brush at the ground under (x, y, z); returns its id, or −1 when full. */
    public int addBrush(WeatherBrush brush, double x, double y, double z) {
        domain.surface(x, y, z, frame, 0);
        return brushes.add(brush, frame[0], frame[1], frame[2]);
    }

    /** Forms a storm cell at the ground under (x, y, z); −1 when full or below the storms tier. */
    public int spawnStorm(double x, double y, double z) {
        if (storms == null) {
            return -1;
        }
        domain.surface(x, y, z, frame, 0);
        return storms.spawn(frame[0], frame[1], frame[2]);
    }

    /** One fixed step. */
    public void step() {
        double dt = config.step();
        state.advance(dt);
        if (config.simulation() != WeatherConfig.Simulation.STATIC) {
            double alt = config.advectionAltitude();
            windEast += config.wind().east(alt) * state.windScale() * dt;
            windNorth += config.wind().north(alt) * state.windScale() * dt;
            domain.frame(focus[0], focus[1], focus[2], frame);
            field.setOffset(windEast * frame[0] + windNorth * frame[3], windEast * frame[1] + windNorth * frame[4],
                windEast * frame[2] + windNorth * frame[5]);
        }
        brushes.step(dt, domain);
        if (storms != null) {
            storms.step(time, dt, state.stormRate(), config.wind(), state.windScale(), focus[0], focus[1], focus[2]);
        }
        if (domain.groundToPlane(focus[0], focus[1], focus[2], plane, 0)) {
            near.recentre(plane[0], plane[1]);
        }
        near.refresh(field, domain, config.tilesPerStep());
        int farBudget = Math.max(1, config.tilesPerStep() / 4);
        if (far != null) {
            far.recentre(plane[0], plane[1]);
            far.refresh(field, domain, farBudget);
        } else {
            global.refresh(field, farBudget);
        }
        time += dt;
        steps++;
    }

    /**
     * Fills every tile of the maps now, e.g. behind a loading screen, instead of over the next steps' budgets. Costs
     * about 0.3 µs per texel (0.3 s for the HIGH preset's maps).
     */
    public void refreshAll() {
        int nearTiles = near.tilesPerEdge() * near.tilesPerEdge();
        near.refresh(field, domain, nearTiles);
        if (far != null) {
            far.refresh(field, domain, far.tilesPerEdge() * far.tilesPerEdge());
        } else {
            global.refresh(field, 6 * global.tilesPerEdge() * global.tilesPerEdge());
        }
    }

    /** Runs the fixed steps that {@code seconds} more of game time complete; returns how many ran. */
    public int advance(double seconds) {
        accumulator += seconds;
        int n = 0;
        while (accumulator >= config.step()) {
            accumulator -= config.step();
            step();
            n++;
        }
        return n;
    }

    /**
     * The weather at the ground under (x, y, z): from the near map where it holds the place, else the far or global
     * map, else evaluated directly. Allocates nothing.
     */
    public void sample(double x, double y, double z, WeatherSample out) {
        domain.surface(x, y, z, frame, 0);
        if (domain.groundToPlane(frame[0], frame[1], frame[2], plane, 0)) {
            if (near.sample(plane[0], plane[1], out) || (far != null && far.sample(plane[0], plane[1], out))) {
                return;
            }
        }
        if (global != null && global.sample(frame[0], frame[1], frame[2], out)) {
            return;
        }
        field.evaluate(frame[0], frame[1], frame[2], out);
    }

    public WeatherConfig config() {
        return config;
    }

    public WeatherDomain domain() {
        return domain;
    }

    public WeatherState state() {
        return state;
    }

    public Brushes brushes() {
        return brushes;
    }

    /** The storm cells, or null below the {@link WeatherConfig.Simulation#STORMS} tier. */
    public StormSimulation storms() {
        return storms;
    }

    /** The flashes the storms produced. */
    public LightningEvents lightning() {
        return lightning;
    }

    public WeatherField field() {
        return field;
    }

    /** The map around the focus. */
    public WeatherGrid nearMap() {
        return near;
    }

    /** The coarse map on a flat world, or null on a planet. */
    public WeatherGrid farMap() {
        return far;
    }

    /** The whole planet's map, or null on a flat world. */
    public GlobalWeatherGrid globalMap() {
        return global;
    }

    /** Game seconds simulated. */
    public double time() {
        return time;
    }

    /** Steps run. */
    public long steps() {
        return steps;
    }

    /** How far the wind has carried the coverage pattern east, metres. */
    public double driftEast() {
        return windEast;
    }

    /** How far north. */
    public double driftNorth() {
        return windNorth;
    }
}
