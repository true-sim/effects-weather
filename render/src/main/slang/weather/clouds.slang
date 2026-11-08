// Clouds, rain shafts and lightning in the media between the camera and the scene: the weather map's channels, the
// cloud types' vertical profiles, the noise volumes, physical extinction, the approximate Mie phase, multiple
// scattering, the sun through the atmosphere, the sky's ambient light, the in-cloud lightning glow, and the Beer shadow
// map. Shared by the march (march.slang), the cheap 2D tier (composite.slang), the shadow map (shadowmap.slang) and the
// surfaces' cloud shadow mask (mask.slang). All lengths in metres, coefficients in 1/m, light in the engine's physical
// units: illuminance in lux, luminous intensity in candela, radiance in cd/m^2 (the scene's lights' units).
//
// Sources:
// - Schneider and Vos 2015 (Horizon Zero Dawn, SIGGRAPH Advances) and Schneider 2017 (Nubis): weather map, height
//   profiles per type, coverage remap of the base shape, erosion by detail noise, curl distortion, two-speed march,
//   cone light march.
// - Hillaire 2016 (Frostbite, "Physically Based Sky, Atmosphere and Cloud Rendering"): energy-conserving integration
//   of each step, multiple scattering octaves after Wrenninge 2013 ("Oz: The Great and Volumetric"), ambient by height.
// - Jendersie and d'Eon 2023 ("An Approximate Mie Scattering Function for Fog and Cloud Rendering"): the HG + Draine
//   phase function from the droplet diameter.
// - Hillaire 2020 ("A Scalable and Production Ready Sky and Atmosphere Rendering Technique", and the UE volumetric
//   clouds course): the Beer shadow map (front depth, mean extinction, maximum optical depth).
// - Schueler 2012 (GPU Pro 3, "An Approximation to the Chapman Grazing-Incidence Function for Atmospheric
//   Scattering"): the analytic atmosphere's optical depth, until effects-atmosphere's transmittance table replaces it.
// - Marshall and Palmer 1948: the rain's drop size distribution, from which its extinction follows (geometric optics).
// - Dobashi et al. 2001 and the diffusion approximation (Ishimaru 1978): lightning lighting clouds from inside.
module clouds;

import bindless;

/** The cloud types (weather-clouds' CloudType, in its order). */
public static const uint CLOUD_TYPES = 9;
/** The flashes lighting the clouds at once, at most. */
public static const uint MAX_FLASHES = 8;
/** The type whose particles are ice (cirrus): its phase is Henyey-Greenstein. */
public static const uint CIRRUS = 8;
/**
 * The base shape (Perlin-Worley remapped by the low-frequency Worley fbm) lies between these quantiles, 5 % and 95 %,
 * of the CloudNoiseTextures volume (measured on the CPU reference; CloudCoverageTest checks the cloud fraction): it is
 * stretched to [0, 1] so that the coverage remap's threshold 1 - coverage leaves a cloud fraction about the coverage.
 */
public static const float SHAPE_LOW = 0.697;
public static const float SHAPE_HIGH = 0.854;
/** Henyey-Greenstein g of cloud droplets for similarity (diffusion): sigma (1 - g) is the reduced extinction. */
public static const float CLOUD_G = 0.85;

/** One cloud type's layer and microphysics (weather-clouds' CloudProfile, art applied). */
[HostLayout]
public struct CloudType
{
    public float base;          // the layer's base, metres above the ground
    public float top;           // its top (the weather map's top channel may override it)
    public float extinction;    // 1/m at density 1
    public float shear;         // how far its upper part leans downwind, a fraction of its thickness
    public float droplet;       // effective droplet diameter, micrometres (the approximate Mie phase)
    public float absorption;    // single-scattering co-albedo
    public float erosion;       // 0 billowy, 1 wispy
    public float ice;           // 1: ice crystals (Henyey-Greenstein)
};

