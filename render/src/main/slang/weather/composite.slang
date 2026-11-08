// The clouds over the scene's colour, in place (VolumetricClouds, SkyboxClouds): scene * T + L.
//
// compositeMain upsamples the march's light and transmittance to the scene's pixels, joint-bilaterally on depth
// (Kopf et al. 2007; the four nearest march texels weighted by how well the depth each marched to matches this
// pixel's), so clouds stop at mountains and aircraft.
//
// skyboxMain is the cheap 2D tier: no noise volumes and no march, the weather map's coverage and type on the layer of
// each type, intersected once per pixel: the coverage as the fraction of the sky covered, the layer a slab lit
// analytically (the two-stream slab's diffuse reflection or transmission, the sun's single scattering with the
// approximate Mie phase), behind it the scene by the direct transmittance exp(-tau).
import bindless;
import meshlets.common;
import weather.clouds;

struct CompositePush
{
    View* view;
    CloudFrame* frame;
    uint color;          // storage slot: the scene's colour
    uint depth;          // sampled slot: the scene's depth
    uint clouds;         // sampled slots: the march's (or history's) light and transmittance, and depths
    uint cloudDepth;
    uint size;           // the scene's region
    uint marchSize;
    float3 camera;       // the camera from the anchor (y: altitude)
    float scale;         // OUTPUT_SCALE (skyboxMain: above 0 for the aerial perspective)
};

[[vk::push_constant]]
ConstantBuffer<CompositePush> push;

float sceneDistance(float2 ndc, float depth)
{
    if (depth <= 0)
    {
        return 3.4e38;
    }
    float4 p = mul(push.view[0].inverseViewProjection, float4(ndc, depth, 1));
    return length(p.xyz / p.w);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void compositeMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    uint2 marchSize = uint2(push.marchSize & 0xffff, push.marchSize >> 16);
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    float distance = sceneDistance(uv * 2 - 1, textures[push.depth].Load(int3(id.xy, 0)).r);
    float2 at = uv * float2(marchSize) - 0.5;
    int2 base = int2(floor(at));
    float2 fraction = at - float2(base);
    float4 sum = 0;
    float total = 0;
    for (uint k = 0; k < 4; k++)
    {
        int2 offset = int2(k & 1, k >> 1);
        int2 q = clamp(base + offset, int2(0, 0), int2(marchSize) - 1);
        float bilinear = (offset.x == 1 ? fraction.x : 1 - fraction.x) * (offset.y == 1 ? fraction.y : 1 - fraction.y);
        float marched = textures[push.cloudDepth].Load(int3(q, 0)).g;
        float agreement = 1 / (1.0e-3 + abs(min(distance, 1.0e7) - min(marched, 1.0e7)) / max(min(distance, 1.0e7), 1.0));
        float w = (bilinear + 1.0e-3) * agreement;
        sum += w * textures[push.clouds].Load(int3(q, 0));
        total += w;
    }
    float4 c = sum / max(total, 1.0e-6);
    float4 scene = images[push.color][id.xy];
    float3 color = scene.rgb * c.a + c.rgb * push.scale;
    images[push.color][id.xy] = float4(min(color, 65000.0), scene.a);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void skyboxMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    CloudFrame* f = push.frame;
    float2 ndc = (float2(id.xy) + 0.5) / float2(size) * 2 - 1;
    float distance = sceneDistance(ndc, textures[push.depth].Load(int3(id.xy, 0)).r);
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    // The layer of the type under the point where the ray crosses the middle of a typical layer, then that type's.
    float3 light = 0;
    float transmittance = 1;
    float y = push.camera.y;
    if (abs(dir.y) > 1.0e-4)
    {
        float guess = 0.5 * (f[0].types[2].base + f[0].types[2].top);
        float t = (guess - y) / dir.y;
        if (t > 0)
        {
            float3 p = push.camera + dir * t;
            Weather w = weatherAt(f, p.xz);
            uint type = w.a;
            float base = f[0].types[type].base;
            float top = w.top > base ? w.top : f[0].types[type].top;
            float middle = 0.5 * (base + top);
            float tm = (middle - y) / dir.y;
            float3 q = push.camera + dir * tm;
            Weather at = weatherAt(f, q.xz);
            if (tm > 0 && tm < distance && at.coverage > 0)
            {
                // The coverage as the fraction of the sky the layer covers; where it does, a slab of the type's
                // extinction over the profile's mean density (a half), its light the two-stream slab's: diffusely
                // reflected above it, diffusely transmitted below it, plus the sun's single scattering.
                float thickness = top - base;
                float tau = thickness * f[0].types[type].column * f[0].densityScale;
                float3 toSun = f[0].sunDirection;
                float mu = max(toSun.y, 0.05);
                float view = tau / max(abs(dir.y), 0.05);
                float direct = exp(-view);
                float diffuse = diffuseTransmittance(tau);
                float3 sun = f[0].sunIlluminance * sunTransmittance(f, top);
                float3 down = sun * max(toSun.y, 0) + f[0].skyIlluminance;
                bool above = y > middle;
                float3 slab = down * (above ? 1 - diffuse : max(diffuse - exp(-tau / mu), 0)) / PI;
                float single = cloudPhase(f, type, dot(dir, toSun), 1) * exp(-tau / mu);
                float3 cloud = slab + sun * single * (1 - direct);
                float c = at.coverage;
                light = c * cloud;
                transmittance = 1 - c + c * direct;
                if (push.scale > 0)
                {
                    float3 ap = aerialTransmittance(f, y, middle, tm);
                    light = light * ap + f[0].apRadiance * (1 - ap) * (1 - transmittance);
                }
            }
        }
    }
    float4 scene = images[push.color][id.xy];
    images[push.color][id.xy] = float4(min(scene.rgb * transmittance + light, 65000.0), scene.a);
}
