// The cloud noise textures (weather-clouds' CloudNoiseTextures, made on the GPU once): the base shape volume (R
// Perlin-Worley, G B A Worley fbm at 1x, 2x, 4x), the detail volume (R G B Worley fbm at 1x, 2x, 4x) and the 2D curl
// texture (RG, 0.5 + 0.5 c / range). Texel (x, y, z) samples its centre, ((x + 0.5) / size) * frequency lattice units.
import bindless;
import weather.noise;

struct NoisePush
{
    uint image;          // storage slot: volumeImages[] for the volumes, images[] for the curl
    uint size;           // texels per edge
    uint frequency;      // lattice cells per tile
    uint octaves;        // Perlin octaves (shape, curl)
    int seed;
    float range;         // the curl's encoding range
};

[[vk::push_constant]]
ConstantBuffer<NoisePush> push;

float3 lattice(uint3 id)
{
    return (float3(id) + 0.5) / float(push.size) * float(push.frequency);
}

[shader("compute")]
[numthreads(4, 4, 4)]
void shapeMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id >= push.size))
    {
        return;
    }
    float3 p = lattice(id);
    int f = int(push.frequency);
    float4 v;
    v.r = perlinWorley(p.x, p.y, p.z, f, int(push.octaves), push.seed);
    v.g = worleyFbm(p.x, p.y, p.z, f, push.seed);
    v.b = worleyFbm(2 * p.x, 2 * p.y, 2 * p.z, 2 * f, push.seed);
    v.a = worleyFbm(4 * p.x, 4 * p.y, 4 * p.z, 4 * f, push.seed);
    volumeImages[push.image][id] = v;
}

[shader("compute")]
[numthreads(4, 4, 4)]
void detailMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id >= push.size))
    {
        return;
    }
    float3 p = lattice(id);
    int f = int(push.frequency);
    float4 v;
    v.r = worleyFbm(p.x, p.y, p.z, f, push.seed);
    v.g = worleyFbm(2 * p.x, 2 * p.y, 2 * p.z, 2 * f, push.seed);
    v.b = worleyFbm(4 * p.x, 4 * p.y, 4 * p.z, 4 * f, push.seed);
    v.a = 1;
    volumeImages[push.image][id] = v;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void curlMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float2 p = (float2(id.xy) + 0.5) / float(push.size) * float(push.frequency);
    int f = int(push.frequency);
    float cx = curlX(p.x, p.y, f, int(push.octaves), push.seed);
    float cy = curlY(p.x, p.y, f, int(push.octaves), push.seed);
    images[push.image][id.xy] = float4(0.5 + 0.5 * cx / push.range, 0.5 + 0.5 * cy / push.range, 0, 1);
}
