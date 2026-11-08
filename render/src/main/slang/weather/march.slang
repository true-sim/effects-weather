// The clouds' and rain's light along each view ray, marched at the march's resolution (VolumetricClouds): from the
// camera (or where the ray enters the cloud slab) to the scene's depth or the march's range, Schneider's two-speed
// march (coarse steps on the base shape until it meets cloud, then fine steps with detail, back to coarse after empty
// samples), a cone light march towards the sun per sample, Wrenninge's octaves of scattering, the sky's and the
// ground's ambient light by height, the flashes' glow, rain below the bases, Hillaire 2016's energy-conserving step
// and the aerial perspective at the clouds' transmittance-weighted depth. Writes the light (scaled by OUTPUT_SCALE)
// and transmittance, and the mean depth and the depth it marched to (for reprojection and upsampling).
import bindless;
import meshlets.common;
import weather.clouds;

struct MarchPush
{
    View* view;
    CloudFrame* frame;
    uint output;         // storage slot, RGBA16F: light / OUTPUT_SCALE, transmittance
    uint depthOutput;    // storage slot, RG32F: mean depth, the depth marched to
    uint depth;          // sampled slot: the scene's depth (reverse), full resolution
    uint shape;          // sampled slots: the base shape and detail volumes, the curl texture, the Beer shadow map
    uint detail;
    uint curl;
    uint shadow;
    uint size;           // the march's size: width, height in the high 16 bits
    uint fullSize;       // the scene's
    uint frameIndex;
    float3 camera;       // the camera from the frame's anchor: x, z in the ground plane, y its altitude
    uint steps;          // coarse steps across the slab
    float range;         // metres the march reaches at most
    float near;          // metres from the camera marched finely with detail from the start (fly-through)
    uint lightSteps;
    float lightStep;     // the first light step, metres; each next one twice as long
    uint flags;          // MARCH_*
};

[[vk::push_constant]]
ConstantBuffer<MarchPush> push;

/** Detail noise in fine steps (else the base shape only). */
static const uint MARCH_DETAIL = 1;
/** The ambient's ground bounce under the clouds' shadow. */
static const uint MARCH_GROUND = 2;
/** Rain below the bases. */
static const uint MARCH_RAIN = 4;

/** The light in the outputs is this much smaller than the radiance (cd/m^2), within half floats' range. */
static const float OUTPUT_SCALE = 1024.0;

/** The optical depth towards the sun from `p` (from the anchor): `push.lightSteps` steps of growing length. */
float sunDepth(CloudFrame* f, float3 p, float3 toSun, bool detail)
{
    float depth = 0;
    float travelled = 0;
    float step = push.lightStep;
    for (uint k = 0; k < push.lightSteps; k++)
    {
        float3 q = p + toSun * (travelled + 0.5 * step);
        CloudPoint c = cloudAt(f, float3(q.x, 0, q.z), q.y, detail && k < 2, push.shape, push.detail, push.curl);
        depth += c.extinction * step;
        travelled += step;
        step *= 2;
    }
    return depth;
}

/** What scatters towards the camera at a cloud point, per unit of scattering coefficient (cd/m^2 per 1/m per m). */
float3 cloudLight(CloudFrame* f, CloudPoint c, float3 p, float cosTheta, float tauSun, float3 sun, float3 ground)
{
    float3 light = 0;
    float a = 1;
    float b = 1;
    float e = 1;
    for (uint i = 0; i < max(f[0].octaves, 1u); i++)
    {
        light += a * cloudPhase(f, c.type, cosTheta, e) * exp(-b * tauSun);
        a *= f[0].msScattering;
        b *= f[0].msExtinction;
        e *= f[0].msPhase;
    }
    if (f[0].powder > 0)
    {
        light *= lerp(1.0, 2 * (1 - exp(-2 * tauSun)), f[0].powder); // Schneider 2015 (art: off when physical)
    }
    light *= sun;
    // The diffuse light deep inside: the sun's and the sky's light on the layer's top, diffused down to here, and the
    // ground's from below (two-stream), isotropic: an isotropic field of irradiance E has fluence 4E, so it scatters
    // sigma_s 4E / 4 pi. The octaves above carry the first orders; this the many after.
    float above = c.extinction * (1 - c.height) * c.thickness * f[0].ambientDepth;
    float below = c.extinction * c.height * c.thickness * f[0].ambientDepth;
    float3 top = sun * max(f[0].sunDirection.y, 0) + f[0].skyIlluminance;
    light += (top * diffuseTransmittance(above) + ground * diffuseTransmittance(below)) / PI;
    light += flashLight(f, p, c.extinction);
    return light;
}

