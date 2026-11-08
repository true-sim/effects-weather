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
// - Storm cells as modelled convective clouds: implicit surfaces (Ebert 1997, "Volumetric modeling with implicit
//   functions: a cloud is born"; Webanck et al. 2018, "Procedural cloudscapes") for the towers, their envelope's
//   signed distance carved by billows and turned into density across a thin edge as Schneider 2022 ("Nubis^3",
//   SIGGRAPH Advances) does with its dimensional profile; the anatomy from the WMO International Cloud Atlas
//   (cumulus humilis to congestus, cumulonimbus calvus and capillatus, incus, mamma) and Houze 2014 ("Cloud
//   Dynamics", ch. 8).
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
/**
 * How far the coverage reaches into the base shape: full cover still leaves the shape's lowest tenth out, so a storm
 * core or an overcast deck keeps its billows and a few thin spots instead of a solid cylinder or slab.
 */
public static const float COVER_REACH = 0.9;
/** A shape volume slot meaning none: cloudAt's density from the coverage and profile alone (the cheap tiers). */
public static const uint NO_NOISE = 0xffffffff;
/** Henyey-Greenstein g of cloud droplets for similarity (diffusion): sigma (1 - g) is the reduced extinction. */
public static const float CLOUD_G = 0.85;
/** The storm cells the frame record holds (field.slang's MAX_CELLS). */
public static const uint MAX_CELLS = 64;
/** A storm cell's towers (StormTowers.TOWERS): the main updraft's, three of the flanking line, two early cumuli. */
public static const uint MAX_TOWERS = 6;

/** One cloud type's layer and microphysics (weather-clouds' CloudProfile, art applied). */
[HostLayout]
public struct CloudType
{
    public float base;          // the layer's base, metres above the ground
    public float top;           // its top (the weather map's top channel may override it)
    public float extinction;    // 1/m at density 1: the physical extinction over the layer's mean density
    public float shear;         // how far its upper part leans downwind, a fraction of its thickness
    public float droplet;       // effective droplet diameter, micrometres (the approximate Mie phase)
    public float absorption;    // single-scattering co-albedo
    public float erosion;       // 0 billowy, 1 wispy
    public float ice;           // 1: ice crystals (Henyey-Greenstein)
    public float column;        // 1/m: the physical extinction, the layer's mean where it covers (from its water)
    public float pad0;
    public float pad1;
    public float pad2;
};

/**
 * A storm cell as the clouds draw it (StormTowers, from weather-model's StormSimulation): towers on one flat base, the
 * first the main updraft's (an overshooting top over it while the updraft is strongest), its tops glaciating, its
 * liquid towers eroding away as it dissipates. Its anvil is the weather maps' (the types map's blue, the cells map's
 * top).
 */
