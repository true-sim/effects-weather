package com.truesim.effects.weather;

import com.truesim.effects.atmosphere.Quality;
import com.truesim.effects.weather.clouds.CloudNoise;
import com.truesim.effects.weather.clouds.CloudProfile;
import com.truesim.effects.weather.clouds.CloudType;
import com.truesim.effects.weather.clouds.CloudTypes;
import com.truesim.effects.weather.lightning.BoltGeometry;
import com.truesim.effects.weather.lightning.FlashType;
import com.truesim.effects.weather.lightning.LightningConfig;
import com.truesim.effects.weather.lightning.LightningEvents;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * CPU visualisations of the weather data (TRUESIM_SCREENSHOTS=true; PNGs into TRUESIM_SCREENSHOTS_DIR or
 * build/screenshots). Pictures of what the renderers will be fed; the ray-marched preview is a coarse CPU sketch, not
 * the cloud renderer.
 */
@EnabledIfEnvironmentVariable(named = "TRUESIM_SCREENSHOTS", matches = "true")
class WeatherScreenshots {

    static Path dir() throws Exception {
        String d = System.getenv("TRUESIM_SCREENSHOTS_DIR");
        Path p = Path.of(d == null ? "build/screenshots" : d);
        Files.createDirectories(p);
        return p;
    }

    static Graphics2D graphics(BufferedImage image, Color background) {
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(background);
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        return g;
    }

    /** A colour per cloud type, for the type channel. */
    static final Color[] TYPE_COLORS = {new Color(150, 150, 150), new Color(120, 170, 220), new Color(250, 250, 250),
        new Color(250, 210, 120), new Color(230, 80, 80), new Color(160, 120, 220), new Color(90, 200, 190),
        new Color(70, 90, 160), new Color(200, 240, 255)};

    static int grey(double v) {
        int c = (int) Math.round(255 * Math.max(0, Math.min(1, v)));
        return (c << 16) | (c << 8) | c;
    }

    /** Coverage, type, precipitation and top height of {@code weather}'s field over a square, into four panels. */
    static void channels(BufferedImage image, Weather weather, int ox, int oy, int px, double extent) {
        WeatherSample s = new WeatherSample();
        for (int y = 0; y < px; y++) {
            for (int x = 0; x < px; x++) {
                double wx = (x + 0.5 - px / 2.0) / px * extent;
                double wz = (y + 0.5 - px / 2.0) / px * extent;
                weather.field().evaluate(wx, 0, wz, s);
                image.setRGB(ox + x, oy + y, grey(s.coverage()));
                Color t = TYPE_COLORS[s.cloudType().ordinal()];
                double k = 0.25 + 0.75 * s.coverage();
                image.setRGB(ox + px + 8 + x, oy + y,
                    new Color((int) (t.getRed() * k), (int) (t.getGreen() * k), (int) (t.getBlue() * k)).getRGB());
                double p = s.precipitation();
                image.setRGB(ox + 2 * (px + 8) + x, oy + y,
                    new Color((int) (30 + 40 * p), (int) (30 + 90 * p), (int) (40 + 215 * p)).getRGB());
                double top = s.topHeight() > 0 ? s.topHeight()
                    : CloudTypes.physical().profile(s.cloudType()).top();
                image.setRGB(ox + 3 * (px + 8) + x, oy + y, grey(s.coverage() > 0.02 ? top / 14000 : 0));
            }
        }
    }