/** The ray's distance to altitudes `lo` and `hi` from altitude `y` along `dy`: the slab it crosses (empty: x > y). */
float2 slab(float y, float dy, float lo, float hi)
{
    if (abs(dy) < 1.0e-6)
    {
        return y >= lo && y <= hi ? float2(0, 3.4e38) : float2(1, 0);
    }
    float a = (lo - y) / dy;
    float b = (hi - y) / dy;
    return float2(max(min(a, b), 0), max(a, b));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void marchMain(uint3 id : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    if (any(id.xy >= size))
    {
        return;
    }
    CloudFrame* f = push.frame;
    uint2 full = uint2(push.fullSize & 0xffff, push.fullSize >> 16);
    float2 uv = (float2(id.xy) + 0.5) / float2(size);
    // The farthest scene depth under the pixel's footprint (reverse depth: the smallest value).
    float2 footprint = float2(full) / float2(size);
    float sceneDepth = 1;
    for (uint k = 0; k < 4; k++)
    {
        float2 corner = (float2(id.xy) + float2(k & 1, k >> 1) * 0.5 + 0.25) * footprint;
        uint2 texel = min(uint2(corner), full - 1);
        sceneDepth = min(sceneDepth, textures[push.depth].Load(int3(texel, 0)).r);
    }
    float2 ndc = uv * 2 - 1;
    float4 farPoint = mul(push.view[0].inverseViewProjection, float4(ndc, 0.5, 1));
    float3 dir = normalize(farPoint.xyz / farPoint.w);
    float limit = push.range;
    if (sceneDepth > 0)
    {
        float4 hit = mul(push.view[0].inverseViewProjection, float4(ndc, sceneDepth, 1));
        limit = min(limit, length(hit.xyz / hit.w));
    }
    float2 span = slab(push.camera.y, dir.y, 0, f[0].maxAltitude);
    span.y = min(span.y, limit);

    float3 light = 0;
    float transmittance = 1;
    float weightedDepth = 0;
    float weight = 0;
    if (span.x < span.y)
    {
        float3 toSun = f[0].sunDirection;
        float cosTheta = dot(dir, toSun);
        float rainPhase = henyeyGreenstein(cosTheta, f[0].rainForward);
        float across = span.y - span.x;
        float coarse = max(across / float(push.steps), 20.0);
        float fine = coarse * 0.35;
        bool detail = (push.flags & MARCH_DETAIL) != 0;
        bool fineMode = span.x < push.near;
        float t = span.x + jitter(id.xy, push.frameIndex) * (fineMode ? fine : coarse);
        uint empty = 0;
        uint limitSteps = push.steps * 4;
        for (uint i = 0; i < limitSteps && t < span.y && transmittance > 0.003; i++)
        {
            bool nearCamera = t < push.near;
            float step = fineMode || nearCamera ? fine : coarse;
            float dt = min(step, span.y - t);
            float3 p = push.camera + dir * (t + 0.5 * dt);
            CloudPoint c = cloudAt(f, float3(p.x, 0, p.z), p.y, detail && (fineMode || nearCamera), push.shape,
                push.detail, push.curl);
            if (!fineMode && !nearCamera && c.extinction > 0)
            {
                // Cloud met at coarse steps: back up and go on finely, with detail.
                fineMode = true;
                empty = 0;
                t = max(span.x, t - coarse * 0.5);
                continue;
            }
            if (fineMode && c.extinction <= 0)
            {
                if (++empty > 8)
                {
                    fineMode = false;
                }
            }
            else if (c.extinction > 0)
            {
                empty = 0;
            }
            float rain = (push.flags & MARCH_RAIN) != 0 ? c.rain : 0;
            float sigma = c.extinction + rain;
            if (sigma > 0)
            {
                float3 atmosphere = sunTransmittance(f, p.y);
                float3 sun = f[0].sunIlluminance * atmosphere;
                float3 source = 0;
                if (c.extinction > 0)
                {
                    float3 ground = 0;
                    if ((push.flags & MARCH_GROUND) != 0)
                    {
                        // The ground's light under the clouds' column: the sun's and the sky's, diffused through it.
                        float column = push.shadow != 0 ? columnDepth(f, push.shadow, float3(p.x, 0, p.z)) : 0;
                        ground = f[0].groundAlbedo * (f[0].sunIlluminance * sunTransmittance(f, 0)
                            * max(toSun.y, 0) + f[0].skyIlluminance) * diffuseTransmittance(column);
                    }
                    float tau = sunDepth(f, p, toSun, detail && (fineMode || nearCamera));
                    float albedo = 1 - f[0].types[c.type].absorption;
                    source += c.extinction * albedo * cloudLight(f, c, p, cosTheta, tau, sun, ground);
                }
                if (rain > 0)
                {
                    float shade = push.shadow != 0 ? cloudShadow(f, push.shadow, p) : 1;
                    source += rain * (sun * shade * rainPhase
                        + f[0].skyIlluminance * lerp(0.1, 1.0, shade) / (4 * PI));
                }
                float stepTransmittance = exp(-sigma * dt);
                light += transmittance * (source - source * stepTransmittance) / sigma;
                float before = transmittance;
                transmittance *= stepTransmittance;
                weightedDepth += (before - transmittance) * (t + 0.5 * dt);
                weight += before - transmittance;
            }
            t += step;
        }
        if (transmittance <= 0.003)
        {
            light /= 1 - transmittance; // what was left goes the way of the rest, rather than to the scene
            transmittance = 0;
        }
    }
    float mean = weight > 1.0e-4 ? weightedDepth / weight : limit;
    if (weight > 0)
    {
        // The aerial perspective between the camera and the clouds (scene behind: its own, applied with its colour).
        float3 ap = aerialTransmittance(f, push.camera.y, push.camera.y + dir.y * mean, mean);
        light = light * ap + f[0].apRadiance * (1 - ap) * (1 - transmittance);
    }
    images[push.output][id.xy] = float4(min(light / OUTPUT_SCALE, 60000.0), transmittance);
    images[push.depthOutput][id.xy] = float4(mean, limit, 0, 0);
}
