package com.truesim.effects.weather.clouds;

import com.truesim.common.math.random.Hash;

/**
 * The cloud noises, CPU reference: tileable Perlin (gradient) noise, Worley (cellular) noise, their fractal sums, the
 * Perlin–Worley mix of Schneider 2015, and curl noise. The GPU generators (Slang, a later stage) implement the same
 * definitions; this class is what they are tested against.
 *
 * <h2>Exact definitions</h2>
 * All arithmetic is 32-bit: integer hashing is exact on both sides; float results agree within the stated tolerance
 * (GPUs may fuse multiply-adds). Coordinates are in lattice units: a point x in [0, period) covers one tile.
 * <ul>
 * <li><b>Lattice hash</b> H(i, j, k) = pcg(pcg(pcg(seed + w(i)) + w(j)) + w(k)), w(i) = floorMod(i, period),
 *     with {@code Hash.pcg} (PCG, Jarzynski and Olano 2020), int arithmetic wrapping.</li>
 * <li><b>Perlin</b> (Perlin 2002, "Improving Noise"): gradient index g = H &amp; 15 with Perlin's grad(g, x, y, z)
 *     (12 edge directions, 4 repeated); fade f(t) = t³(t(6t − 15) + 10); trilinear blend of the 8 corners. Range about
 *     [−1, 1]; {@link #perlin01} maps it to 0.5 + 0.5 n, clamped.</li>
 * <li><b>Worley</b>: one feature point per cell, at the cell's corner + (u(h₁), u(h₂), u(h₃)) with h₁ = H(i, j, k),
 *     h₂ = pcg(h₁), h₃ = pcg(h₂) and u = {@code Hash.unit} (top 24 bits / 2²⁴); F1 = the smallest Euclidean distance
 *     over the 27 neighbouring cells; the result is 1 − min(F1, 1) (inverted: 1 at feature points).</li>
 * <li><b>Fractal sums</b>: Perlin fbm sums octave o at frequency 2ᵒ (period 2ᵒ·period) with amplitude 0.5ᵒ, divided
 *     by the amplitudes' sum. Worley fbm = 0.625 W(f) + 0.25 W(2f) + 0.125 W(4f) (Schneider 2015).</li>
 * <li><b>Perlin–Worley</b> = clamp((p − (w − 1)) / (1 − (w − 1)), 0, 1): the Perlin fbm p (in [0, 1]) remapped from
 *     [w − 1, 1] to [0, 1] by the Worley fbm w, which gives billowy, connected shapes (Schneider 2015).</li>
 * <li><b>Curl</b> (2D, for distortion): the curl of the potential ψ(x, y) = Perlin fbm at (x, y, 0):
 *     (∂ψ/∂y, −∂ψ/∂x) by central differences with step {@value #CURL_STEP} lattice units.</li>
 * </ul>
 * Tolerance between CPU and GPU: 1e-5 absolute on each float result; RGBA8 textures within one step (1/255).
 * Nothing here allocates.
 */
public final class CloudNoise {

    /** The central-difference step of {@link #curlX} and {@link #curlY}, in lattice units. */
    public static final float CURL_STEP = 1f / 512f;

    private CloudNoise() {
    }

    /** H(i, j, k): the lattice hash of a cell, wrapped to {@code period}. */
    public static int hash(int i, int j, int k, int period, int seed) {
        return Hash.pcg(Hash.pcg(Hash.pcg(seed + Math.floorMod(i, period)) + Math.floorMod(j, period))
            + Math.floorMod(k, period));
    }

    private static float fade(float t) {
        return t * t * t * (t * (t * 6f - 15f) + 10f);
    }