    @Test
    void weatherMapPerPreset() throws Exception {
        WeatherPreset[] presets = {WeatherPreset.CLEAR, WeatherPreset.FAIR, WeatherPreset.SCATTERED,
            WeatherPreset.BROKEN, WeatherPreset.OVERCAST, WeatherPreset.ALTOSTRATUS, WeatherPreset.RAIN,
            WeatherPreset.STORM};
        int px = 220;
        int left = 120;
        BufferedImage image = new BufferedImage(left + 4 * (px + 8) + 10, 110 + presets.length * (px + 10) + 60,
            BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image, new Color(24, 26, 30));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Weather map per preset: WeatherField over 64 km × 64 km (x east, z south), seed 1", 12, 28);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        String[] heads = {"R coverage", "G cloud type", "B precipitation/darkness", "A cloud top (0–14 km)"};
        for (int c = 0; c < 4; c++) {
            g.drawString(heads[c], left + c * (px + 8), 96);
        }
        for (int t = 0; t < CloudType.count(); t++) {
            g.setColor(TYPE_COLORS[t]);
            g.fillRect(12 + (t % 5) * 190, 40 + (t / 5) * 18, 12, 12);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(CloudType.of(t).name().toLowerCase().replace('_', ' '), 28 + (t % 5) * 190,
                51 + (t / 5) * 18);
        }
        WeatherConfig config = WeatherConfig.forQuality(Quality.HIGH);
        for (int i = 0; i < presets.length; i++) {
            Weather weather = new Weather(config, WeatherDomain.flat(), presets[i]);
            if (presets[i] == WeatherPreset.STORM) {
                // Storm cells of different ages: 40, 30, 20 and 10 minutes old, no drift (the strip shows motion).
                for (int k = 0; k < 4; k++) {
                    weather.spawnStorm(-21_000 + k * 14_000, 0, (k % 2) * 16_000 - 8_000);
                    for (int step = 0; step < 600; step++) {
                        weather.storms().step(step, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
                    }
                }
            }
            int oy = 110 + i * (px + 10);
            channels(image, weather, left, oy, px, 64_000);
            g.setColor(Color.WHITE);
            g.drawString(presets[i].name(), 12, oy + px / 2);
            if (presets[i] == WeatherPreset.STORM) {
                g.setColor(Color.LIGHT_GRAY);
                g.drawString("+ 4 cells,", 12, oy + px / 2 + 18);
                g.drawString("10–40 min old", 12, oy + px / 2 + 34);
            }
        }
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("4-weather-map-presets.png").toFile());
    }

