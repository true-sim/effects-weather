// The sky's illuminance on a horizontal surface at the camera (WeatherGpu.skyLight), from effects-atmosphere's sky-view
// table: its radiance integrated over the upper hemisphere, E = integral of L cos(theta) d(omega), as pi times the mean
// radiance of 256 cosine-distributed directions (stratified 16 x 16). The clouds' ambient light from above and the
// light under them come from it: by day and in twilight alike (a sun below the horizon still lights the sky).
import bindless;
import atmosphere.atmosphere;

struct SkyLightPush
{
    AtmosphereFrame* atmosphere;
    float4* output;      // rgb: lux (scene units)
    uint skyView;        // sampled slot: the sky-view table
    uint size;           // its width, height in the high 16 bits
};

[[vk::push_constant]]
ConstantBuffer<SkyLightPush> push;

groupshared float3 sums[64];

[shader("compute")]
[numthreads(64, 1, 1)]
void skyLightMain(uint3 id : SV_GroupThreadID)
{
    AtmosphereFrame* a = push.atmosphere;
    SkyFrame frame = skyFrame(a, cameraPosition(a));
    float2 size = float2(float(push.size & 0xffff), float(push.size >> 16));
    float3 sum = 0;
    for (uint k = 0; k < 4; k++)
    {
        uint i = id.x * 4 + k;
        float u1 = (float(i % 16) + 0.5) / 16;
        float u2 = (float(i / 16) + 0.5) / 16;
        float r = sqrt(u1);
        float phi = 2 * ATMOSPHERE_PI * u2;
        float3 d = frame.up * sqrt(1 - u1) + (frame.north * cos(phi) + frame.east * sin(phi)) * r;
        float2 uv = skyViewTexel(skyViewUv(frame, d), size);
        sum += textures[push.skyView].SampleLevel(samplers[LINEAR_REPEAT], uv, 0).rgb;
    }
    sums[id.x] = sum;
    GroupMemoryBarrierWithGroupSync();
    for (uint s = 32; s > 0; s >>= 1)
    {
        if (id.x < s)
        {
            sums[id.x] += sums[id.x + s];
        }
        GroupMemoryBarrierWithGroupSync();
    }
    if (id.x == 0)
    {
        push.output[0] = float4(sums[0] * (ATMOSPHERE_PI / 256), 1);
    }
}
