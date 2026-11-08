// Tests and screenshots: a clear sky behind the scene until effects-atmosphere's sky lands. Single scattering of the
// clouds' analytic atmosphere (Rayleigh and Mie, the sun through it by the Chapman approximation), 24 steps along each
// sky pixel's ray, and the sun's disc; physical units (cd/m^2). Not a technique of the library: the atmosphere's.
import bindless;
import meshlets.common;
import weather.clouds;

struct SkyPush
{
    View* view;
    CloudFrame* frame;
    uint color;
    uint depth;
    uint size;
    float altitude;      // the camera's
};

[[vk::push_constant]]
ConstantBuffer<SkyPush> push;

[shader("compute")]
[numthreads(8, 8, 1)]
void skyMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size) || textures[push.depth].Load(int3(id.xy, 0)).r > 0)
    {
        return;
    }
    CloudFrame* f = push.frame;
    float2 ndc = (float2(id.xy) + 0.5) / float2(size) * 2 - 1;
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    float3 toSun = f[0].sunDirection;
    float cosTheta = dot(dir, toSun);
    float rayleighPhase = 3 / (16 * PI) * (1 + cosTheta * cosTheta);
    float miePhase = henyeyGreenstein(cosTheta, 0.8);
    // To the atmosphere's top (100 km) or the ground, over a flat world near the camera.
    float top = 100000;
    float length = dir.y > 1.0e-3 ? (top - push.altitude) / dir.y : (dir.y < -1.0e-3 ? push.altitude / -dir.y : 300000);
    length = min(length, 300000);
    float3 light = 0;
    float3 view = 1;
    float step = length / 24;
    for (uint i = 0; i < 24; i++)
    {
        float h = max(push.altitude + dir.y * (float(i) + 0.5) * step, 0);
        float3 rayleigh = f[0].rayleighScattering * exp(-h / f[0].rayleighHeight);
        float mie = f[0].mieScattering * exp(-h / f[0].mieHeight);
        float3 extinction = rayleigh + f[0].mieExtinction * exp(-h / f[0].mieHeight);
        float3 scattered = (rayleigh * rayleighPhase + mie * miePhase) * f[0].sunIlluminance * sunTransmittance(f, h);
        float3 through = exp(-extinction * step);
        light += view * scattered * (1 - through) / max(extinction, 1.0e-12);
        view *= through;
    }
    if (cosTheta > cos(0.00465)) // the sun's disc
    {
        light += f[0].sunIlluminance * sunTransmittance(f, push.altitude) * view / (PI * 0.00465 * 0.00465);
    }
    images[push.color][id.xy] = float4(min(light, 60000.0), 1);
}
