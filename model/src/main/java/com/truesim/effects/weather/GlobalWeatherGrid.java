package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * The weather over a whole planet, coarse: six cube faces of {@code faceSize}² texels (the far map from orbit and to
 * the horizon at altitude). Face f has major axis a = f / 2, sign + for even f; a direction d lies on it where |d_a| is
 * largest, at u = d_b / |d_a|, v = d_c / |d_a| in [−1, 1] with b = (a + 1) mod 3, c = (a + 2) mod 3; texel (i, j)
 * covers u ∈ [−1 + 2i/size, −1 + 2(i+1)/size). Renderers use the same mapping. Refreshed in tiles, round-robin, with
 * a budget per step; allocation-free.
 */
public final class GlobalWeatherGrid {

    private final int faceSize;
    private final int tileTexels;
    private final int tiles;
    private final double radius;
    private final float[] data;
    private final int[] tileVersion;
    private final boolean[] filled;
    private final WeatherSample texelSample = new WeatherSample();
    private int version;
    private int cursor;

    @AllowAllocation("made once")
    public GlobalWeatherGrid(int faceSize, int tileTexels, double radius) {
        this.faceSize = Checks.range(faceSize, 2, 1 << 13, "faceSize");
        this.tileTexels = Checks.range(tileTexels, 1, faceSize, "tileTexels");
        if (faceSize % tileTexels != 0) {
            throw new IllegalArgumentException("The face size " + faceSize + " is not a multiple of " + tileTexels);
        }
        this.radius = Checks.above(radius, 0, "radius");
        tiles = faceSize / tileTexels;
        data = new float[6 * 4 * faceSize * faceSize];
        tileVersion = new int[6 * tiles * tiles];
        filled = new boolean[6 * tiles * tiles];
    }

    /** Refreshes up to {@code budget} tiles: unfilled ones first, then in turn. Returns how many. */
    public int refresh(WeatherField field, int budget) {
        int total = 6 * tiles * tiles;
        int done = 0;
        for (int t = 0; t < total && done < budget; t++) {
            if (!filled[t]) {
                fill(t, field);
                done++;
            }
        }
        for (int i = 0; i < total && done < budget; i++) {
            fill(cursor, field);
            cursor = (cursor + 1) % total;
            done++;
        }
        return done;
    }

    private void fill(int tile, WeatherField field) {
        int face = tile / (tiles * tiles);
        int inFace = tile % (tiles * tiles);
        int ti = inFace % tiles;
        int tj = inFace / tiles;
        int a = face / 2;
        double sign = (face & 1) == 0 ? 1 : -1;
        for (int j = 0; j < tileTexels; j++) {
            int y = tj * tileTexels + j;
            double v = -1 + 2 * (y + 0.5) / faceSize;
            for (int i = 0; i < tileTexels; i++) {
                int x = ti * tileTexels + i;
                double u = -1 + 2 * (x + 0.5) / faceSize;
                double da = sign;
                double len = Math.sqrt(1 + u * u + v * v);
                double px = component(0, a, da, u, v) * radius / len;
                double py = component(1, a, da, u, v) * radius / len;
                double pz = component(2, a, da, u, v) * radius / len;
                field.evaluate(px, py, pz, texelSample);
                texelSample.write(data, 4 * ((face * faceSize + y) * faceSize + x));
            }
        }
        filled[tile] = true;
        tileVersion[tile] = ++version;
    }

    /** Component {@code axis} of the cube point (major axis {@code a} at {@code da}, then u, v on the next axes). */
    private static double component(int axis, int a, double da, double u, double v) {
        int rel = Math.floorMod(axis - a, 3);
        return rel == 0 ? da : rel == 1 ? u : v;
    }

    /** Samples the direction (x, y, z) from the planet's centre bilinearly within its face into {@code out}. */
    public boolean sample(double x, double y, double z, WeatherSample out) {
        double ax = Math.abs(x);
        double ay = Math.abs(y);
        double az = Math.abs(z);
        int a = ax >= ay && ax >= az ? 0 : ay >= az ? 1 : 2;
        double major = a == 0 ? x : a == 1 ? y : z;
        if (major == 0) {
            return false;
        }
        int face = 2 * a + (major < 0 ? 1 : 0);
        double m = Math.abs(major);
        double u = (a == 0 ? y : a == 1 ? z : x) / m;
        double v = (a == 0 ? z : a == 1 ? x : y) / m;
        double fu = Math.clamp((u + 1) / 2 * faceSize - 0.5, 0, faceSize - 1);
        double fv = Math.clamp((v + 1) / 2 * faceSize - 0.5, 0, faceSize - 1);
        int x0 = Math.min((int) fu, faceSize - 2);
        int y0 = Math.min((int) fv, faceSize - 2);
        if (!filled[tileOf(face, x0, y0)] || !filled[tileOf(face, x0 + 1, y0 + 1)]
            || !filled[tileOf(face, x0 + 1, y0)] || !filled[tileOf(face, x0, y0 + 1)]) {
            return false;
        }
        float tx = (float) (fu - x0);
        float ty = (float) (fv - y0);
        int i00 = 4 * ((face * faceSize + y0) * faceSize + x0);
        int i10 = i00 + 4;
        int i01 = i00 + 4 * faceSize;
        int i11 = i01 + 4;
        int nearest = tx < 0.5f ? (ty < 0.5f ? i00 : i01) : (ty < 0.5f ? i10 : i11);
        out.set(lerp2(i00, i10, i01, i11, 0, tx, ty), data[nearest + 1], lerp2(i00, i10, i01, i11, 2, tx, ty),
            lerp2(i00, i10, i01, i11, 3, tx, ty));
        return true;
    }

    private int tileOf(int face, int x, int y) {
        return face * tiles * tiles + (y / tileTexels) * tiles + x / tileTexels;
    }

    private float lerp2(int a, int b, int c, int d, int ch, float fx, float fy) {
        float top = data[a + ch] + (data[b + ch] - data[a + ch]) * fx;
        float bottom = data[c + ch] + (data[d + ch] - data[c + ch]) * fx;
        return top + (bottom - top) * fy;
    }

    public int faceSize() {
        return faceSize;
    }

    /** Tiles per face edge. */
    public int tilesPerEdge() {
        return tiles;
    }

    /** RGBA float texels, face by face, row by row. Read only. */
    public float[] data() {
        return data;
    }

    /** The version of tile {@code tile} (face-major, then row, then column); 0 never written. */
    public int tileVersion(int tile) {
        return tileVersion[tile];
    }

    public int version() {
        return version;
    }
}
