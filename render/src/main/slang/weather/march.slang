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
/** render.clouds.debug's bits: the clouds without the air in front of them, without the sky's light, without the sun's
 * diffused light from above, without the sun's octaves. */
static const uint DEBUG_NO_AERIAL = 1;
static const uint DEBUG_NO_SKY = 2;
static const uint DEBUG_NO_DIFFUSE = 4;
static const uint DEBUG_NO_DIRECT = 8;
/** render.clouds.airShadow (not a debugging bit): the clouds' shadow on the air in front of them. */
static const uint AIR_SHADOW = 32;
/**
 * An optical depth towards the sun beyond which the cone march stops: its octaves' last (b^2 = 1/4) leaves e^-12 of
 * the sun; the rest of the way is the shadow map's from where it stopped, as after the whole cone.
 */
static const float SUN_OPAQUE = 48;
/** Empty-space skipping over the occupancy maps. */
static const uint MARCH_SKIP = 16;
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
 * Where clouds can be over plane point `plane` (occupancy.slang): the lowest base and highest top over its block, the
 * near map's (the union with the far map's across the near map's outer tenth, where the clouds blend the two), else the
 * far map's; and the block's extent in plane coordinates (min x, min z, max x, max z). Outside both maps: nothing, to
 * the far map's edge.
 */
float2 occupancyAt(CloudFrame* f, float2 plane, out float4 box)
{
    float2 occ = float2(60000, 0);
    box = f[0].farBounds;
    bool inFar = all(plane > f[0].farBounds.xy) && all(plane < f[0].farBounds.zw);
    float near = within(plane, f[0].nearBounds, 0.1 * f[0].nearExtent);
    if (inFar && near < 1)
    {
        uint size = f[0].farSize / f[0].farBlock;
        float2 uv = f[0].farAnchor + plane / f[0].farExtent;
        float2 cell = floor(uv * float(size));
        occ = textures[f[0].farOccupancy].Load(int3(clamp(int2(cell), int2(0, 0), int2(size - 1, size - 1)), 0)).rg;
        box = float4((cell / float(size) - f[0].farAnchor) * f[0].farExtent,
            ((cell + 1) / float(size) - f[0].farAnchor) * f[0].farExtent);
    }
    if (near > 0)
    {
        uint size = f[0].nearSize / f[0].nearBlock;
        float2 uv = f[0].nearAnchor + plane / f[0].nearExtent;
        float2 cell = floor(uv * float(size));
        float2 v = textures[f[0].nearOccupancy].Load(int3(clamp(int2(cell), int2(0, 0), int2(size - 1, size - 1)),
            0)).rg;
        occ = near < 1 ? float2(min(occ.x, v.x), max(occ.y, v.y)) : v;
        box = float4((cell / float(size) - f[0].nearAnchor) * f[0].nearExtent,
            ((cell + 1) / float(size) - f[0].nearAnchor) * f[0].nearExtent);
    }
    return occ;
}

/**
 * Where the ray (from the camera along `dir`, at `t` now, at point `p` of altitude `height`) next may meet cloud, given
 * its block's base and top `occ` and extent `box`: where it leaves the block, or sooner where its altitude over the
 * curved surface comes down to the top (from above) or up to the base (from below), whichever is first.
 */
float skipTo(float3 dir, float t, float3 p, float height, float2 occ, float4 box, float R)
{
    float2 leave = float2(dir.x > 0 ? (box.z - p.x) / dir.x : dir.x < 0 ? (box.x - p.x) / dir.x : 3.4e38,
        dir.z > 0 ? (box.w - p.z) / dir.z : dir.z < 0 ? (box.y - p.z) / dir.z : 3.4e38);
    float next = t + max(min(leave.x, leave.y), 0.0);
    if (height > occ.y)
    {
        float2 s = shell(push.camera.y, dir.y, occ.y, R); // below the top between these
        if (s.x <= s.y && s.x > t)
        {
            next = min(next, s.x);
        }
    }
    else if (height < occ.x)
    {
        float2 s = shell(push.camera.y, dir.y, occ.x, R);
        next = min(next, max(s.y, t));
    }
    return next;
}

