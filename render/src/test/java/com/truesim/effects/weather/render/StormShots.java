package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assumptions.assumeThat;

import com.truesim.effects.weather.StormSimulation;
import com.truesim.effects.weather.WeatherPreset;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Pictures of storm cells (TRUESIM_SCREENSHOTS=true; into TRUESIM_SHOTS): a cell through its life from the side,
 * tracked at a fixed distance. {@code TRUESIM_QUICK} lists the ages in minutes (and optionally {@code d=<km>},
 * {@code sun=<elevation>,<azimuth>}, {@code res=<w>x<h>}, {@code look=<degrees up>}).
 */
class StormShots {

    private static boolean wanted() {
        return "true".equals(System.getenv("TRUESIM_SCREENSHOTS"));
    }

    /** The first active cell's slot. */
    static int first(StormSimulation storms) {
        for (int i = 0; i < storms.capacity(); i++) {
            if (storms.active(i)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void quick() {
        assumeThat(wanted()).isTrue();
        String spec = System.getenv().getOrDefault("TRUESIM_QUICK", "10,16,22,30,45");
        double distance = 30;
        String[] suns = {"35,120"};
        String[] debugs = {"0"};
        boolean storm = true;
        String tier = "";
        java.util.Map<String, String> settings = new java.util.HashMap<>();
        double up = 12;
        int w = 1280;
        int h = 720;
        String preset = "FAIR";
        double[] ages = new double[0];
        for (String part : spec.trim().split(" +")) {
            if (part.startsWith("d=")) {
                distance = Double.parseDouble(part.substring(2));
            } else if (part.startsWith("sun=")) {
                suns = part.substring(4).split("/");
            } else if (part.startsWith("res=")) {
                String[] v = part.substring(4).split("x");
                w = Integer.parseInt(v[0]);
                h = Integer.parseInt(v[1]);
            } else if (part.startsWith("look=")) {
                up = Double.parseDouble(part.substring(5));
            } else if (part.startsWith("debug=")) {
                debugs = part.substring(6).split(",");
            } else if (part.startsWith("set=")) {
                for (String kv : part.substring(4).split(";")) {
                    settings.put(kv.split("=")[0], kv.split("=")[1]);
                }
            } else if (part.startsWith("tier=")) {
                tier = part.substring(5);
            } else if (part.equals("nostorm")) {
                storm = false;
            } else if (part.startsWith("preset=")) {
                preset = part.substring(7);
            } else {
                ages = Arrays.stream(part.split(",")).mapToDouble(Double::parseDouble).toArray();
            }
        }
        try (CloudScene s = CloudShots.scene(w, h, WeatherPreset.builtIn(preset.toLowerCase(java.util.Locale.ROOT)))) {
            if (!tier.isEmpty()) {
                Clouds.SLOT.choose(s.settings, tier.equals("skybox") ? Clouds.SKYBOX : tier.equals("layered")
                    ? Clouds.LAYERED : Clouds.VOLUMETRIC);
            }
            if (!settings.isEmpty()) {
                s.settings.load(settings);
            }
            if (storm) {
                s.weather.spawnStorm(-20_000, 0, 0);
            }
            StormSimulation storms = s.weather.storms();
            int cell = first(storms);
            double done = 0;
            for (double age : ages) {
                double cx = cell >= 0 ? storms.position(cell, 0) : 0;
                double cz = cell >= 0 ? storms.position(cell, 2) : -20_000;
                CloudShots.run(s, age - done, cx, 0, cz);
                done = age;
                cx = cell >= 0 ? storms.position(cell, 0) : 0;
                cz = cell >= 0 ? storms.position(cell, 2) : -20_000;
                // From the south, the cell north of the camera.
                s.look(cx, 2, cz + distance * 1000, 0, Math.tan(Math.toRadians(up)), -1, Math.toRadians(60));
                double[] sky = new double[3];
                s.clouds.skyLight(sky);
                if (cell >= 0) {
                    System.out.printf("Cell at %.0f min: top %.0f, anvil %.2f, core radius %.0f, %s, stage %s; sky "
                        + "light %.0f %.0f %.0f%n", age, storms.top(cell), storms.anvil(cell), storms.coreRadius(cell),
                        storms.cloudType(cell), storms.stage(cell), sky[0], sky[1], sky[2]);
                }
                for (String sun : suns) {
                    String[] v = sun.split(",");
                    s.sun(Double.parseDouble(v[0]), Double.parseDouble(v[1]));
                    for (String debug : debugs) {
                        s.settings.set(CloudSettings.DEBUG, Integer.parseInt(debug));
                        CloudShots.shot(s, String.format("cell-%03.0f-sun%s%s%s", age, v[1],
                            debug.equals("0") ? "" : "-debug" + debug, tier.isEmpty() ? "" : "-" + tier), 24, 0);
                    }
                    s.settings.set(CloudSettings.DEBUG, 0);
                }
            }
        }
    }

    /** The weather run to {@code minutes} after the cell formed, its focus on it. */
    private static void runTo(CloudScene s, int cell, double minutes, double formed) {
        StormSimulation storms = s.weather.storms();
        CloudShots.run(s, formed + minutes - s.weather.time() / 60, storms.position(cell, 0), 0,
            storms.position(cell, 2));
    }

    /** Where a film's camera keeps to the cell: {@code back} metres south of its core, 2 m up. */
    private static void beside(CloudScene s, int cell, double back, double up, double fovDegrees) {
        StormSimulation storms = s.weather.storms();
        if (storms.active(cell)) {
            s.look(storms.position(cell, 0), 2, storms.position(cell, 2) + back, 0, Math.tan(Math.toRadians(up)), -1,
                Math.toRadians(fovDegrees));
        }
    }

    /**
     * A storm cell's whole life, slowed: from the first cumulus to the anvil thinning away, 75 minutes of weather in
     * 30 s (5 s a frame), from 35 km south of it, the camera keeping its place beside the cell as the wind carries it
     * (a still view of its growth), the sun from the east. {@code TRUESIM_FILM_SIZE} (e.g. 640x360) for side by side.
     */
    @Test
    void lifeCycle() throws Exception {
        assumeThat(wanted()).isTrue();
        String size = System.getenv().getOrDefault("TRUESIM_FILM_SIZE", "1280x720");
        int w = Integer.parseInt(size.split("x")[0]);
        int h = Integer.parseInt(size.split("x")[1]);
        try (CloudScene s = CloudShots.scene(w, h, WeatherPreset.FAIR)) {
            s.flashLights(4);
            s.weather.spawnStorm(-20_000, 0, 0);
            int cell = first(s.weather.storms());
            s.sun(35, 80);
            CloudShots.film(s, "film-life-cycle", 900, 2, 5, 0, f -> beside(s, cell, 35_000, 10, 60));
        }
    }

    /**
     * Where the top popped in: a cell from 10 to 28 minutes (3 s a frame, 360 frames), framed on its upper part from
     * 30 km.
     */
    @Test
    void topRegion() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = CloudShots.scene(1280, 720, WeatherPreset.FAIR)) {
            s.weather.spawnStorm(-20_000, 0, 0);
            int cell = first(s.weather.storms());
            runTo(s, cell, 10, 0);
            s.sun(35, 80);
            CloudShots.film(s, "film-top-region", 360, 2, 3, 0, f -> beside(s, cell, 30_000, 16, 40));
        }
    }

    /**
     * A mature multicell storm from 30 km, still (real time, 6 s): an older cell's anvil spread downwind, the cell
     * after it towering on its flank, a third young; the sun from the west.
     */
    @Test
    void multicell() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = CloudShots.scene(1280, 720, WeatherPreset.FAIR)) {
            s.flashLights(4);
            s.weather.spawnStorm(-20_000, 0, 0);
            int cell = first(s.weather.storms());
            runTo(s, cell, 14, 0);
            StormSimulation storms = s.weather.storms();
            // The next cells on the old one's southern (right-hand) flank, as multicell storms grow.
            s.weather.spawnStorm(storms.position(cell, 0) - 3000, 0, storms.position(cell, 2) + 9000);
            runTo(s, cell, 26, 0);
            s.weather.spawnStorm(storms.position(cell, 0) - 5000, 0, storms.position(cell, 2) + 17_000);
            runTo(s, cell, 34, 0);
            double x = storms.position(cell, 0);
            double z = storms.position(cell, 2);
            s.sun(30, 250).look(x - 4000, 2, z + 40_000, 0.1, Math.tan(Math.toRadians(11)), -1, Math.toRadians(55));
            CloudShots.shot(s, "multicell-storm", 24, 0);
            CloudShots.film(s, "film-multicell", 180, 1, 1.0 / 30, 0, f -> { });
        }
    }

    /**
     * Flying past a mature cell's tower: 20 s at 300 m/s, 5 km up, 3 km from its western flank, turning to keep the
     * tower in view; the volumetric tier, the sun from the south-west.
     */
    @Test
    void flyBy() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = CloudShots.scene(1280, 720, WeatherPreset.FAIR)) {
            Clouds.SLOT.choose(s.settings, Clouds.VOLUMETRIC);
            s.weather.spawnStorm(-20_000, 0, 0);
            int cell = first(s.weather.storms());
            runTo(s, cell, 24, 0);
            StormSimulation storms = s.weather.storms();
            double cx = storms.position(cell, 0);
            double cz = storms.position(cell, 2);
            double r = storms.coreRadius(cell);
            s.sun(35, 225);
            CloudShots.film(s, "film-fly-by", 600, 1, 1.0 / 30, 0, f -> {
                double x = cx - 1.35 * r - 3000;
                double z = cz + 3000 - 10.0 * f;
                s.look(x, 5000, z, cx - x, 1200, cz - z - 2500, Math.toRadians(65));
            });
        }
    }

    /**
     * Rain curtains from 20 km: a mature cell's rain shafts under its base in daylight, 10 minutes of weather in 10 s
     * (2 s a frame), the sun from the side (south-west).
     */
    @Test
    void rainCurtains() throws Exception {
        assumeThat(wanted()).isTrue();
        try (CloudScene s = CloudShots.scene(1280, 720, WeatherPreset.FAIR)) {
            s.flashLights(4);
            s.weather.spawnStorm(-20_000, 0, 0);
            int cell = first(s.weather.storms());
            runTo(s, cell, 22, 0);
            s.sun(30, 230);
            beside(s, cell, 20_000, 5, 50);
            CloudShots.shot(s, "rain-curtains", 24, 0);
            CloudShots.film(s, "film-rain-curtains", 300, 2, 2, 0, f -> beside(s, cell, 20_000, 5, 50));
        }
    }
}