/** One flash's channel lighting the clouds: from `a` to `b` (relative to the frame's anchor), its intensity now. */
[HostLayout]
public struct Flash
{
    public float3 a;
    public float intensity;     // luminous intensity this frame, candela (the frame's mean: Flicker.average)
    public float3 b;
    public float radius;        // the channel's glow radius, metres: distances below it count as it
};

/**
 * What every cloud pass of a frame reads: the weather maps, the types, the noise's placement, the sun and sky, the
 * atmosphere, the lighting model's parameters, the flashes and the shadow map. Written once a frame by the CPU
 * (WeatherGpu), read field by field.
 */
[HostLayout]
public struct CloudFrame
{
    public float3 sunDirection;     // unit, towards the sun, world axes (y up)
    public float sunVisible;        // 1 above the horizon's shadow of the planet, else 0
    public float3 sunIlluminance;   // lux, outside the atmosphere
    public uint flashCount;
    public float3 skyIlluminance;   // lux: the sky's light on a horizontal surface above the clouds (the ambient)
    public float groundAlbedo;
    public float3 apRadiance;       // cd/m^2: the sky's radiance near the horizon (aerial perspective's in-scattering)
    public float maxAltitude;       // the highest cloud top, metres
    public uint nearMap;            // sampled slots of the weather maps (RGBA16F: coverage, type, rain, top)
    public uint farMap;
    public uint profileAtlas;       // RGBA16F: per type a row of heights (density, anvil)
    public uint profileHeights;
    public float4 nearBounds;       // where the near map holds the weather: min x, min z, max x, max z from the anchor
    public float4 farBounds;
    public float2 nearAnchor;       // the anchor's texture coordinates in the near map (toroidal: the fraction)
    public float2 farAnchor;
    public float nearExtent;        // metres the maps span
    public float farExtent;
    public uint nearSize;           // texels per edge
    public uint farSize;
    public float2 wind;             // unit: where the steering wind blows to, in the ground plane (x, z)
    public float rainMaxRate;       // mm/h at precipitation 1
    public float rainForward;       // Henyey-Greenstein g of rain drops
    public float shapePeriod;       // metres per tile of the base shape volume
    public float detailPeriod;      // metres per tile of the detail volume
    public float curlPeriod;        // metres per tile of the curl texture
    public float curlStrength;      // metres the curl displaces detail lookups at the base
    public float3 shapeAnchor;      // the anchor's position in shape tiles (wind drift applied), fraction
    public float detailStrength;    // how much detail erodes the base shape (1: Schneider's)
    public float3 detailAnchor;     // the anchor's position in detail tiles, fraction
    public float densityScale;      // art: extinction multiplier (1 physical)
    public float2 curlAnchor;
    public float coverageOffset;    // art: added to every coverage (0 physical)
    public float powder;            // art: Schneider's Beer-powder term (0 physical)
    public float planetRadius;      // metres
    public float rayleighHeight;    // scale heights, metres
    public float mieHeight;
    public float mieScattering;     // 1/m at sea level
    public float3 rayleighScattering;
    public float mieExtinction;
    public float msScattering;      // Wrenninge's octaves: a (scattering), b (extinction), c (phase eccentricity)
    public float msExtinction;
    public float msPhase;
    public uint octaves;
    public float ambientDepth;      // the fraction of the optical depth to the layer's top (bottom) that dims the sky's (ground's) light
    public float flashLeak;         // 1/m: the effective absorption of the lightning glow's diffusion (light leaving the cloud)
    public float shadowExtent;      // metres the Beer shadow map spans
    public float shadowDepth;       // the light-space depth of its plane above the anchor
    public float3 shadowCentre;     // its centre on the ground, from the anchor
    public uint shadowSize;
    public float3 shadowU;          // its axes: across, up the map, towards the light
    public float rainExtinctionScale; // multiplies the Marshall-Palmer extinction (1 physical)
    public float3 shadowV;
    public uint pad0;
    public CloudType types[CLOUD_TYPES];
    public Flash flashes[MAX_FLASHES];
};

