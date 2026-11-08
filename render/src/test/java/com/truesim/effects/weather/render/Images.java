package com.truesim.effects.weather.render;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/** Writes the tests' float images as PNGs: exposed, ACES-fitted (Narkowicz 2015), sRGB encoded. */
final class Images {

    private Images() {
    }

    /** The scratch directory for pictures: TRUESIM_SHOTS, else build/shots. */
    static Path directory() {
        String dir = System.getenv("TRUESIM_SHOTS");
        Path path = Path.of(dir != null ? dir : "build/shots");
        try {
            Files.createDirectories(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    /** {@code rgb} (cd/m²) times {@code exposure}, tone mapped, as {@code name}.png in {@link #directory()}. */
    static Path write(String name, float[] rgb, int width, int height, double exposure) {
        return write(name, rgb, width, height, exposure, null);
    }

    /**
     * As {@link #write(String, float[], int, int, double)}, white balanced by {@code gains} (per channel, null: none),
     * as a camera set to the daylight falling on the scene would be.
     */
    static Path write(String name, float[] rgb, int width, int height, double exposure, double[] gains) {
        double gr = gains == null ? 1 : gains[0];
        double gg = gains == null ? 1 : gains[1];
        double gb = gains == null ? 1 : gains[2];
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = 3 * (y * width + x);
                int r = encode(rgb[o] * exposure * gr);
                int g = encode(rgb[o + 1] * exposure * gg);
                int b = encode(rgb[o + 2] * exposure * gb);
                image.setRGB(x, y, r << 16 | g << 8 | b);
            }
        }
        Path path = directory().resolve(name + ".png");
        try {
            ImageIO.write(image, "png", path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return path;
    }

    private static int encode(double v) {
        double x = Math.max(v, 0);
        double mapped = x * (2.51 * x + 0.03) / (x * (2.43 * x + 0.59) + 0.14);
        double c = Math.clamp(mapped, 0, 1);
        double s = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(s * 255);
    }
}
