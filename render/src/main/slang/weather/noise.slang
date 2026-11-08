// The cloud noises on the GPU: weather-clouds' CloudNoise, the CPU reference, defined exactly there and ported here
// operation for operation (the same PCG lattice hash, gradient table, fade, Worley feature points and fractal sums), so
// the textures the generators write match CloudNoiseTextures within one step of 255 (CloudNoiseGpuTest).
//
// Perlin: Perlin 2002, "Improving Noise". Worley: Worley 1996, "A Cellular Texture Basis Function". Perlin-Worley,
// the Worley fbm weights and curl distortion: Schneider and Vos 2015, "The Real-Time Volumetric Cloudscapes of Horizon
// Zero Dawn". The hash: Jarzynski and Olano 2020, "Hash Functions for GPU Rendering" (PCG).
module noise;

/** PCG (common-math's Hash.pcg): one round of the permuted congruential generator, as a hash of 32 bits. */
public uint pcg(uint v)
{
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

/** Hash.unit: the top 24 bits as a float in [0, 1). */
public float unitOf(uint h)
{
    return float(h >> 8u) * 5.9604645e-8;
}

/** floorMod(i, period): Java's, for any sign. */
int wrap(int i, int period)
{
    int m = i % period;
    return m < 0 ? m + period : m;
}

/** H(i, j, k): the lattice hash of a cell, wrapped to `period`. */
public uint latticeHash(int i, int j, int k, int period, int seed)
{
    return pcg(pcg(pcg(uint(seed + wrap(i, period))) + uint(wrap(j, period))) + uint(wrap(k, period)));
}

float fade(float t)
{
    return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
}

float grad(uint hash, float x, float y, float z)
{
    uint h = hash & 15u;
    float u = h < 8u ? x : y;
    float v = h < 4u ? y : (h == 12u || h == 14u ? x : z);
    return ((h & 1u) == 0u ? u : -u) + ((h & 2u) == 0u ? v : -v);
}

float lerpOf(float a, float b, float t)
{
    return a + t * (b - a);
}

/** Perlin noise at (x, y, z) in lattice units, tiling every `period`; about [-1, 1]. */
public float perlin(float x, float y, float z, int period, int seed)
{
    float fx = floor(x);
    float fy = floor(y);
    float fz = floor(z);
    int i = int(fx);
    int j = int(fy);
    int k = int(fz);
    float dx = x - fx;
    float dy = y - fy;
    float dz = z - fz;
    float u = fade(dx);
    float v = fade(dy);
    float w = fade(dz);
    float n000 = grad(latticeHash(i, j, k, period, seed), dx, dy, dz);
    float n100 = grad(latticeHash(i + 1, j, k, period, seed), dx - 1, dy, dz);
    float n010 = grad(latticeHash(i, j + 1, k, period, seed), dx, dy - 1, dz);
    float n110 = grad(latticeHash(i + 1, j + 1, k, period, seed), dx - 1, dy - 1, dz);
    float n001 = grad(latticeHash(i, j, k + 1, period, seed), dx, dy, dz - 1);
    float n101 = grad(latticeHash(i + 1, j, k + 1, period, seed), dx - 1, dy, dz - 1);
    float n011 = grad(latticeHash(i, j + 1, k + 1, period, seed), dx, dy - 1, dz - 1);
    float n111 = grad(latticeHash(i + 1, j + 1, k + 1, period, seed), dx - 1, dy - 1, dz - 1);
    return lerpOf(lerpOf(lerpOf(n000, n100, u), lerpOf(n010, n110, u), v),
        lerpOf(lerpOf(n001, n101, u), lerpOf(n011, n111, u), v), w);
}

/** Perlin noise mapped to [0, 1]. */
public float perlin01(float x, float y, float z, int period, int seed)
{
    return clamp(0.5 + 0.5 * perlin(x, y, z, period, seed), 0.0, 1.0);
}

/** Perlin fbm of `octaves` octaves in [0, 1]: octave o at frequency 2^o, amplitude 0.5^o. */
public float perlinFbm(float x, float y, float z, int period, int octaves, int seed)
{
    float sum = 0;
    float amplitude = 1;
    float total = 0;
    int scale = 1;
    for (int o = 0; o < octaves; o++)
    {
        sum += amplitude * perlin01(x * scale, y * scale, z * scale, period * scale, seed);
        total += amplitude;
        amplitude *= 0.5;
        scale *= 2;
    }
    return sum / total;
}

/** Inverted Worley F1 in [0, 1] at (x, y, z) in cell units, tiling every `period` cells: 1 at feature points. */
public float worley(float x, float y, float z, int period, int seed)
{
    float fx = floor(x);
    float fy = floor(y);
    float fz = floor(z);
    int i = int(fx);
    int j = int(fy);
    int k = int(fz);
    float best = 3.4e38;
    for (int c = -1; c <= 1; c++)
    {
        for (int b = -1; b <= 1; b++)
        {
            for (int a = -1; a <= 1; a++)
            {
                uint h1 = latticeHash(i + a, j + b, k + c, period, seed);
                uint h2 = pcg(h1);
                uint h3 = pcg(h2);
                float px = fx + a + unitOf(h1) - x;
                float py = fy + b + unitOf(h2) - y;
                float pz = fz + c + unitOf(h3) - z;
                best = min(best, px * px + py * py + pz * pz);
            }
        }
    }
    return 1.0 - min(sqrt(best), 1.0);
}

/** Worley fbm: 0.625 W(f) + 0.25 W(2f) + 0.125 W(4f). */
public float worleyFbm(float x, float y, float z, int period, int seed)
{
    return 0.625 * worley(x, y, z, period, seed) + 0.25 * worley(2 * x, 2 * y, 2 * z, 2 * period, seed)
        + 0.125 * worley(4 * x, 4 * y, 4 * z, 4 * period, seed);
}

/** Perlin-Worley: the Perlin fbm remapped from [w - 1, 1] to [0, 1] by the Worley fbm w. */
public float perlinWorley(float x, float y, float z, int period, int octaves, int seed)
{
    float p = perlinFbm(x, y, z, period, octaves, seed);
    float w = worleyFbm(x, y, z, period, seed);
    return clamp((p - (w - 1.0)) / (1.0 - (w - 1.0)), 0.0, 1.0);
}

/** CloudNoise.CURL_STEP: the central difference's step in lattice units. */
public static const float CURL_STEP = 1.0 / 512.0;

/** The x component of 2D curl noise: the potential's derivative along y. */
public float curlX(float x, float y, int period, int octaves, int seed)
{
    return (perlinFbm(x, y + CURL_STEP, 0, period, octaves, seed)
        - perlinFbm(x, y - CURL_STEP, 0, period, octaves, seed)) / (2 * CURL_STEP);
}

/** The y component: minus the potential's derivative along x. */
public float curlY(float x, float y, int period, int octaves, int seed)
{
    return -(perlinFbm(x + CURL_STEP, y, 0, period, octaves, seed)
        - perlinFbm(x - CURL_STEP, y, 0, period, octaves, seed)) / (2 * CURL_STEP);
}
