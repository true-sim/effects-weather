// The clouds' temporal accumulation at the march's resolution (VolumetricClouds): this frame's jittered march blended
// with the history reprojected by the clouds' own mean depth (not the scene's: they are seen through), the history
// clamped to this frame's 3 x 3 neighbourhood (Karis 2014, "High Quality Temporal Supersampling", as Hillaire 2016
// and Schneider 2015 do for clouds), its weight dropped where the reprojection fell off the screen, where the history
// is new, and while a flash lights the clouds (they must answer within the frame).
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
    float4 now = textures[push.current].Load(int3(id.xy, 0));
    float2 depths = textures[push.currentDepth].Load(int3(id.xy, 0)).rg;
    float4 lo = now;
    float4 hi = now;
    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            int2 q = clamp(int2(id.xy) + int2(x, y), int2(0, 0), int2(size) - 1);
            float4 v = textures[push.current].Load(int3(q, 0));
            lo = min(lo, v);
            hi = max(hi, v);
        }
    }
    float weight = push.valid != 0 ? push.blend : 0;
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(uv * 2 - 1, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    float4 clip = mul(push.view[0].previousViewProjection, float4(dir * depths.x, 1));
    float2 previous = clip.w > 0 ? (clip.xy / clip.w) * 0.5 + 0.5 : float2(-1, -1);
    float4 result = now;
    if (weight > 0 && all(previous >= 0) && all(previous <= 1))
    {
        float4 old = textures[push.history].SampleLevel(samplers[LINEAR_CLAMP], previous, 0);
        float oldLimit = textures[push.historyDepth].SampleLevel(samplers[LINEAR_CLAMP], previous, 0).g;
        // Where the scene's depth moved (something passed in front), the history saw other clouds.
        float moved = abs(oldLimit - depths.y) / max(depths.y, 1.0);
        weight *= saturate(1 - 4 * moved);
        result = lerp(now, clamp(old, lo, hi), weight);
    }
    images[push.output][id.xy] = result;
    images[push.outputDepth][id.xy] = float4(depths, 0, 0);
}