public static const float PI = 3.14159265358979;

// ------------------------------------------------------------------------------------------------------------ helpers

public float remap(float x, float lo, float hi, float a, float b)
{
    return a + (x - lo) / max(hi - lo, 1.0e-6) * (b - a);
}

/** Interleaved gradient noise (Jimenez 2014), advanced by the golden ratio per frame: a jitter in [0, 1). */
public float jitter(uint2 pixel, uint frame)
{
    float n = frac(52.9829189 * frac(dot(float2(pixel), float2(0.06711056, 0.00583715))));
    return frac(n + 0.61803398875 * float(frame % 64u));
}

// -------------------------------------------------------------------------------------------------------- weather map

/** The weather at a ground point: coverage, type (channel), precipitation, top (0: the type's). */
public struct Weather
{
    public float coverage;
    public float type;
    public float rain;
    public float top;
};

/** A map's texel at texture coordinates `uv` (any, toroidal): the four channels, the type from the nearest texel. */
float4 mapAt(uint map, uint size, float2 uv)
{
    float4 v = textures[map].SampleLevel(samplers[LINEAR_REPEAT], uv, 0);
    float2 t = frac(uv) * float(size);
    int2 texel = int2(min(floor(t), float(size - 1)));
    v.g = textures[map].Load(int3(texel, 0)).g;
    return v;
}

bool inside(float2 p, float4 bounds)
{
    return all(p >= bounds.xy) && all(p <= bounds.zw);
}

/** The weather at plane point `plane` (metres from the anchor): the near map where it holds it, else the far map. */
public Weather weatherAt(CloudFrame* f, float2 plane)
{
    float4 v = 0;
    float4 nb = f[0].nearBounds;
    float4 fb = f[0].farBounds;
    if (inside(plane, nb))
    {
        v = mapAt(f[0].nearMap, f[0].nearSize, f[0].nearAnchor + plane / f[0].nearExtent);
    }
    else if (inside(plane, fb))
    {
        v = mapAt(f[0].farMap, f[0].farSize, f[0].farAnchor + plane / f[0].farExtent);
    }
    Weather w;
    w.coverage = saturate(v.r + f[0].coverageOffset);
    w.type = v.g;
    w.rain = v.b;
    w.top = v.a;
    return w;
}

/** The type of a weather map's type channel (CloudType.fromChannel). */
public uint typeOf(float channel)
{
    return uint(round(saturate(channel) * float(CLOUD_TYPES - 1)));
}

// ------------------------------------------------------------------------------------------------------------ density

/** What a density evaluation found: extinction, its type, the height in its layer, the rain below it. */
public struct CloudPoint
{
    public float extinction;    // 1/m, the cloud's
    public float rain;          // 1/m, the rain's (below the layer's base)
    public uint type;
    public float height;        // in the layer, [0, 1]
    public float thickness;     // the layer's, metres
    public float rainRate;      // mm/h
};

/** The profile atlas at type `type`, height `h`: density and the anvil's widening. */
float2 profileAt(CloudFrame* f, uint type, float h)
{
    float v = (float(type) + 0.5) / float(CLOUD_TYPES);
    return textures[f[0].profileAtlas].SampleLevel(samplers[LINEAR_CLAMP], float2(h, v), 0).rg;
}

/** Rain's extinction (1/m) at `rate` mm/h: Marshall-Palmer drops, extinction efficiency 2, so 3.65e-4 R^0.63. */
public float rainExtinction(float rate)
{
    return rate > 0 ? 3.65e-4 * pow(rate, 0.63) : 0;
}

/**
 * The clouds and rain at a point `rel` from the anchor (altitude `altitude` above the ground): Schneider's density,
 * the base shape remapped by the coverage widened by the anvil, eroded by the detail noise where `detail` (distorted
 * by curl), times the type's physical extinction. `shapeVolume`, `detailVolume`, `curl`: sampled slots.
 */
