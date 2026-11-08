// Snow lying on surfaces, as a surface shading hook (engine-render's hooks.slang; effects-weather#4, part 1).
//
// Where snow lies: on surfaces the falling snow reaches (a top-down occlusion map: the topmost surface along the fall
// direction over an area round the camera; a point below it is under a roof, an overhang or a tree), that face up
// enough (full up to slopeStart degrees from level, none past slopeEnd, never on vertical or downward faces), where a
// world-space noise thresholded by the coverage says so: at low coverage only patches, at 1 everything the sky reaches.
// The coverage is the scene's (0: exactly no snow anywhere) times what has accumulated where the point is (a texture
// the technique fills while it snows and melts away, kept per column of the world round the camera).
//
// Where it lies, the snow's albedo, roughness and specular replace the surface's and its normal turns towards the
// geometric one (snow hides fine surface detail), before the surface is lit: so in every way of shading, reflections
// and global illumination see the snow too. Ordered after wetness (weather.wetness) where there is one. A material
// refuses it by name (Material.refuseHooks("weather.snow")), an instance by an override entry switching it off.
module snow;

import bindless;
import hooks;

/** The snow's parameters: what content sets (engine-scene's ShadingHookComponent) and what the technique adds. */
[ShadingHookParameters]
public struct SnowParameters
{
    public float3 albedo = float3(0.9, 0.92, 0.95); // the snow's albedo
    public float coverage = 0;                      // 0: none at all; 1: every surface the sky reaches that faces up
    public float3 fallDirection = float3(0, -1, 0); // where the snow falls (slanted by the wind)
    public float roughness = 0.8;                   // the snow's perceptual roughness
    public float noiseScale = 0.6;                  // noise cells per metre
    public float noiseSharpness = 6;                // how sharp the patches' edges are (their band: 1 / this of the noise)
    public float slopeStart = 30;                   // degrees from level up to which snow lies fully
    public float slopeEnd = 55;                     // degrees from level past which none lies
    public float normalBlend = 0.8;                 // how far the snow turns the normal to the geometric one
    public float snowfall = 0;                      // how hard it snows, 0 to 1: the accumulation fills in
    public float melt = 0;                          // how fast it melts, 0 to 1
    // Written by the technique (SimpleSnow) each frame:
    public uint map;                                // the occlusion map: the topmost surface's depth, sampled slot
    public uint mapSize;                            // its texels a side (0: no map: every point reached)
    public uint accumulation;                       // the accumulation (amount, surface height, covered time)
    public uint accumulationSize;                   // its texels a side (0: none: fully accumulated)
    public float bias;                              // depth (map units) below the topmost surface still reached
    public float accumulationTexel;                 // metres per accumulation texel
    public float2 accumulationOffset;               // the origin's texel, in texels (its cell mod the size)
    public float3 originHigh;                       // the map's origin, world: high part
    public float3 originLow;                        // and the rest
    public float4 toMapX;                           // rows of the map's view-projection, from origin-relative positions
    public float4 toMapY;
    public float4 toMapZ;
};

/** A hash of a lattice point, 0 to 1. */
float snowHash(float3 c)
{
    uint3 u = uint3(int3(c) + 32768);
    uint h = u.x * 73856093u ^ u.y * 19349663u ^ u.z * 83492791u;
    h ^= h >> 13;
    h *= 0x5bd1e995u;
    h ^= h >> 15;
    return float(h & 0xffffu) / 65535.0;
}

/** Value noise, 0 to 1, smooth across cells. */
float snowNoise(float3 x)
{
    float3 c = floor(x);
    float3 f = x - c;
    f = f * f * (3 - 2 * f);
    float a = lerp(lerp(snowHash(c), snowHash(c + float3(1, 0, 0)), f.x),
        lerp(snowHash(c + float3(0, 1, 0)), snowHash(c + float3(1, 1, 0)), f.x), f.y);
    float b = lerp(lerp(snowHash(c + float3(0, 0, 1)), snowHash(c + float3(1, 0, 1)), f.x),
        lerp(snowHash(c + float3(0, 1, 1)), snowHash(c + float3(1, 1, 1)), f.x), f.y);
    return lerp(a, b, f.z);
}

/**
 * Where on a coverage of `coverage` (0 to 1) snow lies at noise `n` (0 to 1), its edges `band` wide: exactly 0 at
 * coverage 0 (n never exceeds 1), 1 everywhere at coverage 1, patches growing from the noise's peaks between.
 */
