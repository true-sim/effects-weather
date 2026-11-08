// Where clouds can be, coarsely, for the march's empty-space skipping (WeatherGpu's maps pass, after the field): per
// block of the weather maps' texels, the lowest base and the highest top of anything that can stand over it (the
// types' layers where they cover, the storm cells' towers, their anvils, rain down to the ground), then dilated by a
// block on every side (shear, lean, turrets and the filters reach beyond a texel's own ground). The march jumps over a
// block wherever the ray is above its top or below its base (march.slang), to the block's edge or to where it reaches
// them: the same clouds, fewer empty samples. Conservative by construction: no cloud is skipped that a sample would
// have found.
import bindless;
import weather.clouds;

struct OccupancyPush
{
    CloudFrame* frame;
    uint map;            // sampled slots of one weather map: coverage, type A, rain, top
    uint types;          // type B, its weight, anvil, the coverage without the anvils
    uint cells;          // the cell lists (three indices + 1, the anvils' top)
    uint output;         // storage slot, RGBA16F: base, top (metres; nothing: base 60000, top 0)
    uint input;          // dilateMain: the blocks' (sampled slot)
    uint block;          // map texels per block along each axis
    uint size;           // the output's texels per edge
};

[[vk::push_constant]]
ConstantBuffer<OccupancyPush> push;

static const float NOTHING = 60000;

[shader("compute")]
[numthreads(8, 8, 1)]
void blockMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    CloudFrame* f = push.frame;
    float base = NOTHING;
    float top = 0;
    for (uint j = 0; j < push.block; j++)
    {
        for (uint i = 0; i < push.block; i++)
        {
            int3 texel = int3(id.xy * push.block + uint2(i, j), 0);
            float4 m = textures[push.map].Load(texel);
            float4 t = textures[push.types].Load(texel);
            float4 c = textures[push.cells].Load(texel);
            float cover = t.a + f[0].coverageOffset;
            if (cover > 1.0e-3)
            {
                // Both types' layers (the top channel overrides a type's top where it is set, as layerOf has it).
                uint ta = uint(round(saturate(m.g) * float(CLOUD_TYPES - 1)));
                uint tb = uint(round(saturate(t.r) * float(CLOUD_TYPES - 1)));
                float topA = m.a > f[0].types[ta].base + 100 ? m.a : f[0].types[ta].top;
                float topB = m.a > f[0].types[tb].base + 100 ? m.a : f[0].types[tb].top;
                base = min(base, min(f[0].types[ta].base, f[0].types[tb].base));
                top = max(top, max(topA, topB));
            }
            if (m.b > 1.0e-4 && f[0].rainAny != 0)
            {
                base = 0; // rain falls to the ground
            }
            if (t.b > 1.0e-3 && c.w > 100)
            {
                base = min(base, c.w - f[0].anvilThickness - f[0].mammatus - (f[0].virga > 0 ? f[0].virgaDepth : 0));
                top = max(top, c.w);
            }
            uint3 ids = uint3(round(c.xyz));
            for (uint k = 0; k < 3; k++)
            {
                uint id = k == 0 ? ids.x : k == 1 ? ids.y : ids.z;
                if (id == 0 || id > min(f[0].cellCount, MAX_CELLS))
                {
                    continue;
                }
                uint cell = id - 1;
                base = min(base, f[0].cells[cell].base);
                float highest = 0;
                for (uint n = 0; n < min(f[0].cells[cell].towers, MAX_TOWERS); n++)
                {
                    highest = max(highest, f[0].cells[cell].tower[n].w);
                }
                top = max(top, highest + f[0].cells[cell].overshoot);
                if (f[0].rainAny != 0)
                {
                    base = 0; // a storm's rain
                }
            }
        }
    }
    // Margins: turrets and domes bulge above the tops, half floats round.
    images[push.output][id.xy] = top > 0 ? float4(max(base - 150, 0), top + 1500, 0, 0) : float4(NOTHING, 0, 0, 0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void dilateMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    float base = NOTHING;
    float top = 0;
    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            int2 q = clamp(int2(id.xy) + int2(x, y), int2(0, 0), int2(push.size - 1, push.size - 1));
            float2 v = textures[push.input].Load(int3(q, 0)).rg;
            base = min(base, v.x);
            top = max(top, v.y);
        }
    }
    images[push.output][id.xy] = float4(base, top, 0, 0);
}