public CloudPoint cloudAt(CloudFrame* f, float3 rel, float altitude, bool detail, uint shapeVolume,
    uint detailVolume, uint curl)
{
    CloudPoint c;
    c.extinction = 0;
    c.rain = 0;
    c.type = 0;
    c.height = 0;
    c.thickness = 1;
    c.rainRate = 0;
    if (altitude > f[0].maxAltitude || altitude < 0)
    {
        return c;
    }
    float2 plane = rel.xz;
    Weather w = weatherAt(f, plane);
    if (w.coverage <= 1.0e-3)
    {
        return c;
    }
    uint t = typeOf(w.type);
    float base = f[0].types[t].base;
    float top = w.top > base ? w.top : f[0].types[t].top;
    float thickness = max(top - base, 1);
    float h = (altitude - base) / thickness;
    c.type = t;
    c.thickness = thickness;
    c.height = saturate(h);
    if (h < 0)
    {
        // Below the base: the rain falling from it (Marshall-Palmer), to the ground.
        c.rainRate = w.rain * f[0].rainMaxRate;
        c.rain = rainExtinction(c.rainRate) * f[0].rainExtinctionScale;
        return c;
    }
    if (h > 1)
    {
        return c;
    }
    float shear = f[0].types[t].shear;
    if (shear != 0)
    {
        // The upper part leans downwind: the weather that is here came from upwind.
        w = weatherAt(f, plane - f[0].wind * (shear * h * thickness));
        if (w.coverage <= 1.0e-3 || typeOf(w.type) != t)
        {
            return c;
        }
    }
    float2 profile = profileAt(f, t, h);
    if (profile.x <= 0)
    {
        return c;
    }
    float coverage = pow(w.coverage, 1.0 / (1.0 + profile.y)); // the anvil widens the coverage near the top
    float3 sp = f[0].shapeAnchor + float3(rel.x, altitude, rel.z) / f[0].shapePeriod;
    float4 shape = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], sp, 0);
    float low = dot(shape.gba, float3(0.625, 0.25, 0.125));
    float cloud = saturate(remap(remap(shape.r, low - 1, 1, 0, 1), SHAPE_LOW, SHAPE_HIGH, 0, 1));
    cloud = saturate(remap(cloud * profile.x, 1 - coverage, 1, 0, 1)) * coverage;
    if (cloud <= 0)
    {
        return c;
    }
    if (detail)
    {
        float2 cp = f[0].curlAnchor + plane / f[0].curlPeriod;
        float2 bend = (textures[curl].SampleLevel(samplers[LINEAR_REPEAT], cp, 0).rg * 2 - 1)
            * (f[0].curlStrength * (1 - c.height));
        float3 dp = f[0].detailAnchor + float3(rel.x + bend.x, altitude, rel.z + bend.y) / f[0].detailPeriod;
        float3 d = volumes[detailVolume].SampleLevel(samplers[LINEAR_REPEAT], dp, 0).rgb;
        float high = dot(d, float3(0.625, 0.25, 0.125));
        high = lerp(high, 1 - high, saturate(c.height * 4)); // wispy at the base, billowy above
        cloud = saturate(remap(cloud, high * f[0].types[t].erosion * 0.4 * f[0].detailStrength, 1, 0, 1));
    }
    c.extinction = cloud * f[0].types[t].extinction * f[0].densityScale;
    return c;
}

// ---------------------------------------------------------------------------------------------------------- phase

/** Henyey-Greenstein. */
public float henyeyGreenstein(float cosTheta, float g)
{
    float g2 = g * g;
    return (1 - g2) / (4 * PI * pow(max(1 + g2 - 2 * g * cosTheta, 1.0e-6), 1.5));
}

/** Draine 2003: HG generalised by alpha. */
public float draine(float cosTheta, float g, float alpha)
{
    float g2 = g * g;
    return henyeyGreenstein(cosTheta, g) * (1 + alpha * cosTheta * cosTheta) / (1 + alpha * (1 + 2 * g2) / 3);
}