/**
 * The optical depth towards the sun from `p` (from the anchor) at cloud point `here`: `steps` steps of growing length
 * over the cone's length (`push.lightStep` times 2^near - 1, near the near light steps: fewer steps far away cover the
 * same cone in longer steps), then the rest of the way to the layer's top at the column's mean extinction (Schneider's
 * long step: a thick cloud's top stays dark below however short the cone).
 */
float sunDepth(CloudFrame* f, float3 p, float3 toSun, float detail, CloudPoint here, float R, uint steps)
{
    float depth = 0;
    float travelled = 0;
    uint near = (push.counts >> 16) & 0xff;
    float step = push.lightStep * (exp2(float(near)) - 1) / max(exp2(float(steps)) - 1, 1);
    for (uint k = 0; k < steps; k++)
    {
        float3 q = p + toSun * (travelled + 0.5 * step);
        CloudPoint c = cloudAt(f, float3(q.x, 0, q.z), altitude(q, R), k < 2 ? detail : 0, shapeSlot(),
            detailSlot(), curlSlot());
        depth += c.extinction * step;
        travelled += step;
        step *= 2;
        if (depth > SUN_OPAQUE)
        {
            break; // deep in a cloud: the rest of the cone changes nothing the direct light uses
        }
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
    for (uint i = 0; i < ((f[0].debug & DEBUG_NO_DIRECT) != 0 ? 0 : max(f[0].octaves, 1u)); i++)
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
    // In a storm's tower the sky's light also comes in from its nearest side (c.surface: the optical depth to it), as
    // much as that side sees of the sky (its irradiance from a sky above, (1 + n.y) / 2), the ground's light as much
    // as it sees of the ground: a tower's shaded flank is lit by the sky beside it, its flat base by the ground.
    float3 sunTop = (f[0].debug & DEBUG_NO_DIFFUSE) != 0 ? 0 : sun * max(f[0].sunDirection.y, 0);
    float side = diffuseTransmittance(c.surface);
    float3 skyLight = (f[0].debug & DEBUG_NO_SKY) != 0 ? 0 : f[0].skyIlluminance;
    light += (sunTop * diffuseTransmittance(above) + skyLight * max(diffuseTransmittance(above),
        c.skyView * side) + ground * max(diffuseTransmittance(below), (1 - c.skyView) * side)) / PI;
    light += flashLight(f, p, c.extinction);
    return light;
}

/**
 * The fraction of the sunlight reaching the air along the ray from the camera to `distance` metres (along `dir`): the
 * clouds' transmittance towards the sun at eight points along it, weighted by the air's density there (the in-scatter
 * comes from where the air is).
 */
float airInSunlight(CloudFrame* f, float3 dir, float distance, float R)
{
    float sum = 0;
    float weights = 0;
    for (uint i = 0; i < 8; i++)
    {
        float3 p = push.camera + dir * (distance * (float(i) + 0.5) / 8);
        float w = exp(-max(altitude(p, R), 0.0) / 8000);
        sum += w * cloudShadow(f, shadowSlot(), p);
        weights += w;
    }
    return sum / max(weights, 1.0e-6);
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
        // Level of detail without steps: the detail noise fades out over the band before detailFar, and the light
        // march's far steps begin at a distance dithered over the band around lightFar (the temporal accumulation
        // averages the two), so a cloud crossing either distance changes continuously.
        float band = f[0].lodBand;
        float lightSwitch = push.lightFar * (1 + band * (2 * frac(jitter(id.xy, push.frameIndex) * 7.31) - 1));
        bool fineMode = span.x < push.near;
        float first = clamp(0.02 * span.x, 30.0, widest);
        float t = span.x + jitter(id.xy, push.frameIndex) * (fineMode ? push.fineRatio * first : first);
        uint empty = 0;
        bool lastEmpty = true;
        // The light march once every `lightEvery` cloud samples (flags' bits 8-11), its optical depth reused between:
        // neighbouring samples tens of metres apart see the sun through nearly the same cloud.
        uint lightEvery = max((push.flags >> 8) & 0xf, 1u);
        uint sinceLight = lightEvery;
        float lastTau = 0;
        uint limitSteps = coarseSteps * 6;
        bool skip = (push.flags & MARCH_SKIP) != 0;
        float4 box = float4(1, 1, 0, 0); // the occupancy block the ray is over (none yet)
        float2 occ = 0;
        float phase = jitter(id.xy, push.frameIndex);
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
            // Near the camera (fine steps from the start) too, while the last sample found no cloud.
            if (skip && (!fineMode || (nearCamera && lastEmpty)))
            {
                // Empty space: over a block where no cloud can be at this altitude, on to where one may be (the
                // pixel's jitter keeps the samples after it from lining up at the blocks' edges). The block's values
                // are kept while the ray stays over it.
                if (any(p.xz < box.xy) || any(p.xz > box.zw))
                {
                    occ = occupancyAt(f, p.xz, box);
                }
                if (height > occ.y || height < occ.x)
                {
                    float next = skipTo(dir, t + 0.5 * dt, p, height, occ, box, R);
                    if (next > t + dt)
                    {
                        // Never backwards: the samples after the jump are offset by the pixel's jitter within a step.
                        t = max(next - phase * step, t + dt);
                        continue;
                    }
                }
            }
            float detailed = detail && (fineMode || nearCamera)
                ? 1 - smoothstep(push.detailFar * (1 - band), push.detailFar, t) : 0;
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
            lastEmpty = c.extinction <= 0;
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
                        // The ground's light under the clouds' column: the sun's and the sky's, diffused through it
                        // (the ground a base sees is kilometres around, not the point under it in a tower's shadow).
                        float column = shadowSlot() != 0 ? columnDepth(f, shadowSlot(), float3(p.x, 0, p.z)) : 0;
                        ground = f[0].groundAlbedo * (groundSunlight(f, p) * max(toSun.y, 0) + f[0].skyIlluminance)
                            * diffuseTransmittance(column);
                    }
                    // Far samples cover few pixels: fewer light steps beyond lightFar (the same cone).
                    uint lightSteps = t > lightSwitch ? push.counts >> 24 : (push.counts >> 16) & 0xff;
                    float tau = lastTau;
                    if (sinceLight >= lightEvery)
                    {
                        // In a storm's tower the cone's first two steps and the long step (it is opaque within
                        // them): the same cone in fewer steps, as far away.
                        tau = sunDepth(f, p, toSun, detailed, c, R, c.surface < 1.0e8 ? min(lightSteps, 2u)
                            : lightSteps);
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
                    float slant = shadowSlot() != 0 ? shadowDepth(f, shadowSlot(), p) : 0;
                    float shade = exp(-min(slant, 80.0));
                    float column = shadowSlot() != 0 ? columnDepth(f, shadowSlot(), p) : 0;
                    // The light diffused to the rain: the sun's through the clouds towards it, the sky's through
                    // the column overhead and from the horizon around it (a fifth of the sky's light comes from
                    // below 12 degrees, which a storm overhead does not hide, but a deck around does).
                    float mu = max(toSun.y, 0);
                    float overhead = diffuseTransmittance(column);
                    // (The sun's diffused light: the two-stream transmittance less the direct beam's, which the
                    // phase function above scatters.)
                    float horizon = 0.2 * (1 - channelsAt(f, p.xz).x); // a deck hides the horizon too
                    float3 diffused = groundSunlight(f, p) * mu * max(diffuseTransmittance(slant * max(mu, 0.05))
                        - shade, 0.0) + f[0].skyIlluminance * (overhead + horizon * (1 - overhead));
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
            // The air in front of the clouds lies partly in their shadow (under a storm's anvil, beside its tower):
            // it scatters only the sunlight that reaches it (as the cloud shadows on the atmosphere of Hillaire's
            // sky do in Unreal Engine: all of its in-scattering, multiple scattering too, comes from the sun). Off by
            // default: effects-atmosphere's aerial perspective on the scene behind has no cloud shadows, and under a
            // deck the ground's unshadowed haze at the horizon then shone brighter than the deck's shadowed one.
            if (shadowSlot() != 0 && (f[0].debug & AIR_SHADOW) != 0)
            {
                front *= airInSunlight(f, dir, mean, R);
            }
            light = (f[0].debug & DEBUG_NO_AERIAL) != 0 ? light : light * through + front * (1 - transmittance);
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
