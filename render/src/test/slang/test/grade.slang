// Tests: a camera's white balance on the scene's colour, in place (CloudScene), before the display transform.
import bindless;

struct GradePush
{
    uint color;          // storage slot: the scene's colour
    uint size;           // width, height in the high 16 bits
    float3 gains;        // per channel
};

[[vk::push_constant]]
ConstantBuffer<GradePush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void gradeMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    float4 c = images[push.color][id.xy];
    images[push.color][id.xy] = float4(c.rgb * push.gains, c.a);
}