/**
 * The approximate Mie phase of water droplets of diameter `d` micrometres (Jendersie and d'Eon 2023, fitted for
 * 5 to 50 um), its eccentricities scaled by `scale` (Wrenninge's octaves).
 */
public float approximateMie(float cosTheta, float d, float scale)
{
    d = clamp(d, 5.0, 50.0);
    float gHg = exp(-0.0990567 / (d - 1.67154));
    float gD = exp(-2.20679 / (d + 3.91029) - 0.428934);
    float alpha = exp(3.62489 - 8.29288 / (d + 5.52825));
    float wD = exp(-0.599085 / (d - 0.641583) - 0.665888);
    return (1 - wD) * henyeyGreenstein(cosTheta, gHg * scale) + wD * draine(cosTheta, gD * scale, alpha);
}

/** The phase of type `t` (ice: HG with g 0.75; water: the approximate Mie). */
public float cloudPhase(CloudFrame* f, uint t, float cosTheta, float scale)
{
    return f[0].types[t].ice > 0.5 ? henyeyGreenstein(cosTheta, 0.75 * scale)
        : approximateMie(cosTheta, f[0].types[t].droplet, scale);
}

// ------------------------------------------------------------------------------------------------------ atmosphere

/**
 * Schueler 2012's approximation of the Chapman function times the density at height: the optical depth along a ray
 * from height `h` (metres) at cosine `mu` from the zenith, over the scale height H, divided by H and the sea-level
 * coefficient.
 */
float chapman(float radius, float scaleHeight, float h, float mu)
{
    float x = radius / scaleHeight;
    float hh = max(h, 0) / scaleHeight;
    float c = sqrt(x + hh);
    if (mu >= 0)
    {
        return c / (c * mu + 1) * exp(-hh);
    }
    float x0 = sqrt(max(1 - mu * mu, 0)) * (x + hh);
    float c0 = sqrt(x0);
    return 2 * c0 * exp(x - x0) - c / (1 - c * mu) * exp(-hh);
}

/** The sun's transmittance through the atmosphere from altitude `h` (RGB): Rayleigh and Mie. */
public float3 sunTransmittance(CloudFrame* f, float h)
{
    float mu = f[0].sunDirection.y;
    float R = f[0].planetRadius;
    float3 depth = f[0].rayleighScattering * f[0].rayleighHeight * chapman(R, f[0].rayleighHeight, h, mu)
        + f[0].mieExtinction * f[0].mieHeight * chapman(R, f[0].mieHeight, h, mu);
    return exp(-min(depth, 80.0)) * f[0].sunVisible;
}

/** The optical depth of an exponential layer of scale height H between altitudes a0 and a1 over length `d`. */
float layerDepth(float H, float a0, float a1, float d)
{
    float dh = a1 - a0;
    if (abs(dh) < 1.0e-2 * H)
    {
        return d * exp(-0.5 * (a0 + a1) / H);
    }
    return d * H / dh * (exp(-a0 / H) - exp(-a1 / H));
}

/**
 * The aerial perspective over `d` metres from altitude `a0` to `a1` (straight, the planet's curvature left out):
 * transmittance (RGB) and the light scattered in towards the camera, as the sky's horizon radiance times
 * (1 - transmittance). An analytic stand-in for effects-atmosphere's aerial perspective volume.
 */
public float3 aerialTransmittance(CloudFrame* f, float a0, float a1, float d)
{
    float3 depth = f[0].rayleighScattering * layerDepth(f[0].rayleighHeight, max(a0, 0), max(a1, 0), d)
        + f[0].mieExtinction * layerDepth(f[0].mieHeight, max(a0, 0), max(a1, 0), d);
    return exp(-depth);
}

// --------------------------------------------------------------------------------------------------- shadow map

