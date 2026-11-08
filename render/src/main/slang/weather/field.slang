// The weather maps on the GPU (WeatherGpu): weather-model's WeatherField evaluated at every texel, every frame, from
// its small inputs (the preset in effect, the wind's advection offset, the storm cells, the brushes), as its class
// documentation defines it: the preset's Perlin fbm coverage, then the cells' cores and anvils (the strongest's type),
// then the brushes in order. Every texel is the weather at the frame's time, so nothing moving (storm cells, the wind's
// drift, brushes) shows the seams of a map refreshed tile by tile. The maps are centred on the frame's anchor (snapped
// to their texels, so texels keep their place on the ground): texel (i, j) holds the plane point
// anchor + ((i, j) + 0.5 - size / 2) * extent / size. The CPU's maps (WeatherGrid) stay the gameplay's.
//
// The coverage channel is the field's (the anvils' discs in it, as gameplay samples them); the renderer reads the
// cover of everything but the anvils from the second map's alpha and draws the anvils from its blue: each cell's anvil
// as a plume of ice leaving its core downwind, widening and thinning with the distance (a cumulonimbus incus), not a
// disc of the core's cloud type.
import bindless;
import weather.noise;

static const uint MAX_CELLS = 64;
static const uint MAX_BRUSHES = 32;

/** A storm cell: its core and its anvil. Positions from the frame's anchor, on the ground plane (x, z). */
[HostLayout]
struct FieldCell
{
    float2 core;
    float coreRadius;
    float coverage;
    float precipitation;
    float type;          // the type channel's value of the cell's growing type (towering cumulus)
    float top;
    float anvil;         // its anvil's growth [0, 1] (0: none)
    float2 anvilCentre;
    float anvilRadius;   // the anvil's full radius times its growth
    float mature;        // how far it has become its mature type (cumulonimbus), [0, 1], continuous in time
    float matureType;
};

/** A brush: where, how (WeatherBrush.Mode: 0 add, 1 set, 2 clear) and what. */
[HostLayout]
struct FieldBrush
{
    float2 centre;
    float radius;
    float falloff;
    float strength;
    float coverage;
    float precipitation;
    float type;
    float top;
    uint mode;
    float pad0;
    float pad1;
};

[HostLayout]
struct FieldInputs
{
    float coverage;      // the preset in effect (WeatherState)
    float variation;
    float precipitation;
    float type;          // the transition's from type
    float top;
    float noiseScale;
    int octaves;
    int seed;
    float3 noiseOrigin;  // (anchor - offset) / noiseScale, wrapped to the noise's period, per axis (y: the ground's)
    uint cells;
    uint brushes;
    float toType;        // the transition's to type
    float toWeight;      // smoothstep of its progress: the weight of the to type
    float pad2;
    FieldCell cell[MAX_CELLS];
    FieldBrush brush[MAX_BRUSHES];
};

struct FieldPush
{
    FieldInputs* inputs;
    uint map;            // storage slots, RGBA16F: coverage, type A, rain, top; type B, its weight, anvil, the
                         // coverage without the anvils
    uint types;
    uint size;           // texels per edge
    float extent;        // metres the map spans
    float2 centre;       // the map's centre from the anchor, metres
    int period;          // the noise's period, lattice cells
};

[[vk::push_constant]]
ConstantBuffer<FieldPush> push;

float smoothOf(float x)
{
    float t = saturate(x);
    return t * t * (3 - 2 * t);
}

float brushWeight(FieldInputs* f, uint b, float distance)
{
    float radius = f[0].brush[b].radius;
    float inner = radius * (1 - f[0].brush[b].falloff);
    float strength = f[0].brush[b].strength;
    if (distance <= inner)
    {
        return strength;
    }
    if (distance >= radius)
    {
        return 0;
    }
    float t = (radius - distance) / (radius - inner);
    return strength * t * t * (3 - 2 * t);
}

/**
 * Two cloud types and the weight of the second: a texel's mix of types, continuous wherever the weather changes
 * continuously (a transition's progress, a cell's growth and its edge, a brush's falloff), so the profiles blend
 * instead of switching.
 */
struct Mix
{
    float a;
    float b;
    float wb;

    /** Mixes type `t` in with weight `w`: (1 - w) of the mix, w of t; two types kept, the lighter one replaced. */
    [mutating]
    void add(float t, float w)
    {
        w = saturate(w);
        float wa = (1 - wb) * (1 - w);
        float wbNew = wb * (1 - w);
        if (abs(t - a) < 1.0e-3)
        {
            wa += w;
        }
        else if (abs(t - b) < 1.0e-3)
        {
            wbNew += w;
        }
        else if (wa >= wbNew)
        {
            b = t;
            wbNew = w;
        }
        else
        {
            a = t;
            wa = w;
        }
        float total = max(wa + wbNew, 1.0e-6);
        wb = wbNew / total;
        if (wb > 0.5) // the heavier first
        {
            float swap = a;
            a = b;
            b = swap;
            wb = 1 - wb;
        }
    }
};

