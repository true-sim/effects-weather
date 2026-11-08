// The clouds' Beer shadow map (Hillaire 2020, the UE volumetric clouds course; CloudShadowMap): an orthographic map
// along the sun over the ground around the camera, each texel marched through the cloud slab on the base shape (the
// shadows are kilometres wide; detail is not needed), storing the front depth (metres from the map's plane along the
// light), the mean extinction behind the front, and the maximum optical depth. A point at light-space depth d then
// has transmittance exp(-min(B, G max(0, d - R))): exact for a uniform cloud behind the front, and never darker than
// the whole column.
import bindless;
import meshlets.common;
import weather.clouds;

struct ShadowMapPush
{
    CloudFrame* frame;
    uint output;         // storage slot, RGBA16F
    uint shape;          // sampled slot: the base shape volume
    uint size;
    uint steps;
};

[[vk::push_constant]]
ConstantBuffer<ShadowMapPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void shadowMapMain(uint3 id : SV_DispatchThreadID)
{
    if (any(id.xy >= push.size))
    {
        return;
    }
    CloudFrame* f = push.frame;
    float2 uv = (float2(id.xy) + 0.5) / float(push.size) - 0.5;
    float3 L = f[0].sunDirection;
    float3 plane = f[0].shadowCentre + (f[0].shadowU * uv.x + f[0].shadowV * uv.y) * f[0].shadowExtent
        + L * f[0].shadowDepth;
    float ly = max(L.y, 0.02);
    float sTop = max((plane.y - f[0].maxAltitude) / ly, 0);
    float sBottom = max(plane.y / ly, sTop);
    float step = (sBottom - sTop) / float(push.steps);
    float front = -1;
    float last = 0;
    float depth = 0;
    for (uint i = 0; i < push.steps; i++)
    {
        float s = sTop + (float(i) + 0.5) * step;
        float3 p = plane - L * s;
        CloudPoint c = cloudAt(f, float3(p.x, 0, p.z), p.y, false, push.shape, push.shape, push.shape);
        if (c.extinction > 0)
        {
            if (front < 0)
            {
                front = s - 0.5 * step;
            }
            last = s + 0.5 * step;
            depth += c.extinction * step;
        }
    }
    float mean = front >= 0 ? depth / max(last - front, 1.0) : 0;
    images[push.output][id.xy] = float4(front >= 0 ? front : sBottom, mean, depth, 1);
}