/** The Beer shadow map's texture coordinates and light-space depth of a point `rel` from the anchor. */
public float3 shadowCoordinates(CloudFrame* f, float3 rel)
{
    float3 q = rel - f[0].shadowCentre;
    float2 uv = float2(dot(q, f[0].shadowU), dot(q, f[0].shadowV)) / f[0].shadowExtent + 0.5;
    float depth = f[0].shadowDepth - dot(q, f[0].sunDirection);
    return float3(uv, depth);
}

/**
 * The clouds' transmittance towards the sun at `rel` from the Beer shadow map in sampled slot `map` (front depth,
 * mean extinction behind it, maximum optical depth): exp(-min(B, G max(0, d - R))). 1 outside the map.
 */
public float cloudShadow(CloudFrame* f, uint map, float3 rel)
{
    float3 s = shadowCoordinates(f, rel);
    if (any(s.xy < 0) || any(s.xy > 1))
    {
        return 1;
    }
    float3 v = textures[map].SampleLevel(samplers[LINEAR_CLAMP], s.xy, 0).rgb;
    return exp(-min(v.b, v.g * max(0, s.z - v.r)));
}

/** The clouds' vertical optical depth above `rel` from the Beer shadow map's whole column along the sun. */
public float columnDepth(CloudFrame* f, uint map, float3 rel)
{
    float3 s = shadowCoordinates(f, rel);
    if (any(s.xy < 0) || any(s.xy > 1))
    {
        return 0;
    }
    return textures[map].SampleLevel(samplers[LINEAR_CLAMP], s.xy, 0).b * max(f[0].sunDirection.y, 0.02);
}

// ------------------------------------------------------------------------------------------------------- lighting

/**
 * The diffuse transmittance of a conservatively scattering slab of optical depth `tau`: the two-stream (Eddington)
 * result 1 / (1 + 3/4 (1 - g) tau) (e.g. Bohren 1987, "Multiple scattering of light and some of its observable
 * consequences"): how much of the light falling on a cloud's top still arrives, diffused, at a depth `tau` below it.
 */
public float diffuseTransmittance(float tau)
{
    return 1 / (1 + 0.75 * (1 - CLOUD_G) * max(tau, 0));
}

/** The nearest distance from `p` to the segment from `a` to `b`. */
public float segmentDistance(float3 p, float3 a, float3 b)
{
    float3 ab = b - a;
    float t = saturate(dot(p - a, ab) / max(dot(ab, ab), 1.0e-6));
    return length(p - (a + t * ab));
}

/**
 * The light the frame's flashes scatter at `rel` (from the anchor) in a medium of extinction `sigma`, per unit of
 * scattering (multiply by the scattering coefficient): the fluence of a point source of the flash's intensity at the
 * nearest point of its channel in the diffusion approximation, I e^(-sigma_eff r) / (D r), D = 1 / (3 sigma (1 - g)),
 * sigma_eff = sqrt(3 sigma_a' sigma (1 - g)), sigma_a' the leak; isotropic (diffused): / 4 pi.
 */
public float3 flashLight(CloudFrame* f, float3 rel, float sigma)
{
    uint n = min(f[0].flashCount, MAX_FLASHES);
    float3 sum = 0;
    float reduced = sigma * 0.15; // sigma (1 - g), g about 0.85 for cloud droplets
    if (n == 0 || reduced <= 0)
    {
        return 0;
    }
    float D = 1 / (3 * reduced);
    float effective = sqrt(3 * f[0].flashLeak * reduced);
    for (uint i = 0; i < n; i++)
    {
        float r = max(segmentDistance(rel, f[0].flashes[i].a, f[0].flashes[i].b), f[0].flashes[i].radius);
        sum += f[0].flashes[i].intensity * exp(-effective * r) / (D * r);
    }
    return sum * float3(0.9, 0.95, 1.0) / (4 * PI); // lightning's spectrum is a little blue (Orville 1968)
}