[HostLayout]
public struct StormCell
{
    public float2 core;         // the main updraft's ground point, from the anchor
    public float base;          // the flat base, metres
    public float reach;         // metres from the core within which its towers stand (with their lean and turrets)
    public float glaciation;    // [0, 1]: how far its tops have turned to ice above the glaciation level (calvus)
    public float fibres;        // [0, 1]: how fibrous that ice is (capillatus, as the anvil spreads)
    public float water;         // [0, 1]: what is left of its liquid towers (they erode away as it dissipates)
    public float overshoot;     // metres the overshooting top rises above the main tower's
    public float3 noise;        // its place in the turret noise, tiles (hashed; rising with its updraft)
    public uint towers;         // towers in use
    public float ceiling;       // metres: the tropopause its towers flatten under (the overshooting top rises above)
    public float flare;         // how far its main tower's top widens into the anvil, a fraction of its radius
    public float pad2;
    public float pad3;
    public float4 tower[MAX_TOWERS]; // centre x, z from the anchor, radius, top (metres)
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
    public uint nearMap;            // sampled slots of the weather maps (RGBA16F: coverage, type A, rain, top)
    public uint farMap;
    public uint nearTypes;          // and their second types (RGBA16F: type B, its weight)
    public uint farTypes;
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
    public float anvilTop;          // metres: the highest anvil's top (where far shadows cross the anvils)
    public float anvilThickness;    // metres: an anvil's depth at full cover
    public float anvilExtinction;   // 1/m at density 1 (the ice's physical extinction over the anvil's mean density)
    public float anvilColumn;       // 1/m: the ice's physical extinction, the anvil's mean where it covers
    public float anvilPad;
    public float anvilDiameter;     // micrometres: the ice crystals' effective diameter
    public float rainSlant;         // metres downwind per metre fallen (the wind's shear over the drops' fall speed)
    public float rainCurve;         // more slant per fallen fraction of the base's height (shear growing downwards)
    public float rainEvaporation;   // the fraction of the rain evaporated by the time it reaches the ground
    public float rainTaper;         // how much of the rain's edge evaporates before the ground (the shaft narrows)
    public float billowScale;       // the towers' turrets: times the base shape's frequency (0: none)
    public uint rainAny;            // 1: some rain can fall this frame
    public uint nearCells;          // sampled slots of the cell lists (RGBA16F: three cells' indices + 1, anvils' top)
    public uint farCells;
    public uint cellCount;          // storm cells in `cells`
    public float towerLean;         // metres a storm's towers lean downwind per metre up (the wind's shear)
    public float turretPeriod;      // metres per tile of the turret noise (Worley cells a quarter, eighth, sixteenth)
    public float turrets;           // how far turrets bulge out of a tower, times a fifth of its radius (0: smooth)
    public float glaciationAltitude; // metres: where a cumulonimbus's tops turn to ice (about -35 C)
    public float towerWater;        // 1/m: the towers' liquid water's extinction
    public float towerIce;          // 1/m: their glaciated tops'
    public float mammatus;          // metres the pouches under an anvil hang down at most (0: none)
    public float lodBand;           // the march's level-of-detail switches fade over this fraction of their distance
    public uint nearOccupancy;      // sampled slots of where clouds can be (RGBA16F: base, top; occupancy.slang)
    public uint farOccupancy;
    public uint nearBlock;          // map texels per occupancy texel
    public uint farBlock;
    public float virga;             // mm/h falling from an anvil's base where it covers fully (evaporating: virga)
    public float virgaDepth;        // metres below the anvil's base by which it has evaporated
    public uint debug;              // render.clouds.debug: terms left out (DEBUG_*), for looking at the lighting
    public CloudType types[CLOUD_TYPES];
    public Flash flashes[MAX_FLASHES];
    public StormCell cells[MAX_CELLS];
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

/**
 * The weather at a ground point: coverage, rain and top bilinear, and the two cloud types with most weight there with
 * the second's weight. Types blend: each texel holds a mix of two (a transition's, a growing cell's, a brush's edge),
 * and the four texels around the point add theirs, weighted bilinearly, so the profiles change continuously in space
 * and in time.
 */
public struct Weather
{
    public float coverage;
    public float rain;
    public float top;
    public uint a;
    public uint b;
    public float wb;
    public float anvil;         // the storms' anvils' cover (drawn on their own, over the types)
};

/** The type of a weather map's type channel (CloudType.fromChannel). */
public uint typeOf(float channel)
{
    return uint(round(saturate(channel) * float(CLOUD_TYPES - 1)));
}

/**
 * Adds a map's four texels around texture coordinates `uv` (clamped), weighted by `weight`, into the sums. The type
 * weights are indexed by constants only (unrolled): a dynamically indexed local array lives in scratch memory, which
 * made every density evaluation several times slower.
 */
void gather(uint map, uint types, uint size, float2 uv, float weight, inout float4 sums, inout float typeWeights[9])
{
    float2 at = uv * float(size) - 0.5;
    int2 base = int2(floor(at));
    float2 fraction = at - float2(base);
    for (uint k = 0; k < 4; k++)
    {
        int2 offset = int2(k & 1, k >> 1);
        int2 texel = clamp(base + offset, int2(0, 0), int2(size - 1, size - 1));
        float w = weight * (offset.x == 1 ? fraction.x : 1 - fraction.x) * (offset.y == 1 ? fraction.y : 1 - fraction.y);
        float4 v = textures[map].Load(int3(texel, 0));
        float4 t = textures[types].Load(int3(texel, 0));
        sums += w * float4(t.a, v.b, v.a, t.b); // the coverage without the anvils, rain, top, the anvils
        uint ta = typeOf(v.g);
        uint tb = typeOf(t.x);
        for (uint c = 0; c < CLOUD_TYPES; c++)
        {
            typeWeights[c] += (ta == c ? w * (1 - t.y) : 0) + (tb == c ? w * t.y : 0);
        }
    }
}

/** How much of `p` the box `bounds` (min x, min z, max x, max z) holds: 1 inside, falling to 0 over `band` metres. */
public float within(float2 p, float4 bounds, float band)
{
    float2 inner = min(p - bounds.xy, bounds.zw - p);
    return saturate(min(inner.x, inner.y) / band);
}

/**
 * The weather at plane point `plane` (metres from the anchor): the near map, handing over to the far map across the
 * near map's outer tenth (no seam where one ends), nothing beyond the far map.
 */
public Weather weatherAt(CloudFrame* f, float2 plane)
{
    float4 sums = 0;
    float typeWeights[9] = { 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    float near = within(plane, f[0].nearBounds, 0.1 * f[0].nearExtent);
    float far = (1 - near) * step(0.0, min(min(plane.x - f[0].farBounds.x, plane.y - f[0].farBounds.y),
        min(f[0].farBounds.z - plane.x, f[0].farBounds.w - plane.y)));
    if (near > 0)
    {
        gather(f[0].nearMap, f[0].nearTypes, f[0].nearSize, f[0].nearAnchor + plane / f[0].nearExtent, near, sums,
            typeWeights);
    }
    if (far > 0)
    {
        gather(f[0].farMap, f[0].farTypes, f[0].farSize, f[0].farAnchor + plane / f[0].farExtent, far, sums,
            typeWeights);
    }
    Weather w;
    w.coverage = saturate(sums.x + f[0].coverageOffset);
    w.rain = sums.y;
    w.top = sums.z;
    w.anvil = sums.w;
    // The two heaviest types.
    uint a = 0;
    uint b = 0;
    float wa = -1;
    float wbest = -1;
    for (uint t = 0; t < CLOUD_TYPES; t++)
    {
        float v = typeWeights[t];
        if (v > wa)
        {
            b = a;
            wbest = wa;
            a = t;
            wa = v;
        }
        else if (v > wbest)
        {
            b = t;
            wbest = v;
        }
    }
    w.a = a;
    w.b = b;
    w.wb = wbest > 0 ? wbest / max(wa + wbest, 1.0e-6) : 0;
    return w;
}

// ------------------------------------------------------------------------------------------------------------ density

/** What a density evaluation found: extinction, its types, the height in its layer, the rain below it. */
public struct CloudPoint
{
    public float extinction;    // 1/m, the cloud's
    public float rain;          // 1/m, the rain's (below the layer's base)
    public uint type;           // the heavier type
    public float height;        // in its layer, [0, 1]
    public float thickness;     // its layer's, metres
    public float rainRate;      // mm/h
    public float droplet;       // the droplet diameter (micrometres) of the types' mix, by their extinction
    public float ice;           // 1: ice crystals (the anvil's, cirrus)
    public float columnSigma;   // the types' mean extinction over their layers where they cover: the column's
    public float top;           // the heavier type's top here, metres
    public float absorption;
    public float surface;       // the optical depth to the nearest side of a storm's tower (the sky's light from there)
    public float skyView;       // how much of the sky that side sees: 1 a dome's top, a half a flank, 0 the base
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

/** The layer of type `t` at a place whose top channel is `top`: base, top. */
float2 layerOf(CloudFrame* f, uint t, float top)
{
    float base = f[0].types[t].base;
    return float2(base, top > base + 100 ? top : f[0].types[t].top);
}

/** The convective types (towering cumulus, cumulonimbus): towers of billows. */
static const uint TOWERING = 3;
static const uint CUMULONIMBUS_TYPE = 4;

/**
 * The maps' channels at plane point `plane`, bilinear (the hardware's filter, clamped like gather's texels): the
 * coverage without the anvils, the rain, the top, the anvils' cover. Cheaper than weatherAt (no types): for the shear's
 * look-up upwind and the rain falling from upwind.
 */
public float4 channelsAt(CloudFrame* f, float2 plane)
{
    float near = within(plane, f[0].nearBounds, 0.1 * f[0].nearExtent);
    float far = (1 - near) * step(0.0, min(min(plane.x - f[0].farBounds.x, plane.y - f[0].farBounds.y),
        min(f[0].farBounds.z - plane.x, f[0].farBounds.w - plane.y)));
    float4 sum = 0;
    if (near > 0)
    {
        float2 uv = f[0].nearAnchor + plane / f[0].nearExtent;
        float4 m = textures[f[0].nearMap].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
        float4 t = textures[f[0].nearTypes].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
        sum += near * float4(t.a, m.b, m.a, t.b);
    }
    if (far > 0)
    {
        float2 uv = f[0].farAnchor + plane / f[0].farExtent;
        float4 m = textures[f[0].farMap].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
        float4 t = textures[f[0].farTypes].SampleLevel(samplers[LINEAR_CLAMP], uv, 0);
        sum += far * float4(t.a, m.b, m.a, t.b);
    }
    sum.x = saturate(sum.x + f[0].coverageOffset);
    return sum;
}

// ---------------------------------------------------------------------------------------------------- storm cells

/** The cells whose towers may stand over a plane point (indices + 1; 0: none) and the anvils' top there (0: none). */
public struct CellList
{
    public uint3 ids;
    public float anvilTop;
};

/**
 * The cell list at plane point `plane`: the near map's texel holding it, else the far map's (the lists are discrete:
 * nearest texel; every cell whose towers reach a texel is in its list, so which texel answers changes nothing). The
 * anvils' top is filtered (it is defined around the anvils too, so their edges blend with their own top).
 */
public CellList cellsAt(CloudFrame* f, float2 plane)
{
    CellList l;
    l.ids = 0;
    l.anvilTop = 0;
    if (f[0].cellCount == 0)
    {
        return l;
    }
    float4 nb = f[0].nearBounds;
    float4 fb = f[0].farBounds;
    bool near = all(plane > nb.xy) && all(plane < nb.zw);
    if (!near && !(all(plane > fb.xy) && all(plane < fb.zw)))
    {
        return l;
    }
    uint map = near ? f[0].nearCells : f[0].farCells;
    uint size = near ? f[0].nearSize : f[0].farSize;
    float2 uv = (near ? f[0].nearAnchor : f[0].farAnchor) + plane / (near ? f[0].nearExtent : f[0].farExtent);
    int2 texel = clamp(int2(floor(uv * float(size))), int2(0, 0), int2(size - 1, size - 1));
    float4 v = textures[map].Load(int3(texel, 0));
    l.ids = uint3(round(v.xyz));
    l.anvilTop = textures[map].SampleLevel(samplers[LINEAR_CLAMP], uv, 0).w;
    return l;
}

/**
 * The distance from `q` (from an ellipsoid's centre) to the ellipsoid of radii `r` (r.x = r.z): Quilez's bound
 * k0 (k0 - 1) / k1 (k0 = |q / r|, k1 = |q / r^2|), close near the surface, and at least the bounding cylinder's (the
 * plain (k0 - 1) min(r) is a thousandth of the distance beside a flat ellipsoid); and the surface's upward facing
 * there.
 */
float ellipsoidDistance(float3 q, float3 r, out float up)
{
    float k0 = max(length(q / r), 1.0e-4);
    float3 g = q / (r * r);
    float k1 = max(length(g), 1.0e-9);
    up = g.y / k1;
    // Far beside a flat one the bound falls short (the thin axis dominates k1): the distance is at least the bounding
    // cylinder's (r.x = r.z).
    return max(k0 * (k0 - 1) / k1, max(length(q.xz) - r.x, abs(q.y) - r.y));
}

/**
 * An anvil's depth where the maps' anvil cover is `cover`: thick near the core, a thin sheet to its edges and its far
 * end, nothing where it begins to spread.
 */
public float anvilThick(CloudFrame* f, float cover)
{
    return f[0].anvilThickness * (0.1 + 0.9 * pow(saturate(cover / 0.8), 2)) * smoothstep(0.0, 0.3, cover);
}

/** Quilez's polynomial smooth minimum: a and b blended within k of each other. */
float smoothMin(float a, float b, float k)
{
    float h = max(k - abs(a - b), 0.0) / max(k, 1.0e-3);
    return min(a, b) - h * h * k * 0.25;
}

/**
 * One tower's envelope at point `p` (x, z from the anchor, y the altitude): metres to its surface (negative inside)
 * and the surface's upward facing there (`up`: 1 a dome's top, 0 a flank, -1 the base). A column standing on the flat
 * base (a little narrower at the base), leaning downwind with the shear, under a dome as high as the tower allows: a
 * young cumulus is a lens flatter than it is wide (humilis), a towering one round-topped (congestus).
 */
float towerDistance(float4 tw, float base, float2 lean, float flare, float3 p, out float up)
{
    float height = tw.w - base;
    float radius = tw.z * (1 + flare * smoothstep(tw.w - 0.45 * height, tw.w, p.y));
    up = 0;
    if (height < 1 || radius < 1)
    {
        return 1.0e9;
    }
    float2 axis = tw.xy + lean * max(p.y - base, 0.0);
    float rho = length(p.xz - axis);
    float cap = min(1.1 * tw.z, height);
    float capBase = tw.w - cap;
    float d;
    if (p.y < capBase)
    {
        float h = saturate((p.y - base) / max(capBase - base, 1.0));
        d = rho - radius * (0.72 + 0.28 * smoothstep(0.0, 0.55, h));
    }
    else
    {
        d = ellipsoidDistance(float3(rho, p.y - capBase, 0), float3(radius, cap, radius), up);
    }
    float below = base - p.y;
    if (below > d)
    {
        d = below;
        up = -1;
    }
    return d;
}

/**
 * The listed cells' towers at a point: the union's distance, and every value the density reads blended as the union
 * blends the towers (where two towers merge their turrets, tops and cells' states mix over the merge instead of
 * switching where one becomes the nearer: no step in space, and none in time as the towers grow past each other).
 */
public struct Towers
{
    public float distance;      // metres, negative inside
    public float up;            // the surface's upward facing there
    public float amplitude;     // metres the turrets bulge out at most: a fifth of the tower's size (its radius, or its
                                // height while it is lower), times render.clouds.storm.turrets
    public float top;           // its top, metres
    public float ceiling;       // the tropopause over it (its overshoot added over the main updraft)
    public float water;         // its cell's liquid left
    public float glaciation;    // its cell's glaciation
    public float fibres;        // its cell's fibrous ice
    public uint cell;           // the nearest tower's cell (its turret noise)
};

/** Adds `v` to the blended value `a`: weight `w` of v (the union's blend at this tower). */
float blendIn(float a, float v, float w)
{
    return lerp(a, v, w);
}

/**
 * The listed cells' towers at point `p` (x, z from the anchor, y the altitude): their envelopes' smooth union (towers
 * merge where they meet), the main tower's overshooting top as a narrower column rising above it.
 */
public Towers towersAt(CloudFrame* f, CellList list, float3 p)
{
    Towers t;
    t.distance = 1.0e9;
    t.up = 0;
    t.amplitude = 0;
    t.top = 0;
    t.ceiling = 0;
    t.water = 0;
    t.glaciation = 0;
    t.fibres = 0;
    t.cell = 0;
    float nearest = 1.0e9;
    for (uint i = 0; i < 3; i++)
    {
        uint id = i == 0 ? list.ids.x : i == 1 ? list.ids.y : list.ids.z;
        if (id == 0 || id > min(f[0].cellCount, MAX_CELLS))
        {
            continue;
        }
        uint cell = id - 1;
        float2 core = f[0].cells[cell].core;
        if (length(p.xz - core) > f[0].cells[cell].reach)
        {
            continue;
        }
        float base = f[0].cells[cell].base;
        float2 lean = f[0].wind * f[0].towerLean;
        uint n = min(f[0].cells[cell].towers, MAX_TOWERS);
        for (uint k = 0; k <= n; k++)
        {
            float4 tw;
            bool overshoot = k == n;
            if (!overshoot)
            {
                tw = f[0].cells[cell].tower[k];
            }
            else
            {
                // The overshooting top: a dome over the main updraft rising above the tropopause, 0.4 of the tower's
                // width (Houze 2014, 8.4: a few kilometres across, one or two high, for minutes).
                tw = f[0].cells[cell].tower[0];
                if (f[0].cells[cell].overshoot < 1)
                {
                    continue;
                }
                tw.z *= 0.4;
                tw.w = f[0].cells[cell].ceiling + f[0].cells[cell].overshoot;
            }
            float height = tw.w - base;
            if (tw.z < 1 || height < 1 || p.y > tw.w + 0.3 * tw.z + 1500)
            {
                continue;
            }
            // Too far to change the union or the density (a bound on its distance: its widest radius, its lean and
            // its turrets): skipped.
            float2 axisAt = tw.xy + lean * max(p.y - base, 0.0);
            float bound = length(p.xz - axisAt) - tw.z * (1.4 + f[0].cells[cell].flare);
            if (bound > max(t.distance, 0.0) + 0.3 * tw.z && bound > 1.2 * f[0].turrets * (0.2 * tw.z + 150) + 400)
            {
                continue;
            }
            float up;
            float d;
            if (overshoot)
            {
                // An ellipsoid centred on the tropopause over the leaning axis: its upper half shows.
                float2 axis = tw.xy + lean * max(p.y - base, 0.0);
                d = ellipsoidDistance(float3(p.x - axis.x, p.y - f[0].cells[cell].ceiling, p.z - axis.y),
                    float3(tw.z, f[0].cells[cell].overshoot, tw.z), up);
            }
            else
            {
                d = towerDistance(tw, base, lean, k == 0 ? f[0].cells[cell].flare : 0, p, up);
            }
            // Merging over a third of its size (a vanishing tower's blend vanishes with it: no plate left behind).
            float blend = 0.3 * (overshoot ? min(tw.z, f[0].cells[cell].overshoot) : min(tw.z, height));
            // This tower's share where it merges with the union so far (0 far outside it, 1 well inside).
            float w = t.distance >= 1.0e8 ? 1.0 : saturate(0.5 + 0.5 * (t.distance - d) / max(blend, 1.0));
            float size = overshoot ? min(tw.z, f[0].cells[cell].overshoot) : min(tw.z, 0.8 * height);
            t.up = blendIn(t.up, up, w);
            t.amplitude = blendIn(t.amplitude, f[0].turrets * (0.2 * size + 150 * saturate(height / 1500)), w);
            t.top = blendIn(t.top, tw.w, w);
            // The tropopause flattens every top but the overshooting one's (a dome above it).
            t.ceiling = blendIn(t.ceiling, overshoot ? tw.w + tw.z : f[0].cells[cell].ceiling, w);
            t.water = blendIn(t.water, f[0].cells[cell].water, w);
            t.glaciation = blendIn(t.glaciation, f[0].cells[cell].glaciation, w);
            t.fibres = blendIn(t.fibres, f[0].cells[cell].fibres, w);
            t.distance = smoothMin(t.distance, d, blend);
            if (d < nearest)
            {
                nearest = d;
                t.cell = cell;
            }
        }
    }
    return t;
}

/** The optical depth along the ray from `rel` towards `L` of cell `cell`'s towers (analytic columns). */
float towersDepth(CloudFrame* f, uint cell, float3 rel, float3 L)
{
    float depth = 0;
    float base = f[0].cells[cell].base;
    float sigma = lerp(f[0].towerWater, f[0].towerIce, 0.5 * f[0].cells[cell].glaciation)
        * f[0].cells[cell].water * f[0].densityScale;
    uint n = min(f[0].cells[cell].towers, MAX_TOWERS);
    for (uint k = 0; k < n; k++)
    {
        float4 tw = f[0].cells[cell].tower[k];
        float r = 0.9 * tw.z;
        float top = tw.w - 0.45 * min(tw.z, tw.w - base);
        if (r < 1 || top <= max(rel.y, base))
        {
            continue;
        }
        // The ray's span inside the vertical cylinder, then inside the slab between the base and the top.
        float2 o = rel.xz - tw.xy;
        float2 dxz = L.xz;
        float a = dot(dxz, dxz);
        float b = dot(o, dxz);
        float c = dot(o, o) - r * r;
        float t0 = 0;
        float t1 = 3.4e38;
        if (a > 1.0e-8)
        {
            float disc = b * b - a * c;
            if (disc <= 0)
            {
                continue;
            }
            float q = sqrt(disc);
            t0 = (-b - q) / a;
            t1 = (-b + q) / a;
        }
        else if (c > 0)
        {
            continue;
        }
        t0 = max(t0, max((base - rel.y) / L.y, 0.0));
        t1 = min(t1, (top - rel.y) / L.y);
        depth += max(t1 - t0, 0.0) * sigma;
    }
    return depth;
}

/**
 * The optical depth of the storm cells along the ray from `rel` towards `L` (unit, upwards): the towers of the cells
 * listed where it starts and where it crosses 4 km (a tower's shadow reaches beyond its own cells' lists) as columns
 * (their radius, from the base to their dome's middle) at their water's or ice's extinction, and the anvil where the
 * ray crosses its middle, as a slab of its cover's thickness. Analytic: for the light beyond the cone march and the
 * Beer shadow map (storms tens of kilometres away), where a tower's real shape is all that counts.
 */
public float stormDepth(CloudFrame* f, float3 rel, float3 L)
{
    if (f[0].cellCount == 0 || L.y <= 0.02)
    {
        return 0;
    }
    CellList here = cellsAt(f, rel.xz);
    CellList up = rel.y < 4000 ? cellsAt(f, rel.xz + L.xz * ((4000 - rel.y) / L.y)) : here;
    float depth = 0;
    for (uint i = 0; i < 6; i++)
    {
        uint3 ids = i < 3 ? here.ids : up.ids;
        uint j = i % 3;
        uint id = j == 0 ? ids.x : j == 1 ? ids.y : ids.z;
        if (id == 0 || id > min(f[0].cellCount, MAX_CELLS) || (i >= 3 && any(here.ids == id)))
        {
            continue;
        }
        depth += towersDepth(f, id - 1, rel, L);
    }
    // The anvil: the rest of its sheet along the ray, from where the ray is (inside it) or crosses its middle.
    float middle = f[0].anvilTop - 0.5 * f[0].anvilThickness;
    if (f[0].anvilTop > 100 && rel.y < f[0].anvilTop)
    {
        float2 q = rel.y < middle ? rel.xz + L.xz * ((middle - rel.y) / L.y) : rel.xz;
        float cover = channelsAt(f, q).w;
        float thick = anvilThick(f, cover);
        float top = cellsAt(f, q).anvilTop;
        float from = max(rel.y, top - thick);
        depth += cover * f[0].anvilColumn * f[0].densityScale * max(top - from, 0.0) / L.y;
    }
    return depth;
}

/**
 * The clouds and rain at a point `rel` from the anchor (altitude `altitude` above the ground): Schneider's density per
 * type of the place's mix (the base shape remapped by the coverage widened by the anvil, eroded by the detail noise
 * by `detail` (a weight in [0, 1]: the march fades it out with the distance), distorted by curl), times the type's
 * physical extinction, blended by the types' weights; the storm cells' towers (stormAt); the storms' anvils (ice, their
 * own extinction and phase) over them; the rain below the bases. The noise is sampled once for all types.
 * `shapeVolume` NO_NOISE: the cheap tiers' density of the coverage and profile alone.
 *
 * Towering cumulus and cumulonimbus brushed onto the maps (not a storm cell) are towers of billows: their shape also
 * reads the base shape at `billowScale` times its frequency, their coverage narrows towards the top, and their base
 * stays flat (the profile's short base ramp).
 */
public CloudPoint cloudAt(CloudFrame* f, float3 rel, float altitude, float detail, uint shapeVolume,
    uint detailVolume, uint curl)
{
    CloudPoint c;
    c.extinction = 0;
    c.rain = 0;
    c.type = 0;
    c.height = 0;
    c.thickness = 1;
    c.rainRate = 0;
    c.droplet = 20;
    c.ice = 0;
    c.columnSigma = 0;
    c.top = 0;
    c.absorption = 0;
    c.surface = 1.0e9;
    c.skyView = 1;
    if (altitude > f[0].maxAltitude || altitude < 0)
    {
        return c;
    }
    float2 plane = rel.xz;
    Weather w = weatherAt(f, plane);
    CellList cells = cellsAt(f, plane);
    bool storm = cells.ids.x != 0;
    if (w.coverage <= 1.0e-3 && w.anvil <= 1.0e-3 && !storm)
    {
        return c;
    }
    uint types[2] = { w.a, w.b };
    float weights[2] = { 1 - w.wb, w.wb };
    float2 layer = layerOf(f, w.a, w.top);
    float2 other = layerOf(f, w.b, w.top);
    // The layer the lighting sees: the types' blended by their weights (continuous as the mix changes).
    float2 mixed = lerp(layer, other, w.wb);
    c.type = w.a;
    c.thickness = max(mixed.y - mixed.x, 1);
    float h = (altitude - mixed.x) / c.thickness;
    c.height = saturate(h);
    c.top = mixed.y;
    float iceWeight = lerp(f[0].types[w.a].ice, f[0].types[w.b].ice, w.wb);
    c.absorption = lerp(f[0].types[w.a].absorption, f[0].types[w.b].absorption, w.wb);
    float convective = (w.a == TOWERING || w.a == CUMULONIMBUS_TYPE ? weights[0] : 0)
        + (w.b == TOWERING || w.b == CUMULONIMBUS_TYPE ? weights[1] : 0);
    // Below the base: the rain falling from them, to the ground (Marshall-Palmer). Drops fall at a few metres a
    // second through the wind's shear: the rain here left the base upwind (the shaft slants and curves downwind), it
    // evaporates on the way down in the drier air below (the shaft thins and narrows towards the ground), and falls in
    // curtains (the base shape at the base's height, carried with the rain).
    // A storm's rain falls from its own base.
    float rainBase = storm ? f[0].cells[cells.ids.x - 1].base : mixed.x;
    if (altitude < rainBase)
    {
        if (f[0].rainAny != 0)
        {
            float below = rainBase - altitude;
            float drift = below * (f[0].rainSlant + f[0].rainCurve * below / max(rainBase, 1));
            float2 from = plane - f[0].wind * drift;
            float rain = f[0].rainSlant > 0 ? channelsAt(f, from).y : w.rain;
            float fallen = saturate(below / max(rainBase, 1));
            // Narrower towards the ground: the edges (light rain) evaporate first.
            float taper = f[0].rainTaper * fallen;
            rain = saturate((rain - taper) / max(1 - taper, 0.05)) * (1 - f[0].rainEvaporation * fallen);
            if (rain > 0 && shapeVolume != NO_NOISE)
            {
                // Curtains: the Worley octaves at a few hundred metres to a kilometre, the same all the way down
                // (carried with the rain), sharper than the shape.
                float3 sp = f[0].shapeAnchor + float3(from.x, rainBase, from.y) / (0.6 * f[0].shapePeriod);
                float curtains = dot(volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], sp, 0).gb,
                    float2(0.6, 0.4));
                rain *= saturate(2.2 * curtains - 0.5);
            }
            c.rainRate = rain * f[0].rainMaxRate;
            c.rain = rainExtinction(c.rainRate) * f[0].rainExtinctionScale;
        }
        // An anvil overhead does not reach below the base of the cloud under it; a layer whose base is below the
        // storm's goes on.
        if (altitude < mixed.x)
        {
            return c;
        }
    }
    float shear = weights[0] * f[0].types[w.a].shear + weights[1] * f[0].types[w.b].shear;
    float coverage = w.coverage;
    if (shear != 0 && h > 0 && h <= 1)
    {
        // The upper part leans downwind: the cover that is here came from upwind.
        coverage = channelsAt(f, plane - f[0].wind * (shear * saturate(h) * c.thickness)).x;
    }
    float shapeN = 1;
    float high = 0;
    float billow = 1;
    if (shapeVolume != NO_NOISE)
    {
        float3 sp = f[0].shapeAnchor + float3(rel.x, altitude, rel.z) / f[0].shapePeriod;
        float4 shape = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], sp, 0);
        float low = dot(shape.gba, float3(0.625, 0.25, 0.125));
        shapeN = saturate(remap(remap(shape.r, low - 1, 1, 0, 1), SHAPE_LOW, SHAPE_HIGH, 0, 1));
        if (convective > 0 && f[0].billowScale > 0)
        {
            // Turrets: the shape's Worley billows at a finer scale, stretched upwards a little (towers rise).
            float3 bp = sp * float3(f[0].billowScale, 0.7 * f[0].billowScale, f[0].billowScale) + 0.37;
            float4 b = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], bp, 0);
            billow = saturate(remap(dot(b.gba, float3(0.625, 0.25, 0.125)), 0.25, 0.85, 0, 1));
        }
        if (detail > 0)
        {
            float2 cp = f[0].curlAnchor + plane / f[0].curlPeriod;
            float2 bend = (textures[curl].SampleLevel(samplers[LINEAR_REPEAT], cp, 0).rg * 2 - 1)
                * (f[0].curlStrength * (1 - c.height));
            float3 dp = f[0].detailAnchor + float3(rel.x + bend.x, altitude, rel.z + bend.y) / f[0].detailPeriod;
            float3 d = volumes[detailVolume].SampleLevel(samplers[LINEAR_REPEAT], dp, 0).rgb;
            high = dot(d, float3(0.625, 0.25, 0.125));
            high = lerp(high, 1 - high, saturate(c.height * 4)); // wispy at the base, billowy above
        }
    }
    float droplets = 0;
    float extinctionSum = 0;
    float iceExtinction = 0;
    float waterSum = 0;
    for (uint k = 0; k < 2; k++)
    {
        if (weights[k] <= 0 || coverage <= 1.0e-3)
        {
            continue;
        }
        uint t = types[k];
        float2 l = k == 0 ? layer : other;
        float thickness = max(l.y - l.x, 1);
        float ht = (altitude - l.x) / thickness;
        if (ht < 0 || ht > 1)
        {
            continue;
        }
        float2 profile = profileAt(f, t, ht);
        float sigma = f[0].types[t].extinction * f[0].densityScale;
        c.columnSigma += weights[k] * coverage * f[0].types[t].column * f[0].densityScale;
        if (profile.x <= 0)
        {
            continue;
        }
        bool tower = t == TOWERING || t == CUMULONIMBUS_TYPE;
        // Each type covers its weight's share of the coverage: a type leaving breaks up and shrinks, as cloud fields
        // do, instead of thinning into a veil (a thick layer at a small weight would still be opaque).
        float share = coverage * weights[k];
        if (tower)
        {
            // A tower fills its core's disc (its cover rises steeply inside it), and narrows over its top fifth
            // into a rounded crown.
            share = sqrt(share) * (1 - 0.45 * smoothstep(0.75, 1.0, ht));
        }
        float cover = pow(share, 1.0 / (1.0 + profile.y)); // the anvil widens the coverage at the top
        // Turrets carve the tower's flanks, where its cover falls off; its core stays solid.
        float shaped = tower && shapeVolume != NO_NOISE ? shapeN * lerp(1.0, lerp(0.35, 1.0, billow),
            saturate(1.5 * (1 - cover))) : shapeN;
        float cloud = shapeVolume == NO_NOISE ? cover * profile.x
            : saturate(remap(shaped * profile.x, 1 - COVER_REACH * cover, 1, 0, 1)) * cover;
        if (detail > 0 && cloud > 0)
        {
            cloud = saturate(remap(cloud, high * f[0].types[t].erosion * 0.4 * f[0].detailStrength * detail, 1, 0,
                1));
        }
        float e = cloud * sigma;
        c.extinction += e;
        droplets += e * (1 - f[0].types[t].ice) * f[0].types[t].droplet;
        waterSum += e * (1 - f[0].types[t].ice);
        extinctionSum += e;
        iceExtinction += e * f[0].types[t].ice;
    }
    // The storm cells' towers: their envelopes (towersAt) carved by turrets, billows of three sizes in the cell's own
    // frame (they move with it and rise with its updraft: inverted Worley, rounded where they bulge out, creased
    // between), largest on the domes; turned into density across an edge that is thin where the surface faces up (the
    // crisp, sunlit cauliflower of a towering cumulus) and wider on the flanks and below. Above the glaciation level
    // the tops turn to ice as the cell matures: the turrets flatten and the edge softens (calvus), then the ice is
    // striated along the shear (capillatus). As the cell dissipates its liquid towers erode into fragments.
    if (storm)
    {
        float3 at = float3(rel.x, altitude, rel.z);
        Towers t = towersAt(f, cells, at);
        float amplitude = t.amplitude;
        if (t.distance < 1.2 * amplitude + 400)
        {
            uint cell = t.cell;
            float base = f[0].cells[cell].base;
            float ice = t.glaciation * smoothstep(f[0].glaciationAltitude - 1000, f[0].glaciationAltitude + 1500,
                altitude);
            float water = t.water;
            float d = t.distance;
            float3 cp = float3(rel.x - f[0].cells[cell].core.x, altitude - base, rel.z - f[0].cells[cell].core.y);
            float lobes = 0.5;
            if (shapeVolume != NO_NOISE && f[0].turretPeriod > 0)
            {
                float3 bp = f[0].cells[cell].noise + cp / f[0].turretPeriod;
                float3 n = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], bp, 0).gba;
                lobes = dot(n, float3(0.6, 0.3, 0.1));
                // Domes bulge most, flanks less, the base not at all (it stays flat).
                float facing = t.up >= 0 ? lerp(0.65, 1.0, t.up) : 1 + t.up;
                // Rounded bulges out of the envelope, shallow creases between them (not holes through it).
                d -= amplitude * (1 - 0.75 * ice) * facing * clamp(3 * (lobes - 0.45), -0.35, 1.2);
            }
            // The tropopause flattens the tops (the anvil spreads under it); only the overshooting top rises above it.
            d = -smoothMin(-d, t.ceiling - altitude, 300);
            // Dissipating: the liquid towers erode away from outside in, into the billows' fragments.
            d += (1 - water) * (1 - 0.6 * ice) * (7.5 * amplitude / max(f[0].turrets, 0.2) + 1000);
            float edge = t.up >= 0 ? lerp(220.0, 50.0, saturate(t.up)) : lerp(220.0, 80.0, saturate(-t.up));
            edge = lerp(edge, 450.0, ice);
            float cloud = saturate(-d / edge);
            if (cloud > 0 && ice > 0.01 && shapeVolume != NO_NOISE)
            {
                // Fibrous ice: the noise stretched along the shear and upwards (striations).
                float along = dot(cp.xz, f[0].wind);
                float across = cp.x * f[0].wind.y - cp.z * f[0].wind.x;
                float3 fp = f[0].cells[cell].noise + float3(along / 6, cp.y / 2.5, across) * (3 / f[0].turretPeriod);
                float fibre = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], fp, 0).b;
                cloud *= lerp(1.0, saturate(0.15 + 1.5 * fibre), ice * t.fibres);
            }
            if (detail > 0 && cloud > 0)
            {
                // The smallest cauliflower: the detail noise at the edge, less on ice.
                cloud = saturate(remap(cloud, high * 0.25 * 0.4 * f[0].detailStrength * detail * (1 - 0.6 * ice), 1,
                    0, 1));
            }
            float sigma = lerp(f[0].towerWater * max(water, 0.2), f[0].towerIce, ice) * f[0].densityScale;
            float e = cloud * sigma;
            if (e > 0)
            {
                float before = c.extinction;
                c.extinction += e;
                droplets += e * (1 - ice) * f[0].types[CUMULONIMBUS_TYPE].droplet;
                waterSum += e * (1 - ice);
                extinctionSum += e;
                iceExtinction += e * ice;
                if (e >= before)
                {
                    // The tower's own column for the lighting: from its base to its top, at its extinction.
                    c.type = CUMULONIMBUS_TYPE;
                    c.thickness = max(t.top - base, 1);
                    c.height = saturate((altitude - base) / c.thickness);
                    c.top = t.top;
                    c.columnSigma = sigma;
                    c.absorption = f[0].types[CUMULONIMBUS_TYPE].absorption;
                }
                // How deep inside it is from its nearest side, and how much sky that side sees: the sky's light
                // comes in from there (a flat base sees the ground, not the sky).
                if (max(-d, 0.0) * sigma < c.surface)
                {
                    c.surface = max(-d, 0.0) * sigma;
                    c.skyView = 0.5 + 0.5 * t.up;
                }
            }
        }
    }
    // The anvil: ice spreading under the tropopause from a storm's top (a cumulonimbus incus), its cover the maps'
    // plume (sharp-edged, thinning downwind), its top the cells' (the maps' cells' top): thick near the core, a thin
    // sheet to its edges, a flat top cut off sharply, a smooth underside with pouches hanging from it downwind
    // (mamma), its ice fibrous along the shear. Optical depths of tens (ice water content of tenths of a gram per
    // cubic metre, crystals of tens of micrometres), not a cirrus veil.
    if (w.anvil > 1.0e-3 && f[0].anvilThickness > 0 && cells.anvilTop > 100)
    {
        float cover = w.anvil;
        // Thick near the core, a thin sheet to its edges and its far end, nothing where it begins to spread.
        float thick = anvilThick(f, cover);
        float top = cells.anvilTop;
        float sag = 0;
        float fibre = 0.5;
        float along = dot(plane, f[0].wind);
        float across = plane.x * f[0].wind.y - plane.y * f[0].wind.x;
        if (shapeVolume != NO_NOISE)
        {
            if (f[0].mammatus > 0)
            {
                // Pouches under the thinner, older part downwind: rounded lobes of the Worley noise hanging down.
                float pouch = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], f[0].shapeAnchor
                    + float3(plane.x, 0, plane.y) * (1.6 / f[0].shapePeriod), 0).g;
                sag = f[0].mammatus * smoothstep(0.1, 0.3, cover) * (1 - smoothstep(0.55, 0.85, cover))
                    * saturate((pouch - 0.45) / 0.4);
            }
        }
        float base = top - thick - sag;
        if (altitude <= base && f[0].virga > 0 && altitude > base - f[0].virgaDepth && f[0].rainAny != 0)
        {
            // Precipitation from the anvil's ice, evaporating in the drier air below it within virgaDepth: virga,
            // trailing in streaks (the curtains' noise, stretched down).
            float fallen = (base - altitude) / f[0].virgaDepth;
            float streaks = 1;
            if (shapeVolume != NO_NOISE)
            {
                float3 vp = f[0].shapeAnchor + float3(plane.x, base, plane.y) / (0.35 * f[0].shapePeriod);
                streaks = saturate(3 * volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], vp, 0).r - 1.4);
            }
            float rate = f[0].virga * saturate(cover / 0.5) * streaks * (1 - fallen) * (1 - fallen);
            c.rainRate = max(c.rainRate, rate);
            c.rain += rainExtinction(rate) * f[0].rainExtinctionScale;
        }
        if (altitude > base && altitude < top)
        {
            float ha = (altitude - base) / (top - base);
            if (shapeVolume != NO_NOISE)
            {
                // Fibres along the wind, a little up and down.
                float3 ap = f[0].shapeAnchor + float3(along / 10, altitude / 2, across) * (4 / f[0].shapePeriod);
                fibre = volumes[shapeVolume].SampleLevel(samplers[LINEAR_REPEAT], ap, 0).g;
            }
            float profile = smoothstep(0.0, 0.12, ha) * (1 - smoothstep(0.92, 1.0, ha));
            // Sharp edges, feathered along the wind by the fibres.
            float sheet = saturate((cover * (0.8 + 0.4 * fibre) - 0.05) / 0.1);
            float cloud = profile * sheet * lerp(0.65, 1.0, fibre);
            if (detail > 0 && cloud > 0)
            {
                cloud = saturate(remap(cloud, high * 0.3 * 0.4 * f[0].detailStrength * detail, 1, 0, 1));
            }
            float e = cloud * f[0].anvilExtinction * f[0].densityScale;
            c.extinction += e;
            extinctionSum += e;
            iceExtinction += e;
            c.columnSigma = max(c.columnSigma, cover * f[0].anvilColumn * f[0].densityScale);
            if (e > 0 && e > 0.5 * c.extinction)
            {
                // The anvil's own layer for the lighting.
                c.height = ha;
                c.thickness = top - base;
                c.top = top;
            }
        }
    }
    c.ice = extinctionSum > 0 ? iceExtinction / extinctionSum : iceWeight;
    c.droplet = waterSum > 0 ? droplets / waterSum : f[0].types[w.a].droplet;
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

