// The clouds' temporal accumulation at the march's resolution (VolumetricClouds): this frame's jittered march blended
// with the history reprojected by the clouds' own mean depth (not the scene's: they are seen through), the history
// clamped to this frame's 3 x 3 neighbourhood (Karis 2014, "High Quality Temporal Supersampling", as Hillaire 2016
// and Schneider 2015 do for clouds), its weight dropped where the reprojection fell off the screen, where the history
// is new, and while a flash lights the clouds (they must answer within the frame). The clouds move with the wind: a
// point of cloud now was `motion` upwind a frame ago (the weather's drift over the frame), so the history is read
// there; and the shapes evolve, so the history counts for less the more weather time a frame spans (VolumetricClouds:
// the blend falls with the frame's weather seconds over the history's lifetime), which keeps a time-lapse from
// smearing the clouds into streaks.
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
};


[[vk::push_constant]]
ConstantBuffer<TemporalPush> push;

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
    float4 lo = now;
    float4 hi = now;
    float4 sum = 0;
    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            int2 q = block + int2(x, y) * s;
            q = select(q < 0, q + s, select(q >= int2(size), q - s, q)); // stay on the lattice
            float4 v = textures[push.current].Load(int3(q, 0));
            lo = min(lo, v);
            hi = max(hi, v);
            sum += v;
        }
    }
    if (!fresh)
    {
        // Not marched this frame: until the history says better, the new pixels around it (spatially).
        now = lerp(now, sum / 9, 0.5);
    }
    float weight = push.valid != 0 ? push.blend : 0;
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(uv * 2 - 1, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    float4 clip = mul(push.view[0].previousViewProjection, float4(dir * depths.x - push.motion, 1));
    float2 previous = clip.w > 0 ? (clip.xy / clip.w) * 0.5 + 0.5 : float2(-1, -1);
    float4 result = now;
    if (weight > 0 && all(previous >= 0) && all(previous <= 1))
    {
        float4 old = textures[push.history].SampleLevel(samplers[LINEAR_CLAMP], previous, 0);
        float oldLimit = textures[push.historyDepth].SampleLevel(samplers[LINEAR_CLAMP], previous, 0).g;
        // Where the scene's depth moved (something passed in front), the history saw other clouds.
        float moved = abs(oldLimit - depths.y) / max(depths.y, 1.0);
        weight *= saturate(1 - 4 * moved);
        if (!fresh)
        {
            // A pixel not marched this frame is its history (clamped by the new pixels around it), as long as the
            // history holds: blending in the spatial estimate every frame would blur the image by the blocks.
            weight = push.blend > 0 ? saturate(weight / push.blend) : 0;
            // Its neighbourhood is of other pixels (the lattice's, s apart): a looser clamp, or every frame's clamp
            // pulls it towards its neighbours (edges blur).
            float4 spread = 0.5 * (hi - lo);
            lo -= spread;
            hi += spread;
        }
        result = lerp(now, clamp(old, lo, hi), weight);
    }
    images[push.output][id.xy] = result;
    images[push.outputDepth][id.xy] = float4(depths, 0, 0);
}
