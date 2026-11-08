// The weather maps on the GPU (WeatherGpu): weather-model's WeatherField evaluated at every texel, every frame, from
// its small inputs (the preset in effect, the wind's advection offset, the storm cells, the brushes), as its class
// documentation defines it: the preset's Perlin fbm coverage, then the cells' cores and anvils,
// then the brushes in order. Every texel is the weather at the frame's time, so nothing moving (storm cells, the wind's
// drift, brushes) shows the seams of a map refreshed tile by tile. The maps are centred on the frame's anchor (snapped
// to their texels, so texels keep their place on the ground): texel (i, j) holds the plane point
// anchor + ((i, j) + 0.5 - size / 2) * extent / size. The CPU's maps (WeatherGrid) stay the gameplay's.
//
// The coverage channel is the field's (the cells' cores and the anvils' discs in it, as gameplay samples them). The
// renderer draws the storm cells themselves as convective towers (clouds.slang's convective model, from the cells'
// towers in the frame record), so the cover it reads from the second map's alpha and the types are the preset's and
// the brushes' alone; the third map lists the cells whose towers may reach each texel (their indices + 1, up to
// three, the nearest first) and the anvils' top. The anvils are the second map's blue: each cell's anvil as a plume of
// ice leaving its core downwind, widening to tens of kilometres and thinning with the distance (a cumulonimbus incus),
// its edges sharp.
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
    float reach;         // metres from the core within which its towers may stand (the cell list's footprint)
    float top;           // its anvil's top (the tropopause it spreads under), metres
    float anvil;         // its anvil's growth [0, 1] (0: none), faded as the cell dies
    float2 anvilCentre;
    float anvilRadius;   // the anvil's full radius
    float pad0;
    float pad1;
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
    float anvilWidth;    // an anvil's half width at its far end, over its radius
    float anvilLength;   // how far it reaches downwind, over its radius
    float pad2;
    float pad3;
    FieldCell cell[MAX_CELLS];
    FieldBrush brush[MAX_BRUSHES];
};

struct FieldPush
{
    FieldInputs* inputs;
    uint map;            // storage slots, RGBA16F: coverage, type A, rain, top; type B, its weight, anvil, the
                         // coverage without the anvils
    uint types;
    uint cells;          // storage slot, RGBA16F: up to three cells' indices + 1, the anvils' top (metres)
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
    float anvilCover = 0;      // the anvils' plumes (drawn)
    float anvilWeight = 0;     // and their tops, weighted by their cover
    float anvilTops = 0;
    float withAnvils = coverage; // the field's coverage with the cores and the anvils' discs (WeatherField's)
    float cells = coverage;    // the cover with the cores (the rain falls where they are)
    // The cells whose towers may reach this texel: the three nearest (by distance over reach), indices + 1.
    float3 list = 0;
    float3 keys = 1.0e9;
    float margin = 1.5 * texel;