    private static float grad(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : (h == 12 || h == 14 ? x : z);
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    /** Perlin noise at (x, y, z) in lattice units, tiling every {@code period}; about [−1, 1]. */
    public static float perlin(float x, float y, float z, int period, int seed) {
        float fx = (float) Math.floor(x);
        float fy = (float) Math.floor(y);
        float fz = (float) Math.floor(z);
        int i = (int) fx;
        int j = (int) fy;
        int k = (int) fz;
        float dx = x - fx;
        float dy = y - fy;
        float dz = z - fz;
        float u = fade(dx);
        float v = fade(dy);
        float w = fade(dz);
        float n000 = grad(hash(i, j, k, period, seed), dx, dy, dz);
        float n100 = grad(hash(i + 1, j, k, period, seed), dx - 1, dy, dz);
        float n010 = grad(hash(i, j + 1, k, period, seed), dx, dy - 1, dz);
        float n110 = grad(hash(i + 1, j + 1, k, period, seed), dx - 1, dy - 1, dz);
        float n001 = grad(hash(i, j, k + 1, period, seed), dx, dy, dz - 1);
        float n101 = grad(hash(i + 1, j, k + 1, period, seed), dx - 1, dy, dz - 1);
        float n011 = grad(hash(i, j + 1, k + 1, period, seed), dx, dy - 1, dz - 1);
        float n111 = grad(hash(i + 1, j + 1, k + 1, period, seed), dx - 1, dy - 1, dz - 1);
        return lerp(lerp(lerp(n000, n100, u), lerp(n010, n110, u), v),
            lerp(lerp(n001, n101, u), lerp(n011, n111, u), v), w);
    }

    /** {@link #perlin} mapped to [0, 1]. */
    public static float perlin01(float x, float y, float z, int period, int seed) {
        return Math.clamp(0.5f + 0.5f * perlin(x, y, z, period, seed), 0f, 1f);
    }

    /** Perlin fbm of {@code octaves} octaves in [0, 1]. */
    public static float perlinFbm(float x, float y, float z, int period, int octaves, int seed) {
        float sum = 0;
        float amplitude = 1;
        float total = 0;
        int scale = 1;
        for (int o = 0; o < octaves; o++) {
            sum += amplitude * perlin01(x * scale, y * scale, z * scale, period * scale, seed);
            total += amplitude;
            amplitude *= 0.5f;
            scale *= 2;
        }
        return sum / total;
    }

    /** Inverted Worley F1 noise in [0, 1] at (x, y, z) in cell units, tiling every {@code period} cells. */
    public static float worley(float x, float y, float z, int period, int seed) {
        float fx = (float) Math.floor(x);
        float fy = (float) Math.floor(y);
        float fz = (float) Math.floor(z);
        int i = (int) fx;
        int j = (int) fy;
        int k = (int) fz;
        float best = Float.MAX_VALUE;
        for (int c = -1; c <= 1; c++) {
            for (int b = -1; b <= 1; b++) {
                for (int a = -1; a <= 1; a++) {
                    int h1 = hash(i + a, j + b, k + c, period, seed);
                    int h2 = Hash.pcg(h1);
                    int h3 = Hash.pcg(h2);
                    float px = fx + a + Hash.unit(h1) - x;
                    float py = fy + b + Hash.unit(h2) - y;
                    float pz = fz + c + Hash.unit(h3) - z;
                    best = Math.min(best, px * px + py * py + pz * pz);
                }
            }
        }
        return 1f - Math.min((float) Math.sqrt(best), 1f);
    }

    /** Worley fbm: 0.625 W(f) + 0.25 W(2f) + 0.125 W(4f), with f = 1 at the given coordinates. */
    public static float worleyFbm(float x, float y, float z, int period, int seed) {
        return 0.625f * worley(x, y, z, period, seed) + 0.25f * worley(2 * x, 2 * y, 2 * z, 2 * period, seed)
            + 0.125f * worley(4 * x, 4 * y, 4 * z, 4 * period, seed);
    }

    /** Perlin–Worley: the Perlin fbm remapped by the Worley fbm (Schneider 2015), in [0, 1]. */
    public static float perlinWorley(float x, float y, float z, int period, int octaves, int seed) {
        float p = perlinFbm(x, y, z, period, octaves, seed);
        float w = worleyFbm(x, y, z, period, seed);
        return Math.clamp((p - (w - 1f)) / (1f - (w - 1f)), 0f, 1f);
    }

    /** The x component of 2D curl noise: ∂ψ/∂y. */
    public static float curlX(float x, float y, int period, int octaves, int seed) {
        return (perlinFbm(x, y + CURL_STEP, 0, period, octaves, seed)
            - perlinFbm(x, y - CURL_STEP, 0, period, octaves, seed)) / (2 * CURL_STEP);
    }

    /** The y component of 2D curl noise: −∂ψ/∂x. */
    public static float curlY(float x, float y, int period, int octaves, int seed) {
        return -(perlinFbm(x + CURL_STEP, y, 0, period, octaves, seed)
            - perlinFbm(x - CURL_STEP, y, 0, period, octaves, seed)) / (2 * CURL_STEP);
    }
}