/**
 * The phase of a cloud point: its ice's (HG with g 0.75, Baum et al. 2005's ice crystals) and its droplets' (the
 * approximate Mie) by their shares of its extinction.
 */
public float pointPhase(CloudPoint c, float cosTheta, float scale)
{
    float ice = c.ice > 0 ? henyeyGreenstein(cosTheta, 0.75 * scale) : 0;
    float water = c.ice < 1 ? approximateMie(cosTheta, c.droplet, scale) : 0;
    return lerp(water, ice, saturate(c.ice));
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

/** The typical vertical optical depth of a low cloud layer where it covers (beyond the Beer shadow map). */
static const float FAR_COLUMN = 30;

/**
 * How much the Beer shadow map holds `s` (its texture coordinates): 1 inside, falling to 0 over its outer tenth, so
 * what lies beyond it hands over to the far estimates without a seam as the map moves with the camera.
 */
float shadowMapWeight(float3 s)
{
    float2 inner = min(s.xy, 1 - s.xy);
    return saturate(min(inner.x, inner.y) / 0.1);
}

/**
 * Beyond the Beer shadow map, the optical depth towards the sun (`L`) from `rel`: the storms' towers and anvils along
 * the ray (stormDepth: their shapes, analytic), and the layer clouds' coverage where the ray crosses 2 km as the
 * fraction of the sun they hide (the coverage projection, design section 7.4: clouds are opaque to the direct beam).
 */
float farShadowDepth(CloudFrame* f, float3 rel, float3 L)
{
    if (L.y <= 0.02)
    {
        return 0;
    }
    float depth = stormDepth(f, rel, L);
    if (rel.y < 2000)
    {
        float2 q = rel.xz + L.xz * ((2000 - rel.y) / L.y);
        depth += -log(max(1 - channelsAt(f, q).x, 1.0e-4));
    }
    return depth;
}

/**
 * The clouds' optical depth towards the sun from `rel`: from the Beer shadow map (front depth, mean extinction
 * behind it, maximum optical depth: min(B, G max(0, d - R))); beyond it, farShadowDepth; blended across the map's
 * outer tenth.
 */
public float shadowDepth(CloudFrame* f, uint map, float3 rel)
{
    float3 s = shadowCoordinates(f, rel);
    float inside = shadowMapWeight(s);
    float depth = 0;
    if (inside > 0)
    {
        float3 v = textures[map].SampleLevel(samplers[LINEAR_CLAMP], s.xy, 0).rgb;
        depth = min(v.b, v.g * max(0, s.z - v.r));
    }
    if (inside < 1)
    {
        depth = lerp(farShadowDepth(f, rel, f[0].sunDirection), depth, inside);
    }
    return depth;
}

/** The clouds' transmittance towards the sun at `rel` (shadowDepth's). */
public float cloudShadow(CloudFrame* f, uint map, float3 rel)
{
    return exp(-min(shadowDepth(f, map, rel), 80.0));
}

/**
 * The clouds' vertical optical depth above `rel`: from the Beer shadow map's whole column along the sun; beyond it,
 * the storms' towers and anvils straight up and the layer clouds' coverage times a typical low cloud's column;
 * blended across the map's outer tenth.
 */
public float columnDepth(CloudFrame* f, uint map, float3 rel)
{
    float3 s = shadowCoordinates(f, rel);
    float inside = shadowMapWeight(s);
    float depth = 0;
    if (inside > 0)
    {
        depth = textures[map].SampleLevel(samplers[LINEAR_CLAMP], s.xy, 0).b * max(f[0].sunDirection.y, 0.02);
    }
    if (inside < 1)
    {
        float far = stormDepth(f, rel, float3(0, 1, 0)) + channelsAt(f, rel.xz).x * FAR_COLUMN;
        depth = lerp(far, depth, inside);
    }
    return depth;
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
