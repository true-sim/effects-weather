// The clouds' temporal accumulation at the march's resolution (VolumetricClouds): this frame's jittered march blended
// with the history reprojected by the clouds' own mean depth (not the scene's: they are seen through), the history
// clamped to this frame's 3 x 3 neighbourhood (Karis 2014, "High Quality Temporal Supersampling", as Hillaire 2016
// and Schneider 2015 do for clouds), its weight dropped where the reprojection fell off the screen, where the history
// is new, and while a flash lights the clouds (they must answer within the frame). The clouds move with the wind: a
// point of cloud now was `motion` upwind a frame ago (the weather's drift over the frame), so the history is read
// there; and the shapes evolve, so the history counts for less the more weather time a frame spans (VolumetricClouds:
// the blend falls with the frame's weather seconds over the history's lifetime), which keeps a time-lapse from
// smearing the clouds into streaks.
//
// Depth-aware throughout (a joint bilateral on the depth each pixel marches to, the scene's farthest under it, as the
// upsampling's): a pixel not marched this frame knows its own depth from the scene's, and its estimate, its clamp and
// its history only take the marched pixels and history taps that see the same depth. Before, a pixel just below the
// horizon took the sky's opaque clouds from the marched pixel above it into its estimate and kept them for many frames
// (the history clamp spanned both): every other pixel of the horizon's row dotted.
import bindless;
import meshlets.common;

struct TemporalPush
{
    View* view;
    uint current;        // sampled slots: this frame's light and transmittance, its depths
    uint currentDepth;
    uint history;        // sampled slots: the history's light and depths (the frame before's output)
    uint historyDepth;
    uint output;         // storage slots: the new history
    uint outputDepth;
    uint size;
    float blend;         // the history's weight
    uint valid;          // 0: no history yet
    float3 motion;       // metres the clouds moved since the frame before (the wind's drift), world axes
    uint sparse;         // the march's interleave s and phase (march.slang): s x s blocks, one pixel each new
    uint depth;          // sampled slot: the scene's depth (reverse), full resolution
    uint fullSize;       // the scene's
    float range;         // metres the march reaches at most
};


[[vk::push_constant]]
ConstantBuffer<TemporalPush> push;

/** Whether two depths marched to are the same surface (or both the sky). */
float sameDepth(float a, float b)
{
    return abs(a - b) < 0.05 * max(min(a, b), 1.0) ? 1.0 : 0.0;
}

/**
 * Whether a pixel's march (its clouds' mean depth and the depth it marched to, `d`) stands for a pixel marching to
 * `own`: it marched to the same depth, or its clouds lie well in front of both (a tower before the horizon is the same
 * tower whether the sky or the ground lies behind it).
 */
float agrees(float2 d, float own)
{
    return sameDepth(d.y, own) > 0 || (d.x < 0.9 * d.y && d.x < 0.9 * own) ? 1.0 : 0.0;
}

