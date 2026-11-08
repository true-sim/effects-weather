// The clouds' and rain's light along each view ray, marched at the march's resolution (VolumetricClouds): from the
// camera (or where the ray enters the cloud slab) to the scene's depth or the march's range, Schneider's two-speed
// march (coarse steps on the base shape until it meets cloud, then fine steps with detail, back to coarse after empty
// samples), a cone light march towards the sun per sample, Wrenninge's octaves of scattering, the sky's and the
// ground's ambient light by height, the flashes' glow, rain below the bases, Hillaire 2016's energy-conserving step
// and the aerial perspective at the clouds' transmittance-weighted depth. Writes the light (scaled by OUTPUT_SCALE)
// and transmittance, and the mean depth and the depth it marched to (for reprojection and upsampling).
//
// The clouds lie on the planet (their altitude above its curved surface, altitude()), so a deck reaches down to the
// horizon as a real one does, and with effects-atmosphere (MARCH_TABLES) every sample's sunlight comes through its
// transmittance table with the planet's shadow (Hillaire 2020; Bruneton's parameterisation), and the air in front of
// the clouds is its aerial perspective volume, continued beyond the volume's range by its own integration.
import bindless;
import meshlets.common;
import weather.clouds;
import atmosphere.atmosphere;

struct MarchPush
{
    View* view;
    CloudFrame* frame;
    float3 camera;       // the camera from the frame's anchor: x, z in the ground plane, y its altitude
    uint outputs;        // storage slots: RGBA16F light / OUTPUT_SCALE and transmittance; RG32F mean depth and the
                         // depth marched to << 16
    uint depth;          // sampled slot: the scene's depth (reverse), full resolution
    uint noise;          // sampled slots: the base shape volume, the detail volume << 16
    uint maps;           // sampled slots: the curl texture, the Beer shadow map << 16 (0: none)
    uint aerial;         // sampled slots: the aerial perspective volume's light, its transmittance << 16 (NO_AERIAL)
    uint size;           // the march's size: width, height in the high 16 bits
    uint fullSize;       // the scene's
    uint frameIndex;
    uint counts;         // coarse steps across the slab, light steps << 16, light steps beyond lightFar << 24
    float range;         // metres the march reaches at most
    float near;          // metres from the camera marched finely with detail from the start (fly-through)
    float lightFar;      // metres from the camera beyond which the light march takes its far steps
    float lightStep;     // the first light step, metres; each next one twice as long
    uint flags;          // MARCH_*, the light march's interval << 8
    float outputScale;   // the light in the outputs is this much smaller than the radiance (scene units)
    float apFar;         // km: the aerial perspective volume's range
    uint sparse;         // the interleave s (1, 2, 4: one pixel of each s x s block a frame), its phase x << 8, y << 16
    float fineRatio;     // fine steps over coarse steps (inside clouds)
    float opaque;        // the transmittance at which a ray stops: what lies behind no longer shows
    float detailFar;     // metres from the camera beyond which the detail noise is left out (sub-pixel there)
    AtmosphereFrame* atmosphere; // effects-atmosphere's frame (MARCH_TABLES)
};



[[vk::push_constant]]
ConstantBuffer<MarchPush> push;

// The push's packed slots.
uint marchOutput() { return push.outputs & 0xffff; }
uint marchDepthOutput() { return push.outputs >> 16; }
uint shapeSlot() { return push.noise & 0xffff; }
uint detailSlot() { return push.noise >> 16; }
uint curlSlot() { return push.maps & 0xffff; }
uint shadowSlot() { return push.maps >> 16; }
uint apScatteredSlot() { return push.aerial & 0xffff; }
uint apTransmittedSlot() { return push.aerial >> 16; }

/** Detail noise in fine steps (else the base shape only). */
static const uint MARCH_DETAIL = 1;
/** The ambient's ground bounce under the clouds' shadow. */
static const uint MARCH_GROUND = 2;
/** Rain below the bases. */
static const uint MARCH_RAIN = 4;
/** effects-atmosphere's tables light the clouds and lie in front of them. */
static const uint MARCH_TABLES = 8;
/** No aerial perspective volume (the atmosphere's off): its slot. */
static const uint NO_AERIAL = 0xffff;

/** The planet's radius, metres (the analytic atmosphere's, or effects-atmosphere's). */
float planetRadius(CloudFrame* f)
{
    return (push.flags & MARCH_TABLES) != 0 ? push.atmosphere[0].bottomRadius * 1000 : f[0].planetRadius;
}