    for (uint s = 0; s < min(f[0].cells, MAX_CELLS); s++)
    {
        float r = f[0].cell[s].coreRadius;
        float d = length(p - f[0].cell[s].core);
        float w = 1 - smoothOf((d - 0.6 * r) / (0.4 * r));
        float core = w * f[0].cell[s].coverage;
        if (core > 0)
        {
            withAnvils = max(withAnvils, core);
            cells = max(cells, core);
            precipitation = max(precipitation, w * f[0].cell[s].precipitation);
        }
        if (d < f[0].cell[s].reach + margin)
        {
            float key = d / max(f[0].cell[s].reach, 1.0);
            float id = float(s + 1);
            if (key < keys.x)
            {
                keys = float3(key, keys.xy);
                list = float3(id, list.xy);
            }
            else if (key < keys.y)
            {
                keys = float3(keys.x, key, keys.y);
                list = float3(list.x, id, list.y);
            }
            else if (key < keys.z)
            {
                keys.z = key;
                list.z = id;
            }
        }
        float grown = f[0].cell[s].anvil;
        if (grown > 0)
        {
            float ra = f[0].cell[s].anvilRadius;
            float strength = 0.8 * min(1.0, f[0].cell[s].coverage + 0.5);
            if (w < 0.5)
            {
                // WeatherField's disc, for the coverage channel gameplay reads.
                float rg = ra * grown;
                float da = length(p - f[0].cell[s].anvilCentre);
                withAnvils = max(withAnvils, (1 - smoothOf((da - 0.5 * rg) / (0.5 * rg))) * strength);
            }
            // The plume drawn: from the core along the wind at the top (towards the anvil's centre), widening from
            // the core's width to anvilWidth times the anvil's radius, anvilLength radii downwind as it grows, its
            // ends rounded, a little upwind of the core too (the anvil's overhang), thinning downwind; its edges
            // sharp (ice spreading as a sheet, not fading out over kilometres).
            float2 axis = f[0].cell[s].anvilCentre - f[0].cell[s].core;
            float reach = length(axis);
            axis = reach > 1 ? axis / reach : float2(1, 0);
            // It spreads out of the tower's top: from nothing, to the core's size over its first quarter, then
            // downwind.
            float start = smoothOf(grown / 0.25);
            float len = f[0].anvilLength * ra * grown + r * start;
            float back = (0.6 * r + 0.15 * ra) * grown;
            float2 q = p - f[0].cell[s].core;
            float u = dot(q, axis);
            float v = abs(q.x * axis.y - q.y * axis.x);
            float t = saturate(u / len);
            float half = lerp(r * start + 0.1 * ra * grown, max(f[0].anvilWidth * ra * grown, r * start), sqrt(t));
            // Rounded ends: the half width closes over the last fifth downwind and behind the core.
            float ahead = saturate((u - 0.8 * len) / (0.2 * len));
            float behind = saturate(-u / max(back, 1.0));
            half *= sqrt(saturate(1 - ahead * ahead)) * sqrt(saturate(1 - behind * behind));
            float across = 1 - smoothOf((v - 0.9 * half) / max(0.1 * half, 1.0));
            float wa = (u > -back && u < len && half > 1) ? across * strength * min(1.0, 3 * grown) * (1 - 0.8 * t * t)
                : 0;
            anvilCover = max(anvilCover, wa);
            // The top is defined around the plume too (a faint weight), so that filtering it at the plume's edge
            // blends it with its own top, not with nothing.
            float weight = wa + 1.0e-3 * grown * saturate(1 - length(q) / (len + ra));
            anvilWeight += weight;
            anvilTops += weight * f[0].cell[s].top;
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
            cells *= 1 - w;
            anvilCover *= 1 - w;
            precipitation *= 1 - w;
            continue;
        }
        if (mode == 0)
        {
            coverage = min(1.0, coverage + w * f[0].brush[b].coverage);
            withAnvils = min(1.0, withAnvils + w * f[0].brush[b].coverage);
            cells = min(1.0, cells + w * f[0].brush[b].coverage);
            precipitation = min(1.0, precipitation + w * f[0].brush[b].precipitation);
        }
        else
        {
            coverage += (f[0].brush[b].coverage - coverage) * w;
            withAnvils += (f[0].brush[b].coverage - withAnvils) * w;
            cells += (f[0].brush[b].coverage - cells) * w;
            precipitation += (f[0].brush[b].precipitation - precipitation) * w;
        }
        mix.add(f[0].brush[b].type, smoothOf(2 * w - 0.5));
        if (f[0].brush[b].top > 0)
        {
            top = lerp(top, f[0].brush[b].top, smoothOf(2 * w - 0.5));
        }
    }
    images[push.map][id.xy] = float4(withAnvils, mix.a, min(precipitation, cells), top);
    images[push.types][id.xy] = float4(mix.b, mix.wb, anvilCover, coverage);
    images[push.cells][id.xy] = float4(list, anvilWeight > 1.0e-7 ? anvilTops / anvilWeight : 0);
}