[shader("compute")]
[numthreads(8, 8, 1)]
void fieldMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    FieldInputs* f = push.inputs;
    float texel = push.extent / float(push.size);
    float2 p = push.centre + (float2(id.xy) + 0.5 - 0.5 * float(push.size)) * texel;
    float3 lattice = f[0].noiseOrigin + float3(p.x, 0, p.y) / f[0].noiseScale;
    float n = perlinFbm(lattice.x, lattice.y, lattice.z, push.period, f[0].octaves, f[0].seed);
    float coverage = saturate(f[0].coverage + f[0].variation * (2 * n - 1));
    float precipitation = f[0].precipitation * coverage;
    Mix mix = { f[0].type, f[0].toType, f[0].toWeight };
    if (mix.wb > 0.5)
    {
        mix.a = f[0].toType;
        mix.b = f[0].type;
        mix.wb = 1 - f[0].toWeight;
    }
    float top = f[0].top;
    float preset = coverage;
    float anvilCover = 0;      // the anvils' plumes (drawn)
    float withAnvils = coverage; // the field's coverage with the anvils' discs (WeatherField's)

    float strongest = 0;
    for (uint s = 0; s < min(f[0].cells, MAX_CELLS); s++)
    {
        float r = f[0].cell[s].coreRadius;
        float d = length(p - f[0].cell[s].core);
        float w = 1 - smoothOf((d - 0.6 * r) / (0.4 * r));
        float core = w * f[0].cell[s].coverage;
        if (core > 0)
        {
            coverage = max(coverage, core);
            withAnvils = max(withAnvils, core);
            precipitation = max(precipitation, w * f[0].cell[s].precipitation);
            // The cell's type takes over as its core outgrows the preset's cover (WeatherField: the strongest wins).
            float take = smoothOf(core / max(preset, 0.05));
            mix.add(f[0].cell[s].type, take);
            mix.add(f[0].cell[s].matureType, take * f[0].cell[s].mature);
            if (core > strongest)
            {
                strongest = core;
                top = lerp(top, f[0].cell[s].top, take);
            }
        }
        if (f[0].cell[s].anvil > 0)
        {
            float ra = f[0].cell[s].anvilRadius;
            float strength = 0.8 * min(1.0, f[0].cell[s].coverage + 0.5);
            if (w < 0.5)
            {
                // WeatherField's disc, for the coverage channel gameplay reads.
                float da = length(p - f[0].cell[s].anvilCentre);
                withAnvils = max(withAnvils, (1 - smoothOf((da - 0.5 * ra) / (0.5 * ra))) * strength);
            }
            // The plume drawn: from the core along the wind at the top (towards the anvil's centre), widening from
            // the core's width to the anvil's, its far end rounded, thinning downwind.
            float2 axis = f[0].cell[s].anvilCentre - f[0].cell[s].core;
            float reach = length(axis);
            axis = reach > 1 ? axis / reach : float2(1, 0);
            float len = max(2 * reach, 0.6 * ra) + r;
            float2 q = p - f[0].cell[s].core;
            float u = dot(q, axis);
            float v = abs(q.x * axis.y - q.y * axis.x);
            float t = saturate(u / len);
            float half = lerp(0.8 * r, max(0.7 * ra, r), sqrt(t));
            float across = 1 - smoothOf((v - 0.35 * half) / (0.65 * half));
            float along = (1 - smoothOf((u - 0.55 * len) / (0.45 * len))) * (1 - smoothOf((-u - 0.4 * r) / (0.4 * r)));
            // Rounded ends: inside an ellipse over the plume's length.
            float wa = across * along * strength * (1 - 0.45 * t);
            anvilCover = max(anvilCover, wa);
        }
    }

    for (uint b = 0; b < min(f[0].brushes, MAX_BRUSHES); b++)
    {
        float w = brushWeight(f, b, length(p - f[0].brush[b].centre));
        if (w == 0)
        {
            continue;
        }
        uint mode = f[0].brush[b].mode;
        if (mode == 2)
        {
            coverage *= 1 - w;
            withAnvils *= 1 - w;
            anvilCover *= 1 - w;
            precipitation *= 1 - w;
            continue;
        }
        if (mode == 0)
        {
            coverage = min(1.0, coverage + w * f[0].brush[b].coverage);
            withAnvils = min(1.0, withAnvils + w * f[0].brush[b].coverage);
            precipitation = min(1.0, precipitation + w * f[0].brush[b].precipitation);
        }
        else
        {
            coverage += (f[0].brush[b].coverage - coverage) * w;
            withAnvils += (f[0].brush[b].coverage - withAnvils) * w;
            precipitation += (f[0].brush[b].precipitation - precipitation) * w;
        }
        mix.add(f[0].brush[b].type, smoothOf(2 * w - 0.5));
        if (f[0].brush[b].top > 0)
        {
            top = lerp(top, f[0].brush[b].top, smoothOf(2 * w - 0.5));
        }
    }
    images[push.map][id.xy] = float4(withAnvils, mix.a, min(precipitation, coverage), top);
    images[push.types][id.xy] = float4(mix.b, mix.wb, anvilCover, coverage);
}
