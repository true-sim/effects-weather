// Tests and screenshots: the atmosphere until effects-atmosphere's lands. Single scattering of the clouds' analytic
// atmosphere (Rayleigh and Mie, the sun through it by the Chapman approximation and through the clouds by their Beer
// shadow map), plus the clouds' diffused light on the air under them, along each pixel's ray: to the atmosphere's top
// for the sky (with the sun's disc), to the surface for the scene (aerial perspective: colour * T + in-scattering).
// Physical units (cd/m^2). Not a technique of the library: the atmosphere's.
import bindless;
import meshlets.common;
import weather.clouds;

struct SkyPush
{
    View* view;
    CloudFrame* frame;
    uint color;
    uint depth;
    uint shadow;         // sampled slot: the clouds' Beer shadow map
    uint size;
    float3 camera;       // the camera from the anchor (y: altitude)
    float pad;
    float3 below;        // the clouds' diffused light under them, lux (WeatherGpu.skyBelowClouds)
};

[[vk::push_constant]]
ConstantBuffer<SkyPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void skyMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    CloudFrame* f = push.frame;
    float depth = textures[push.depth].Load(int3(id.xy, 0)).r;
    float2 ndc = (float2(id.xy) + 0.5) / float2(size) * 2 - 1;
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    float3 toSun = f[0].sunDirection;
    float cosTheta = dot(dir, toSun);
    float rayleighPhase = 3 / (16 * PI) * (1 + cosTheta * cosTheta);
    float miePhase = henyeyGreenstein(cosTheta, 0.8);
    float altitude = push.camera.y;
    float length;
    if (depth > 0)
    {
        float4 hit = mul(push.view[0].inverseViewProjection, float4(ndc, depth, 1));
        length = min(distance(hit.xyz / hit.w, float3(0, 0, 0)), 300000);
    }
    else
    {
        // To the atmosphere's top (100 km) or the ground, over a flat world near the camera.
        float top = 100000;
        length = dir.y > 1.0e-3 ? (top - altitude) / dir.y : (dir.y < -1.0e-3 ? altitude / -dir.y : 300000);
        length = min(length, 300000);
    }
    float3 light = 0;
    float3 view = 1;
    float step = length / 32;
    float3 diffused = push.below / PI; // the radiance of the clouds' diffused light, from above
    for (uint i = 0; i < 32; i++)
    {
        float3 p = push.camera + dir * ((float(i) + 0.5) * step);
        float h = max(p.y, 0);
        float3 rayleigh = f[0].rayleighScattering * exp(-h / f[0].rayleighHeight);
        float mie = f[0].mieScattering * exp(-h / f[0].mieHeight);
        float3 extinction = rayleigh + f[0].mieExtinction * exp(-h / f[0].mieHeight);
        float shade = push.shadow != 0 ? cloudShadow(f, push.shadow, p) : 1;
        // Under the clouds (below their tops), the air is lit by their diffused light too (about isotropically).
        float under = h < f[0].maxAltitude ? 1 : 0;
        float3 scattered = (rayleigh * rayleighPhase + mie * miePhase) * f[0].sunIlluminance * sunTransmittance(f, h)
            * shade + (rayleigh + mie) * diffused * 0.5 * under;
        float3 through = exp(-extinction * step);
        light += view * scattered * (1 - through) / max(extinction, 1.0e-12);
        view *= through;
    }
    if (depth > 0)
    {
        float4 scene = images[push.color][id.xy];
        images[push.color][id.xy] = float4(min(scene.rgb * view + light, 65000.0), scene.a);
        return;
    }
    if (cosTheta > cos(0.00465)) // the sun's disc
    {
        light += f[0].sunIlluminance * sunTransmittance(f, altitude) * view / (PI * 0.00465 * 0.00465);
    }
    images[push.color][id.xy] = float4(min(light, 60000.0), 1);
}