    @Test
    void stormLifeCycle() throws Exception {
        WeatherConfig config = WeatherConfig.forQuality(Quality.HIGH).withSeed(7);
        Weather weather = new Weather(config, WeatherDomain.flat(), WeatherPreset.SCATTERED);
        weather.setPreset(WeatherPreset.STORM, 600);
        weather.spawnStorm(-15_000, 0, 5_000);
        weather.spawnStorm(5_000, 0, -10_000);
        int frames = 9;
        int px = 300;
        double extent = 100_000;
        BufferedImage image = new BufferedImage(3 * (px + 10) + 20, 3 * (px + 44) + 110, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image, new Color(24, 26, 30));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Storm life cycle: 2 h of StormSimulation (preset scattered → storm), every 15 min, 100 km "
            + "square", 12, 26);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        g.drawString("grey: coverage · red: towering cumulus / cumulonimbus cores · cyan: anvils (ice) · blue tint: rain; "
            + "flashes of the last 15 min: yellow ground, white intracloud", 12, 48);
        g.drawString("rings: cell cores (G growing, M mature, D dissipating); the view follows the 4 km wind (the "
            + "westerlies carry the storms east)", 12, 66);
        LightningEvents events = weather.lightning();
        double[] drift = {0, 0};
        long lastFlash = -1;
        WeatherSample s = new WeatherSample();
        for (int f = 0; f < frames; f++) {
            if (f > 0) {
                for (int k = 0; k < 900; k++) {
                    weather.step();
                    drift[0] += config.wind().east(4000) * weather.state().windScale() * config.step();
                    drift[1] -= config.wind().north(4000) * weather.state().windScale() * config.step();
                    weather.setFocus(drift[0], 0, drift[1]);
                }
            }
            int ox = 10 + (f % 3) * (px + 10);
            int oy = 84 + (f / 3) * (px + 44);
            double viewX = drift[0];
            double viewZ = drift[1];
            for (int y = 0; y < px; y++) {
                for (int x = 0; x < px; x++) {
                    double wx = viewX + (x + 0.5 - px / 2.0) / px * extent;
                    double wz = viewZ + (y + 0.5 - px / 2.0) / px * extent;
                    weather.field().evaluate(wx, 0, wz, s);
                    double c = s.coverage();
                    double r = 30 + 200 * c;
                    double gg = 30 + 200 * c;
                    double b = 36 + 200 * c;
                    if (s.cloudType() == CloudType.CUMULONIMBUS) {
                        gg *= 0.45;
                        b *= 0.45;
                    } else if (s.cloudType() == CloudType.TOWERING_CUMULUS && s.topHeight() > 0) {
                        gg *= 0.75;
                        b *= 0.6;
                    } else if (s.cloudType() == CloudType.CIRRUS) {
                        r *= 0.6;
                    }
                    double p = s.precipitation();
                    r *= 1 - 0.6 * p;
                    gg *= 1 - 0.4 * p;
                    image.setRGB(ox + x, oy + y, new Color((int) Math.min(255, r), (int) Math.min(255, gg),
                        (int) Math.min(255, b)).getRGB());
                }
            }
            StormSimulation storms = weather.storms();
            int[] stages = new int[3];
            g.setStroke(new BasicStroke(1.2f));
            for (int c = 0; c < storms.capacity(); c++) {
                if (!storms.active(c)) {
                    continue;
                }
                stages[storms.stage(c).ordinal()]++;
                double x = ox + ((storms.position(c, 0) - viewX) / extent + 0.5) * px;
                double y = oy + ((storms.position(c, 2) - viewZ) / extent + 0.5) * px;
                if (x < ox || y < oy || x > ox + px || y > oy + px) {
                    continue;
                }
                double rr = storms.coreRadius(c) / extent * px;
                g.setColor(new Color(255, 120, 120));
                g.drawOval((int) (x - rr), (int) (y - rr), (int) (2 * rr), (int) (2 * rr));
                g.drawString("GMD".substring(storms.stage(c).ordinal(), storms.stage(c).ordinal() + 1), (int) x - 4,
                    (int) y + 4);
            }
            int[] flashes = new int[2];
            final int fox = ox;
            final int foy = oy;
            final double fvx = viewX;
            final double fvz = viewZ;
            lastFlash = events.forEachSince(lastFlash, (e, slot, id) -> {
                double x = fox + ((e.end(slot, 0) - fvx) / extent + 0.5) * px;
                double y = foy + ((e.end(slot, 2) - fvz) / extent + 0.5) * px;
                boolean ground = e.type(slot).strikesGround();
                flashes[ground ? 0 : 1]++;
                int rgb = ground ? new Color(255, 230, 60).getRGB() : Color.WHITE.getRGB();
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int qx = (int) x + dx;
                        int qy = (int) y + dy;
                        if (qx >= fox && qy >= foy && qx < fox + px && qy < foy + px) {
                            image.setRGB(qx, qy, rgb);
                        }
                    }
                }
            });
            g.setColor(Color.WHITE);
            g.drawString(String.format("t=%d min · G%d M%d D%d · %d CG, %d IC", f * 15, stages[0], stages[1],
                stages[2], flashes[0], flashes[1]), ox, oy + px + 16);
        }
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("4b-storm-life-cycle.png").toFile());
    }

    @Test
    void cloudProfilesAndNoise() throws Exception {
        CloudTypes types = CloudTypes.physical();
        int col = 130;
        int ph = 420;
        BufferedImage image = new BufferedImage(CloudType.count() * col + 40, ph + 420, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image, new Color(18, 26, 40));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("Cloud vertical profiles (physical defaults): width = density(h)·(1 + anvil(h)), altitude 0–13 km",
            12, 26);
        int base = 40 + ph;
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        g.setColor(new Color(80, 90, 110));
        for (int km = 0; km <= 13; km++) {
            int y = base - km * ph / 13;
            g.drawLine(30, y, image.getWidth() - 10, y);
            g.drawString(km + " km", 2, y + 4);
        }
        for (int t = 0; t < CloudType.count(); t++) {
            CloudProfile p = types.profile(CloudType.of(t));
            int cx = 40 + t * col + col / 2;
            for (int y = 0; y < ph; y++) {
                double alt = (double) (ph - y) / ph * 13000;
                double h = (alt - p.base()) / p.thickness();
                if (h < 0 || h > 1) {
                    continue;
                }
                double d = p.density(h);
                double w = d * (1 + p.anvil(h)) * (col / 2.0 - 8) / 2.5 * 1.0;
                int gray = (int) (110 + 140 * Math.min(1, d));
                g.setColor(p.ice() ? new Color(gray - 30, gray, 255) : new Color(gray, gray, gray));
                g.drawLine((int) (cx - w), 40 + y, (int) (cx + w), 40 + y);
            }
            g.setColor(Color.WHITE);
            g.drawString(CloudType.of(t).name().toLowerCase().replace('_', ' '), cx - col / 2 + 6, base + 18);
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(String.format("σ %.3f /m", p.extinction()), cx - col / 2 + 6, base + 34);
            g.drawString(String.format("droplets %.0f µm", p.dropletDiameter()), cx - col / 2 + 6, base + 48);
        }

        // Noise slices, z = 0.5 lattice units.
        int n = 160;
        int oy = base + 80;
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
        g.drawString("CloudNoise slices (CPU reference; tile = 4 cells; seed 1)", 12, oy - 12);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        String[] names = {"Perlin fbm (4 oct.)", "Worley F1 (inverted)", "Worley fbm", "Perlin–Worley",
            "curl (R = x, G = y)", "Perlin–Worley, 2×2 tiles"};
        for (int k = 0; k < names.length; k++) {
            int ox = 20 + k * (n + 30);
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    float u = (x + 0.5f) / n * 4;
                    float v = (y + 0.5f) / n * 4;
                    int rgb = switch (k) {
                        case 0 -> grey(CloudNoise.perlinFbm(u, v, 0.5f, 4, 4, 1));
                        case 1 -> grey(CloudNoise.worley(u, v, 0.5f, 4, 1));
                        case 2 -> grey(CloudNoise.worleyFbm(u, v, 0.5f, 4, 1));
                        case 3 -> grey(CloudNoise.perlinWorley(u, v, 0.5f, 4, 4, 1));
                        case 4 -> {
                            int r = (int) Math.max(0, Math.min(255, 128 + 128 * CloudNoise.curlX(u, v, 4, 3, 1) / 4));
                            int gg = (int) Math.max(0, Math.min(255, 128 + 128 * CloudNoise.curlY(u, v, 4, 3, 1) / 4));
                            yield (r << 16) | (gg << 8) | 60;
                        }
                        default -> grey(CloudNoise.perlinWorley(u * 2, v * 2, 0.5f, 4, 4, 1));
                    };
                    image.setRGB(ox + x, oy + y, rgb);
                }
            }
            g.setColor(Color.WHITE);
            g.drawString(names[k], ox, oy + n + 16);
        }
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("5-cloud-profiles-and-noise.png").toFile());
    }

    @Test
    void lightningBolts() throws Exception {
        int w = 1200;
        int h = 620;
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = graphics(image, new Color(14, 16, 28));
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 18));
        g.drawString("BoltGeometry: six cloud-to-ground bolts from seeds 1–6 (5 km channels, side view, default "
            + "config: 7 levels, branches)", 12, 26);
        LightningConfig config = LightningConfig.defaults();
        BoltGeometry bolt = new BoltGeometry(config.maxBoltPoints());
        double scale = 0.1; // pixels per metre
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        for (int k = 0; k < 6; k++) {
            double ox = 100 + k * 190;
            bolt.generate(0, 5000, 0, (k - 2.5) * 150, 0, 0, k + 1, config);
            for (int s = 0; s < bolt.segmentCount(); s++) {
                int a = bolt.segmentStart(s);
                int b = bolt.segmentEnd(s);
                float br = bolt.brightness(s);
                g.setStroke(new BasicStroke(Math.max(0.6f, 2.6f * br)));
                g.setColor(new Color(0.75f * br + 0.2f, 0.8f * br + 0.2f, Math.min(1f, br + 0.25f)));
                g.drawLine((int) (ox + bolt.x(a) * scale), (int) (580 - bolt.y(a) * scale), (int) (ox + bolt.x(b)
                    * scale), (int) (580 - bolt.y(b) * scale));
            }
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(String.format("seed %d: %d segments", k + 1, bolt.segmentCount()), (int) ox - 50, 605);
        }
        g.setColor(new Color(60, 60, 60));
        g.drawLine(0, 580, w, 580);
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("6-lightning-bolts.png").toFile());
    }

    @Test
    void rayMarchedPreview() throws Exception {
        // A coarse CPU sketch, not the renderer: single scattering with a short light march, Beer transmittance,
        // Henyey–Greenstein phase, constant ambient; density = profile(type, h) × coverage-remapped Perlin–Worley.
        int w = 640;
        int h = 340;
        WeatherConfig config = WeatherConfig.forQuality(Quality.HIGH).withSeed(3);
        Weather weather = new Weather(config, WeatherDomain.flat(), WeatherPreset.SCATTERED);
        weather.spawnStorm(9_000, 0, -26_000);
        for (int step = 0; step < 1800; step++) {
            weather.storms().step(step, 1, 0, WindProfile.calm(), 1, 0, 0, 0);
        }
        CloudTypes types = CloudTypes.physical();
        double[] sun = normalise(-0.55, 0.35, -0.75); // low sun ahead-left (x east, y up, z south)
        BufferedImage image = new BufferedImage(w, h + 56, BufferedImage.TYPE_INT_RGB);
        WeatherSample s = new WeatherSample();
        double fov = Math.toRadians(75);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                // Camera at 300 m looking north (−z), pitched up 12°.
                double sx = (px + 0.5 - w / 2.0) / w * 2 * Math.tan(fov / 2);
                double sy = -(py + 0.5 - h / 2.0) / w * 2 * Math.tan(fov / 2);
                double pitch = Math.toRadians(12);
                double dx = sx;
                double dy = sy * Math.cos(pitch) + Math.sin(pitch);
                double dz = -(Math.cos(pitch) - sy * Math.sin(pitch));
                double[] d = normalise(dx, dy, dz);
                double[] rgb = sky(d, sun);
                if (d[1] > 0.01) {
                    rgb = march(weather, types, s, 0, 300, 0, d, sun, rgb);
                } else {
                    double t = Math.min(1, -d[1] * 6);
                    rgb = new double[] {0.22 - 0.1 * t, 0.26 - 0.1 * t, 0.2 - 0.08 * t};
                }
                int r = (int) Math.min(255, 255 * Math.pow(rgb[0], 1 / 2.2));
                int gg = (int) Math.min(255, 255 * Math.pow(rgb[1], 1 / 2.2));
                int b = (int) Math.min(255, 255 * Math.pow(rgb[2], 1 / 2.2));
                image.setRGB(px, py, (r << 16) | (gg << 8) | b);
            }
        }
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(Color.BLACK);
        g.fillRect(0, h, w, 56);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.drawString("PREVIEW — coarse CPU ray march of the weather data, NOT the cloud renderer (single scattering,", 6,
            h + 15);
        g.drawString("4-step light march, HG g=0.6, flat ambient, jittered; scattered + a 30 min old storm cell).", 6,
            h + 31);
        g.drawString("The storm core is a disc in the weather map: its ragged edges come from the renderer's detail noise.", 6,
            h + 47);
        g.dispose();
        ImageIO.write(image, "png", dir().resolve("7-cloud-preview-cpu-raymarch.png").toFile());
    }

    private static double[] normalise(double x, double y, double z) {
        double l = Math.sqrt(x * x + y * y + z * z);
        return new double[] {x / l, y / l, z / l};
    }

    private static double[] sky(double[] d, double[] sun) {
        double up = Math.max(0, d[1]);
        double mu = d[0] * sun[0] + d[1] * sun[1] + d[2] * sun[2];
        double glow = Math.pow(Math.max(0, mu), 64) * 2 + Math.pow(Math.max(0, mu), 4) * 0.15;
        return new double[] {0.25 + 0.15 * (1 - up) + glow, 0.38 + 0.12 * (1 - up) + glow * 0.9,
            0.75 - 0.15 * up + glow * 0.7};
    }

    /** Cloud density (1/m) at a point. */
    private static double density(Weather weather, CloudTypes types, WeatherSample s, double x, double y, double z) {
        weather.field().evaluate(x, 0, z, s);
        if (s.coverage() <= 0.01) {
            return 0;
        }
        CloudProfile p = types.profile(s.cloudType());
        double top = s.topHeight() > 0 ? s.topHeight() : p.top();
        if (y < p.base() || y > top) {
            return 0;
        }
        double hf = (y - p.base()) / (top - p.base());
        double profile = p.density(hf);
        if (profile <= 0) {
            return 0;
        }
        double shape = CloudNoise.perlinWorley((float) (x / 3000), (float) (y / 3000), (float) (z / 3000), 1 << 12,
            3, 1);
        // Coverage remap (Schneider 2015): the profile shrinks the coverage towards base and top; capped so storm
        // cores keep noisy edges in this preview.
        double coverage = 0.8 * Math.min(1, s.coverage() + p.anvil(hf) * 0.5) * profile;
        double v = (shape - (1 - coverage)) / Math.max(1e-3, coverage);
        return v <= 0 ? 0 : v * Math.min(p.extinction(), 0.04);
    }

    private static double[] march(Weather weather, CloudTypes types, WeatherSample s, double ox, double oy, double oz,
        double[] d, double[] sun, double[] background) {
        // From the cloud base region to 13 km, at most 60 km away.
        double t0 = Math.max(0, (500 - oy) / d[1]);
        double t1 = Math.min(60_000, (13_000 - oy) / d[1]);
        if (t1 <= t0) {
            return background;
        }
        int steps = 96;
        double dt = (t1 - t0) / steps;
        double transmittance = 1;
        double r = 0;
        double g = 0;
        double b = 0;
        double mu = d[0] * sun[0] + d[1] * sun[1] + d[2] * sun[2];
        double gHg = 0.6;
        double phase = (1 - gHg * gHg) / (4 * Math.PI * Math.pow(1 + gHg * gHg - 2 * gHg * mu, 1.5));
        double jitter = com.truesim.common.math.random.Hash.random(Double.hashCode(d[0] * 7919 + d[1] * 104729), 1);
        for (int i = 0; i < steps && transmittance > 0.02; i++) {
            double t = t0 + (i + jitter) * dt;
            double x = ox + d[0] * t;
            double y = oy + d[1] * t;
            double z = oz + d[2] * t;
            double sigma = density(weather, types, s, x, y, z);
            if (sigma <= 0) {
                continue;
            }
            double od = 0;
            for (int k = 1; k <= 4; k++) {
                double l = k * 350.0;
                od += density(weather, types, s, x + sun[0] * l, y + sun[1] * l, z + sun[2] * l) * 350;
            }
            double light = Math.exp(-od) * phase * 9 + 0.08 + 0.12 * Math.exp(-od * 0.25);
            double stepT = Math.exp(-sigma * dt);
            double scattered = (1 - stepT) * transmittance;
            r += scattered * light * 1.0;
            g += scattered * light * 0.97;
            b += scattered * light * 0.93;
            transmittance *= stepT;
        }
        // Aerial haze with distance.
        return new double[] {r + transmittance * background[0], g + transmittance * background[1],
            b + transmittance * background[2]};
    }
}