/**
 * The altitude above the planet's surface of point p (from the anchor: x, z on the plane tangent at the camera's
 * ground point, y the height above that plane): y + d^2 / 2R, d its distance from the camera's ground point (the
 * surface falls away from the tangent plane; exact to d^4 / R^3, a millimetre at 200 km).
 */
float altitude(float3 p, float R)
{
    float2 d = p.xz - push.camera.xz;
    return p.y + dot(d, d) / (2 * (R + max(p.y, 0)));
}

/** The sun's illuminance (scene units, RGB) at point p (from the anchor) of altitude height, through the atmosphere. */
float3 sunlight(CloudFrame* f, float3 p, float height)
{
    if ((push.flags & MARCH_TABLES) != 0)
    {
        AtmosphereFrame* a = push.atmosphere;
        float3 position = planetPosition(a, p - push.camera);
        return f[0].sunIlluminance * transmittanceToLight(a, position, f[0].sunDirection, a[0].lightRadius0);
    }
    return f[0].sunIlluminance * sunTransmittance(f, height);
}

/** The sun's illuminance at the ground below point p. */
float3 groundSunlight(CloudFrame* f, float3 p)
{
    if ((push.flags & MARCH_TABLES) != 0)
    {
        AtmosphereFrame* a = push.atmosphere;
        float3 position = planetPosition(a, float3(p.x, 0, p.z) - push.camera);
        position = normalize(position) * (a[0].bottomRadius + 0.002); // on the ground under it
        return f[0].sunIlluminance * transmittanceToLight(a, position, f[0].sunDirection, a[0].lightRadius0);
    }
    return f[0].sunIlluminance * sunTransmittance(f, 0);
}

/**
 * The air between the camera and the clouds at metres along dir: effects-atmosphere's aerial perspective volume (as
 * its media hook reads it: aerial.slang), continued past the volume's range by integrating the rest of the way.
 */
void aerialPerspective(float3 dir, float metres, out float3 through, out float3 light)
{
    AtmosphereFrame* a = push.atmosphere;
    float3 camera = cameraPosition(a);
    through = 1;
    light = 0;
    float km = metres * 0.001;
    if (apScatteredSlot() != NO_AERIAL)
    {
        SkyFrame frame = skyFrame(a, camera);
        float2 uv = skyViewUv(frame, dir);
        uint w;
        uint h;
        uint d;
        volumes[apScatteredSlot()].GetDimensions(w, h, d);
        uv.y = clamp(uv.y, 0.5 / float(h), 1 - 0.5 / float(h));
        float s = sqrt(min(km / push.apFar, 1)) * float(d) - 1;
        float weight = 1;
        if (s < 0)
        {
            weight = s + 1;
            s = 0;
        }
        float3 at = float3(uv, (s + 0.5) / float(d));
        light = volumes[apScatteredSlot()].SampleLevel(samplers[LINEAR_REPEAT], at, 0).rgb * weight;
        through = lerp(float3(1), volumes[apTransmittedSlot()].SampleLevel(samplers[LINEAR_REPEAT], at, 0).rgb,
            weight);
        if (km > push.apFar)
        {
            segment(a, camera, dir, push.apFar, km, 6, light, through);
        }
        return;
    }
    segment(a, camera, dir, 0, km, 12, light, through);
}

/**
 * The ray's distances where its altitude above the curved surface, alt(t) = y + t dy + t^2 (1 - dy^2) / 2R, equals
 * h: below h between them (none: x > y).
 */
float2 shell(float y, float dy, float h, float R)
{
    float a = (1 - dy * dy) / (2 * R);
    float b = dy;
    float c = y - h;
    if (a < 1.0e-12)
    {
        if (abs(b) < 1.0e-9)
        {
            return c <= 0 ? float2(-3.4e38, 3.4e38) : float2(1, 0);
        }
        float t = -c / b;
        return b > 0 ? float2(-3.4e38, t) : float2(t, 3.4e38);
    }
    float disc = b * b - 4 * a * c;
    if (disc < 0)
    {
        return float2(1, 0);
    }
    float q = sqrt(disc);
    return float2((-b - q) / (2 * a), (-b + q) / (2 * a));
}