public float snowLying(float n, float coverage, float band)
{
    return saturate((n + coverage * (1 + band) - 1) / band);
}

/** The point's position relative to the map's origin. */
float3 snowRelative(ShadingPoint p, SnowParameters* snow)
{
    return (p.camera - snow[0].originHigh) + (p.cameraLow - snow[0].originLow) + p.position;
}

/** How much of the falling snow reaches `rel` (relative to the map's origin): 1 on the topmost surface, 0 under it. */
float snowReached(SnowParameters* snow, float3 rel)
{
    uint size = snow[0].mapSize;
    if (size == 0)
    {
        return 1;
    }
    float4 x = snow[0].toMapX;
    float4 y = snow[0].toMapY;
    float4 z = snow[0].toMapZ;
    float2 clip = float2(dot(x.xyz, rel) + x.w, dot(y.xyz, rel) + y.w);
    float depth = dot(z.xyz, rel) + z.w;
    float2 uv = clip * 0.5 + 0.5;
    if (any(uv < 0) || any(uv > 1) || depth < 0 || depth > 1)
    {
        return 1; // beyond the map: nothing known over it
    }
    // Percentage-closer: the four nearest texels compared, weighted bilinearly
    float2 texel = uv * float(size) - 0.5;
    int2 base = int2(floor(texel));
    float2 f = texel - float2(base);
    float reached = 0;
    float bias = snow[0].bias;
    uint slot = snow[0].map;
    for (int j = 0; j < 2; j++)
    {
        for (int i = 0; i < 2; i++)
        {
            int2 t = clamp(base + int2(i, j), int2(0), int2(int(size) - 1));
            float top = textures[NonUniformResourceIndex(slot)].Load(int3(t, 0)).r;
            float w = (i == 0 ? 1 - f.x : f.x) * (j == 0 ? 1 - f.y : f.y);
            reached += depth >= top - bias ? w : 0;
        }
    }
    return reached;
}

/** How far the snow has accumulated over `rel` (relative to the map's origin), 0 to 1. */
float snowAccumulated(SnowParameters* snow, float3 rel)
{
    uint size = snow[0].accumulationSize;
    if (size == 0)
    {
        return 1;
    }
    float2 at = (snow[0].accumulationOffset + rel.xz / snow[0].accumulationTexel) / float(size);
    return textures[NonUniformResourceIndex(snow[0].accumulation)].SampleLevel(samplers[LINEAR_REPEAT], at, 0).r;
}

[ShadingHook("weather.snow")]
[ShadingHookAfter("weather.wetness")]
public struct WeatherSnow : ISurfaceHook
{
    public typealias Parameters = SnowParameters;

    public static void surface(ShadingPoint p, SnowParameters* snow, inout Surface s)
    {
        float coverage = snow[0].coverage;
        if (coverage <= 0)
        {
            return; // exactly no snow
        }
        // Slopes shed snow by gravity, whatever the wind: the angle from level
        float facing = p.geometricNormal.y;
        float start = cos(radians(snow[0].slopeStart));
        float end = cos(radians(snow[0].slopeEnd));
        float slope = saturate((facing - end) / max(start - end, 1e-4));
        if (slope <= 0 || facing <= 0)
        {
            return;
        }
        float3 rel = snowRelative(p, snow);
        float reached = snowReached(snow, rel);
        if (reached <= 0)
        {
            return;
        }
        float3 w = p.world() * snow[0].noiseScale;
        float n = 0.65 * snowNoise(w) + 0.35 * snowNoise(w * 2.9 + 17.0);
        // Value noise gathers round 0.5: stretched to 0 to 1 so that the coverage is about the share covered
        n = saturate((n - 0.5) * 2.2 + 0.5);
        float band = 1 / max(snow[0].noiseSharpness, 1e-3);
        float lying = snowLying(n, coverage * snowAccumulated(snow, rel), band) * slope * reached;
        if (lying <= 0)
        {
            return;
        }
        s.albedo = lerp(s.albedo, snow[0].albedo, lying);
        s.roughness = lerp(s.roughness, snow[0].roughness, lying);
        s.metallic = lerp(s.metallic, 0, lying);
        s.f0 = lerp(s.f0, float3(0.02), lying); // ice: IOR 1.31
        s.clearcoat = lerp(s.clearcoat, 0, lying);
        s.transmission = lerp(s.transmission, 0, lying);
        s.normal = normalize(lerp(s.normal, p.geometricNormal, lying * snow[0].normalBlend));
    }
};
