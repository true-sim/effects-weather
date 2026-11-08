// The surfaces' screen-space shadow mask of the sun from the clouds' Beer shadow map (CloudShadowMask): the
// transmittance towards the sun at each pixel's point, read by shading through the sun's shadow table entry.
import bindless;
import meshlets.common;
import weather.clouds;

struct MaskPush
{
    View* view;
    CloudFrame* frame;
    uint output;         // storage slot, R8: 1 lit, 0 in the clouds' shadow
    uint depth;          // sampled slot: the view's depth before shading
    uint shadow;         // sampled slot: the Beer shadow map
    uint size;
    float3 camera;       // the camera from the anchor
};

[[vk::push_constant]]
ConstantBuffer<MaskPush> maskPush;

[shader("compute")]
[numthreads(8, 8, 1)]
void maskMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(maskPush.size & 0xffff, maskPush.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    float depth = textures[maskPush.depth].Load(int3(id.xy, 0)).r;
    float lit = 1;
    if (depth > 0)
    {
        float2 ndc = (float2(id.xy) + 0.5) / float2(size) * 2 - 1;
        float4 p = mul(maskPush.view[0].inverseViewProjection, float4(ndc, depth, 1));
        lit = cloudShadow(maskPush.frame, maskPush.shadow, maskPush.camera + p.xyz / p.w);
    }
    images[maskPush.output][id.xy] = float4(lit, 0, 0, 1);
}