/**
 * The optical depth towards the sun from `p` (from the anchor) at cloud point `here`: `steps` steps of growing length
 * over the cone's length (`push.lightStep` times 2^near - 1, near the near light steps: fewer steps far away cover the
 * same cone in longer steps), then the rest of the way to the layer's top at the column's mean extinction (Schneider's
 * long step: a thick cloud's top stays dark below however short the cone).
 */
float sunDepth(CloudFrame* f, float3 p, float3 toSun, bool detail, CloudPoint here, float R, uint steps)
{
    float depth = 0;
    float travelled = 0;
    uint near = (push.counts >> 16) & 0xff;
    float step = push.lightStep * (exp2(float(near)) - 1) / max(exp2(float(steps)) - 1, 1);
    for (uint k = 0; k < steps; k++)
    {
        float3 q = p + toSun * (travelled + 0.5 * step);
        CloudPoint c = cloudAt(f, float3(q.x, 0, q.z), altitude(q, R), detail && k < 2, shapeSlot(), detailSlot(),
            curlSlot());
        depth += c.extinction * step;
        travelled += step;
        step *= 2;
    }
    // Beyond the cone, the rest of the way to the sun: the Beer shadow map's optical depth from where the cone ended
    // (the clouds' real shapes: a tower's sunlit side is lit), else the layer's mean extinction to its top.
    float3 end = p + toSun * travelled;
    if (shadowSlot() != 0)
    {
        return depth + shadowDepth(f, shadowSlot(), end);
    }
    float rise = max(toSun.y, 0.05);
    float left = max(here.top - altitude(end, R), 0) / rise;
    return depth + here.columnSigma * left;
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
        light += a * pointPhase(c, cosTheta, e) * exp(-b * tauSun);
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
    // The optical depths to the layer's top and base at the column's mean extinction (the type's, not this sample's:
    // a wisp at a thick cloud's base is as deep below its top as the cloud is).
    // A point is no deeper below the light from above than it is from the sun (a tower's side is near its surface):
    // only while the sun is up, where the depth towards it says where the surface is (below the horizon the march
    // towards it crosses nothing and would light the inside of every cloud as its top).
    float vertical = c.columnSigma * (1 - c.height) * c.thickness;
    float sideways = tauSun * max(f[0].sunDirection.y, 0.05);
    float above = lerp(vertical, min(vertical, sideways), saturate(f[0].sunDirection.y * 10)) * f[0].ambientDepth;
    float below = c.columnSigma * c.height * c.thickness * f[0].ambientDepth;
    float3 top = sun * max(f[0].sunDirection.y, 0) + f[0].skyIlluminance;
    light += (top * diffuseTransmittance(above) + ground * diffuseTransmittance(below)) / PI;
    light += flashLight(f, p, c.extinction);
    return light;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void marchMain(uint3 dispatch : SV_DispatchThreadID)
{
    uint2 size = uint2(push.size & 0xffff, push.size >> 16);
    // Interleaved: this frame marches one pixel of each s x s block (the phase's); the temporal pass fills the rest
    // from the history, so every pixel is new every s^2 frames (temporal amortization).
    uint s = push.sparse & 0xff;
    uint3 id = uint3(dispatch.xy * s + uint2((push.sparse >> 8) & 0xff, (push.sparse >> 16) & 0xff), 0);
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
    // Where the ray is below the highest top over the curved surface (the scene's depth ends it at the ground).
    float R = planetRadius(f);
    float2 span = shell(push.camera.y, dir.y, f[0].maxAltitude, R);
    span.x = max(span.x, 0);
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
        uint coarseSteps = push.counts & 0xffff;
        float widest = max(across / float(coarseSteps), 30.0);
        bool detail = (push.flags & MARCH_DETAIL) != 0;
        bool fineMode = span.x < push.near;
        float first = clamp(0.02 * span.x, 30.0, widest);
        float t = span.x + jitter(id.xy, push.frameIndex) * (fineMode ? push.fineRatio * first : first);
        uint empty = 0;
        // The light march once every `lightEvery` cloud samples (flags' bits 8-11), its optical depth reused between:
        // neighbouring samples tens of metres apart see the sun through nearly the same cloud.
        uint lightEvery = max((push.flags >> 8) & 0xf, 1u);
        uint sinceLight = lightEvery;
        float lastTau = 0;
        uint limitSteps = coarseSteps * 6;
        for (uint i = 0; i < limitSteps && t < span.y && transmittance > push.opaque; i++)
        {
            bool nearCamera = t < push.near;
            // Steps grow with the distance (2 % of it), never past the slab's share: fine near, coarse far.
            float coarse = clamp(0.02 * t, 30.0, widest);
            float fine = coarse * push.fineRatio;
            float step = fineMode || nearCamera ? fine : coarse;
            float dt = min(step, span.y - t);
            float3 p = push.camera + dir * (t + 0.5 * dt);
            float height = altitude(p, R);
            bool detailed = detail && (fineMode || nearCamera) && t < push.detailFar;
            CloudPoint c = cloudAt(f, float3(p.x, 0, p.z), height, detailed, shapeSlot(),
                detailSlot(), curlSlot());
            if (!fineMode && !nearCamera && c.extinction > 0)
            {
                // Cloud met at coarse steps: back up and go on finely, with detail.
                fineMode = true;
                empty = 0;
                t = max(span.x, t - coarse * 0.5);
                continue;
            }
            if (c.extinction <= 0)
            {
                sinceLight = lightEvery; // out of the cloud: the next cloud sample marches its light anew
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
                float3 sun = sunlight(f, p, height);
                float3 emitted = 0;
                if (c.extinction > 0)
                {
                    float3 ground = 0;
                    if ((push.flags & MARCH_GROUND) != 0)
                    {
                        // The ground's light under the clouds' column: the sun's and the sky's, diffused through it.
                        float column = shadowSlot() != 0 ? columnDepth(f, shadowSlot(), float3(p.x, 0, p.z)) : 0;
                        ground = f[0].groundAlbedo * (groundSunlight(f, p) * max(toSun.y, 0) + f[0].skyIlluminance)
                            * diffuseTransmittance(column);
                    }
                    // Far samples cover few pixels: fewer light steps beyond lightFar (the same cone).
                    uint lightSteps = t > push.lightFar ? push.counts >> 24 : (push.counts >> 16) & 0xff;
                    float tau = lastTau;
                    if (sinceLight >= lightEvery)
                    {
                        tau = sunDepth(f, p, toSun, detailed, c, R, lightSteps);
                        lastTau = tau;
                        sinceLight = 0;
                    }
                    sinceLight++;
                    float albedo = 1 - c.absorption;
                    emitted += c.extinction * albedo * cloudLight(f, c, p, cosTheta, tau, sun, ground);
                }
                if (rain > 0)
                {
                    // Rain under the cloud: the sun through the clouds' shadow, and the light diffused through them.
                    float shade = shadowSlot() != 0 ? cloudShadow(f, shadowSlot(), p) : 1;
                    float column = shadowSlot() != 0 ? columnDepth(f, shadowSlot(), p) : 0;
                    float3 diffused = (groundSunlight(f, p) * max(toSun.y, 0) + f[0].skyIlluminance)
                        * diffuseTransmittance(column);
                    emitted += rain * (sun * shade * rainPhase + diffused / PI);
                }
                float stepTransmittance = exp(-sigma * dt);
                light += transmittance * (emitted - emitted * stepTransmittance) / sigma;
                float before = transmittance;
                transmittance *= stepTransmittance;
                weightedDepth += (before - transmittance) * (t + 0.5 * dt);
                weight += before - transmittance;
            }
            t += step;
        }
        if (transmittance <= push.opaque)
        {
            transmittance = 0; // the scene behind is hidden; the light stays what was integrated (no energy added)
        }
    }
    float mean = weight > 1.0e-4 ? weightedDepth / weight : limit;
    if (weight > 0)
    {
        // The aerial perspective between the camera and the clouds: what they hide of the scene's own (its air in
        // front of them too) comes back as the air's light in front of them, (1 - T) L_front.
        if ((push.flags & MARCH_TABLES) != 0)
        {
            float3 through;
            float3 front;
            aerialPerspective(dir, mean, through, front);
            light = light * through + front * (1 - transmittance);
        }
        else
        {
            float3 ap = aerialTransmittance(f, push.camera.y, altitude(push.camera + dir * mean, R), mean);
            light = light * ap + f[0].apRadiance * (1 - ap) * (1 - transmittance);
        }
    }
    images[marchOutput()][id.xy] = float4(min(light / push.outputScale, 60000.0), transmittance);
    images[marchDepthOutput()][id.xy] = float4(mean, limit, 0, 0);
}
