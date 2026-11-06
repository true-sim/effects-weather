package com.truesim.effects.weather.clouds;

/**
 * The cloud noise textures as the GPU will hold them, generated on the CPU from {@link CloudNoise}: the reference the
 * GPU generators are compared with (hash or per-texel tolerance), and a fallback where compute is unavailable. Texel
 * (x, y, z) samples its centre, ((x + 0.5) / size)·frequency lattice units; channels are quantised to bytes as
 * round(clamp(v, 0, 1)·255). Nothing allocates; the caller owns the arrays.
 */
public final class CloudNoiseTextures {

    private CloudNoiseTextures() {
    }

    /**
     * The base shape volume, size³ RGBA8 (x fastest): R Perlin–Worley at {@code frequency}, G, B, A Worley fbm at
     * {@code frequency}, 2×, 4× (Schneider 2015). {@code out} holds 4·size³ bytes.
     */
    public static void shape(int size, int frequency, int perlinOctaves, int seed, byte[] out) {
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float u = (x + 0.5f) / size * frequency;
                    float v = (y + 0.5f) / size * frequency;
                    float w = (z + 0.5f) / size * frequency;
                    int o = 4 * ((z * size + y) * size + x);
                    out[o] = quantise(CloudNoise.perlinWorley(u, v, w, frequency, perlinOctaves, seed));
                    out[o + 1] = quantise(CloudNoise.worleyFbm(u, v, w, frequency, seed));
                    out[o + 2] = quantise(CloudNoise.worleyFbm(2 * u, 2 * v, 2 * w, 2 * frequency, seed));
                    out[o + 3] = quantise(CloudNoise.worleyFbm(4 * u, 4 * v, 4 * w, 4 * frequency, seed));
                }
            }
        }
    }

    /**
     * The detail volume, size³ RGBA8: R, G, B Worley fbm at {@code frequency}, 2×, 4×; A = 255. {@code out} holds
     * 4·size³ bytes.
     */
    public static void detail(int size, int frequency, int seed, byte[] out) {
        for (int z = 0; z < size; z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    float u = (x + 0.5f) / size * frequency;
                    float v = (y + 0.5f) / size * frequency;
                    float w = (z + 0.5f) / size * frequency;
                    int o = 4 * ((z * size + y) * size + x);
                    out[o] = quantise(CloudNoise.worleyFbm(u, v, w, frequency, seed));
                    out[o + 1] = quantise(CloudNoise.worleyFbm(2 * u, 2 * v, 2 * w, 2 * frequency, seed));
                    out[o + 2] = quantise(CloudNoise.worleyFbm(4 * u, 4 * v, 4 * w, 4 * frequency, seed));
                    out[o + 3] = (byte) 255;
                }
            }
        }
    }

    /**
     * The curl texture, size² RG8: the curl encoded as 0.5 + 0.5·c / {@code range}, clamped. {@code out} holds
     * 2·size² bytes.
     */
    public static void curl(int size, int frequency, int octaves, float range, int seed, byte[] out) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float u = (x + 0.5f) / size * frequency;
                float v = (y + 0.5f) / size * frequency;
                int o = 2 * (y * size + x);
                out[o] = quantise(0.5f + 0.5f * CloudNoise.curlX(u, v, frequency, octaves, seed) / range);
                out[o + 1] = quantise(0.5f + 0.5f * CloudNoise.curlY(u, v, frequency, octaves, seed) / range);
            }
        }
    }

    /** round(clamp(v, 0, 1)·255) as an unsigned byte. */
    public static byte quantise(float v) {
        return (byte) Math.round(Math.clamp(v, 0f, 1f) * 255f);
    }

    /** FNV-1a (64-bit) of {@code data}: the fingerprint tests and GPU comparisons use. */
    public static long fingerprint(byte[] data) {
        long h = 0xcbf29ce484222325L;
        for (byte b : data) {
            h ^= b & 0xff;
            h *= 0x100000001b3L;
        }
        return h;
    }
}