/** The depth march pixel `id` marches to: the scene's farthest under its footprint (march.slang's), or the range. */
float marchedTo(uint2 id, uint2 size)
{
    uint2 full = uint2(push.fullSize & 0xffff, push.fullSize >> 16);
    float2 footprint = float2(full) / float2(size);
    float sceneDepth = 1;
    for (uint k = 0; k < 4; k++)
    {
        float2 corner = (float2(id) + float2(k & 1, k >> 1) * 0.5 + 0.25) * footprint;
        uint2 texel = min(uint2(corner), full - 1);
        sceneDepth = min(sceneDepth, textures[push.depth].Load(int3(texel, 0)).r);
    }
    float limit = push.range;
    if (sceneDepth > 0)
    {
        float2 uv = (float2(id) + 0.5) / float2(size);
        float4 hit = mul(push.view[0].inverseViewProjection, float4(uv * 2 - 1, sceneDepth, 1));
        limit = min(limit, length(hit.xyz / hit.w));
    }
    return limit;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void temporalMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    int s = int(push.sparse & 0xff);
    int2 phase = int2((push.sparse >> 8) & 0xff, (push.sparse >> 16) & 0xff);
    // This frame's marched pixel of the block (itself when it was marched, else the block's nearest new pixel), and
    // the neighbourhood of new pixels around it (3 x 3 of the marched lattice) for the history's clamp.
    int2 self = int2(id.xy);
    int2 block = (self / s) * s + phase;
    block = min(block, int2(size) - 1);
    bool fresh = s == 1 || all(block == self);
    float4 now = textures[push.current].Load(int3(block, 0));
    float2 depths = textures[push.currentDepth].Load(int3(block, 0)).rg;
    // The depth this pixel marches to: its own where it was marched, else the scene's under it.
    float own = fresh ? depths.y : marchedTo(id.xy, size);
    float4 lo = 1.0e30;
    float4 hi = -1.0e30;
    float4 sum = 0;
    float2 depthSum = 0;
    float count = 0;
    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            int2 q = block + int2(x, y) * s;
            q = select(q < 0, q + s, select(q >= int2(size), q - s, q)); // stay on the lattice
            float2 d = textures[push.currentDepth].Load(int3(q, 0)).rg;
            if (agrees(d, own) > 0)
            {
                float4 v = textures[push.current].Load(int3(q, 0));
                lo = min(lo, v);
                hi = max(hi, v);
                sum += v;
                depthSum += d;
                count += 1;
            }
        }
    }
    if (!fresh)
    {
        // Not marched this frame: until the history says better, the new pixels around it that see its depth
        // (spatially); none: nothing in front of it yet.
        now = count > 0 ? sum / count : float4(0, 0, 0, 1);
        depths = count > 0 ? float2(depthSum.x / count, own) : float2(own, own);
    }
    if (count == 0)
    {
        lo = now;
        hi = now;
    }
    float weight = push.valid != 0 ? push.blend : 0;
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(uv * 2 - 1, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    float4 clip = mul(push.view[0].previousViewProjection, float4(dir * depths.x - push.motion, 1));
    float2 previous = clip.w > 0 ? (clip.xy / clip.w) * 0.5 + 0.5 : float2(-1, -1);
    float4 result = now;
    float2 outDepths = depths;
    if (weight > 0 && all(previous >= 0) && all(previous <= 1))
    {
        // The history at `previous`, bilinear among the taps that marched to this pixel's depth.
        float2 at = previous * float2(size) - 0.5;
        int2 corner = int2(floor(at));
        float2 fraction = at - float2(corner);
        float4 old = 0;
        float2 oldDepths = 0;
        float total = 0;
        for (uint k = 0; k < 4; k++)
        {
            int2 offset = int2(k & 1, k >> 1);
            int2 q = clamp(corner + offset, int2(0, 0), int2(size) - 1);
            float2 d = textures[push.historyDepth].Load(int3(q, 0)).rg;
            float wk = (offset.x == 1 ? fraction.x : 1 - fraction.x) * (offset.y == 1 ? fraction.y : 1 - fraction.y)
                * agrees(d, own);
            old += wk * textures[push.history].Load(int3(q, 0));
            oldDepths += wk * d;
            total += wk;
        }
        if (total > 1.0e-3)
        {
            old /= total;
            oldDepths /= total;
            if (!fresh)
            {
                // A pixel not marched this frame is its history (clamped by the new pixels around it that see its
                // depth), as long as the history holds: blending in the spatial estimate every frame would blur
                // the image by the blocks.
                weight = push.blend > 0 ? saturate(weight / push.blend) : 0;
                outDepths = float2(oldDepths.x, own);
                // Its neighbourhood is of other pixels (the lattice's, s apart): a looser clamp, or every frame's
                // clamp pulls it towards its neighbours (edges blur).
                float4 spread = 0.5 * (hi - lo);
                lo -= spread;
                hi += spread;
            }
            result = lerp(now, clamp(old, lo, hi), weight);
        }
    }
    images[push.output][id.xy] = result;
    images[push.outputDepth][id.xy] = float4(outDepths, 0, 0);
}
