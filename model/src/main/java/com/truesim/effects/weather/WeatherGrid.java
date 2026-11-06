package com.truesim.effects.weather;

import com.truesim.annotations.AllowAllocation;
import java.util.Arrays;

/**
 * A weather map around the camera: {@code size}² texels of four channels ({@link WeatherSample}) over
 * {@code extent}² metres of the domain's plane, in tiles of {@code tileTexels}², addressed toroidally (global texel
 * (gx, gz) is stored at (gx mod size, gz mod size)), so following the camera rewrites only the tiles that came into
 * range, as the design's scrolled weather texture does. Each step refreshes a budget of tiles: first those that are
 * new, then the others in turn (the weather keeps changing). A renderer uploads the tiles whose
 * {@link #tileVersion} changed. Refreshing and sampling allocate nothing.
 */
public final class WeatherGrid {

    private static final long EMPTY = Long.MIN_VALUE;

    private final int size;
    private final int tileTexels;
    private final int tiles;
    private final double texel;
    private final float[] data;
    private final long[] slotTile;
    private final int[] slotVersion;
    private final double[] ground = new double[3];
    private final WeatherSample texelSample = new WeatherSample();
    private int version;
    private int cursor;
    private int centreTileX;
    private int centreTileZ;

    @AllowAllocation("made once")
    public WeatherGrid(int size, double extent, int tileTexels) {
        this.size = Checks.range(size, 2, 1 << 14, "size");
        this.tileTexels = Checks.range(tileTexels, 1, size, "tileTexels");
        if (size % tileTexels != 0) {
            throw new IllegalArgumentException("The grid's size " + size + " is not a multiple of its tiles' "
                + tileTexels);
        }
        tiles = size / tileTexels;
        texel = Checks.above(extent, 0, "extent") / size;
        data = new float[4 * size * size];
        slotTile = new long[tiles * tiles];
        slotVersion = new int[tiles * tiles];
        Arrays.fill(slotTile, EMPTY);
    }

    private static long key(int tx, int tz) {
        return ((long) tx << 32) | (tz & 0xffffffffL);
    }

    /** Centres the window on plane point (px, pz). */
    public void recentre(double px, double pz) {
        centreTileX = Math.floorDiv((int) Math.floor(px / texel), tileTexels);
        centreTileZ = Math.floorDiv((int) Math.floor(pz / texel), tileTexels);
    }

    /** Forgets every tile, e.g. after re-anchoring: all are refreshed again. */
    public void invalidate() {
        Arrays.fill(slotTile, EMPTY);
    }

    private int wanted(int slotCoord, int centre) {
        int base = centre - tiles / 2;
        return base + Math.floorMod(slotCoord - base, tiles);
    }

    /** Refreshes up to {@code budget} tiles from {@code field}; returns how many. */
    public int refresh(WeatherField field, WeatherDomain domain, int budget) {
        int done = 0;
        for (int sz = 0; sz < tiles && done < budget; sz++) {
            for (int sx = 0; sx < tiles && done < budget; sx++) {
                long want = key(wanted(sx, centreTileX), wanted(sz, centreTileZ));
                if (slotTile[sz * tiles + sx] != want) {
                    fill(sx, sz, want, field, domain);
                    done++;
                }
            }
        }
        int slots = tiles * tiles;
        for (int i = 0; i < slots && done < budget; i++) {
            int slot = cursor;
            cursor = (cursor + 1) % slots;
            fill(slot % tiles, slot / tiles, slotTile[slot], field, domain);
            done++;
        }
        return done;
    }

    private void fill(int sx, int sz, long tile, WeatherField field, WeatherDomain domain) {
        int tx = (int) (tile >> 32);
        int tz = (int) tile;
        for (int j = 0; j < tileTexels; j++) {
            int gz = tz * tileTexels + j;
            for (int i = 0; i < tileTexels; i++) {
                int gx = tx * tileTexels + i;
                domain.planeToGround((gx + 0.5) * texel, (gz + 0.5) * texel, ground, 0);
                field.evaluate(ground[0], ground[1], ground[2], texelSample);
                texelSample.write(data, 4 * (Math.floorMod(gz, size) * size + Math.floorMod(gx, size)));
            }
        }
        int slot = sz * tiles + sx;
        slotTile[slot] = tile;
        slotVersion[slot] = ++version;
    }

    private boolean valid(int gx, int gz) {
        int tx = Math.floorDiv(gx, tileTexels);
        int tz = Math.floorDiv(gz, tileTexels);
        return slotTile[Math.floorMod(tz, tiles) * tiles + Math.floorMod(tx, tiles)] == key(tx, tz);
    }

    /**
     * Samples plane point (px, pz) bilinearly (the type from the nearest texel) into {@code out}; false, with
     * {@code out} untouched, where the map does not hold it.
     */
    public boolean sample(double px, double pz, WeatherSample out) {
        double u = px / texel - 0.5;
        double v = pz / texel - 0.5;
        int x0 = (int) Math.floor(u);
        int z0 = (int) Math.floor(v);
        if (!valid(x0, z0) || !valid(x0 + 1, z0) || !valid(x0, z0 + 1) || !valid(x0 + 1, z0 + 1)) {
            return false;
        }
        float fx = (float) (u - x0);
        float fz = (float) (v - z0);
        int a = index(x0, z0);
        int b = index(x0 + 1, z0);
        int c = index(x0, z0 + 1);
        int d = index(x0 + 1, z0 + 1);
        int nearest = fx < 0.5f ? (fz < 0.5f ? a : c) : (fz < 0.5f ? b : d);
        out.set(bilinear(a, b, c, d, 0, fx, fz), data[nearest + 1], bilinear(a, b, c, d, 2, fx, fz),
            bilinear(a, b, c, d, 3, fx, fz));
        return true;
    }

    private int index(int gx, int gz) {
        return 4 * (Math.floorMod(gz, size) * size + Math.floorMod(gx, size));
    }

    private float bilinear(int a, int b, int c, int d, int ch, float fx, float fz) {
        float top = data[a + ch] + (data[b + ch] - data[a + ch]) * fx;
        float bottom = data[c + ch] + (data[d + ch] - data[c + ch]) * fx;
        return top + (bottom - top) * fz;
    }

    /** Texels per edge. */
    public int size() {
        return size;
    }

    /** Metres per texel. */
    public double texelSize() {
        return texel;
    }

    public int tileTexels() {
        return tileTexels;
    }

    /** Tiles per edge. */
    public int tilesPerEdge() {
        return tiles;
    }

    /** The texels, RGBA float, row by row, toroidally addressed. Read only. */
    public float[] data() {
        return data;
    }

    /** The version of the tile in slot (sx, sz) (0 never written): changed means re-upload. */
    public int tileVersion(int sx, int sz) {
        return slotVersion[sz * tiles + sx];
    }

    /** The latest version written. */
    public int version() {
        return version;
    }

    /** The window's centre tile, x. */
    public int centreTileX() {
        return centreTileX;
    }

    /** The window's centre tile, z. */
    public int centreTileZ() {
        return centreTileZ;
    }
}
