// The clouds over the scene's colour, in place (VolumetricClouds, SkyboxClouds): scene * T + L.
//
// compositeMain upsamples the march's light and transmittance to the scene's pixels, joint-bilaterally on depth
// (Kopf et al. 2007; the four nearest march texels weighted by how well the depth each marched to matches this
// pixel's), so clouds stop at mountains and aircraft.
//
// skyboxMain is the cheap 2D tier: no noise volumes and no march, the weather map's coverage and type on the layer of
// each type, intersected once per pixel: the coverage as the fraction of the sky covered, the layer a slab lit
// analytically (the two-stream slab's diffuse reflection or transmission, the sun's single scattering with the
// approximate Mie phase), behind it the scene by the direct transmittance exp(-tau). The storm cells' towers are
// solids (columns under domes, intersected analytically) lit as a thick cloud's surface, and their anvils a slab at
// their middle, composited with the layer nearest first.
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
        float2 depths = textures[push.cloudDepth].Load(int3(q, 0)).rg;
        float marched = depths.y;
        float agreement = 1 / (1.0e-3 + abs(min(distance, 1.0e7) - min(marched, 1.0e7)) / max(min(distance, 1.0e7), 1.0));
        float w = (bilinear + 1.0e-3) * agreement;
        float4 c = textures[push.clouds].Load(int3(q, 0));
        // A march texel straddling the horizon marched past this pixel's surface (to the sky beside it): clouds
        // behind the surface are hidden by it, so that texel brings none here.
        if (depths.x > 1.02 * distance + 50)
        {
            c = float4(0, 0, 0, 1);
        }
        sum += w * c;
        total += w;
    }
    float4 clouds = sum / max(total, 1.0e-6);
    float4 scene = images[push.color][id.xy];
    if ((push.frame[0].debug & 16) != 0)
    {
        // Debugging: the nearest march texel as it is (its light over the scene's black, its transmittance in red).
        int2 q = clamp(int2(uv * float2(marchSize)), int2(0, 0), int2(marchSize) - 1);
        float4 c = textures[push.clouds].Load(int3(q, 0));
        float2 d = textures[push.cloudDepth].Load(int3(q, 0)).rg;
        images[push.color][id.xy] = float4(c.a * 1000, log2(max(d.y, 1)) * 50, log2(max(d.x, 1)) * 50, scene.a);
        return;
    }
    float3 color = scene.rgb * clouds.a + clouds.rgb * push.scale;
    images[push.color][id.xy] = float4(min(color, 65000.0), scene.a);
}

/**
 * The two-stream slab of optical depth `tau` at distance `t` along `dir` (its middle at altitude `middle`), covering
 * `cover` of the sky there: its light (aerial perspective applied) and transmittance.
 */
float4 slabLight(CloudFrame* f, float3 dir, float t, float middle, float tau, float cover, float phase, float top)
{
    float y = push.camera.y;
    float3 toSun = f[0].sunDirection;
    float mu = max(toSun.y, 0.05);
    float view = tau / max(abs(dir.y), 0.05);
    float direct = exp(-view);
    float diffuse = diffuseTransmittance(tau);
    float3 sun = f[0].sunIlluminance * sunTransmittance(f, top);
    float3 down = sun * max(toSun.y, 0) + f[0].skyIlluminance;
    bool above = y > middle;
    float3 slab = down * (above ? 1 - diffuse : max(diffuse - exp(-tau / mu), 0)) / PI;
    float single = phase * exp(-tau / mu);
    float3 cloud = slab + sun * single * (1 - direct);
    float3 light = cover * cloud;
    float transmittance = 1 - cover + cover * direct;
    if (push.scale > 0)
    {
        float3 ap = aerialTransmittance(f, y, middle, t);
        light = light * ap + f[0].apRadiance * (1 - ap) * (1 - transmittance);
    }
    return float4(light, transmittance);
}

/**
 * The nearest storm tower along the ray from the camera (`dir`), closer than `limit`: its distance and the surface's
 * normal there (no hit: distance `limit`). Each tower a vertical column of 0.9 its radius from the cell's base to its
 * dome's base, under a dome (an ellipsoid of its radius and its cap's height), as clouds.slang's towerDistance.
 */
float4 nearestTower(CloudFrame* f, float3 dir, float limit, out uint hitCell)
{
    float3 o = push.camera;
    float best = limit;
    float3 normal = float3(0, 1, 0);
    hitCell = 0;
    for (uint cell = 0; cell < min(f[0].cellCount, MAX_CELLS); cell++)
    {
        float base = f[0].cells[cell].base;
        uint n = min(f[0].cells[cell].towers, MAX_TOWERS);
        for (uint k = 0; k < n; k++)
        {
            float4 tw = f[0].cells[cell].tower[k];
            float r = 0.9 * tw.z;
            float height = tw.w - base;
            if (r < 1 || height < 1)
            {
                continue;
            }
            float cap = min(0.9 * tw.z, height);
            float capBase = tw.w - cap;
            // The column: |xz - c| = r between the base and the dome's base.
            float2 oc = o.xz - tw.xy;
            float a = dot(dir.xz, dir.xz);
            float b = dot(oc, dir.xz);
            float c = dot(oc, oc) - r * r;
            float disc = b * b - a * c;
            if (a > 1.0e-8 && disc > 0)
            {
                float t = (-b - sqrt(disc)) / a;
                float yy = o.y + t * dir.y;
                if (t > 0 && t < best && yy > base && yy < capBase)
                {
                    best = t;
                    normal = normalize(float3(oc.x + t * dir.x, 0, oc.y + t * dir.z));
                    hitCell = cell;
                }
            }
            // The dome: an ellipsoid, a sphere of radius r once its height is scaled by r / cap.
            float3 scale = float3(1, r / cap, 1);
            float3 so = (o - float3(tw.x, capBase, tw.y)) * scale;
            float3 sd = dir * scale;
            float qa = dot(sd, sd);
            float qb = dot(so, sd);
            float qc = dot(so, so) - r * r;
            float qd = qb * qb - qa * qc;
            if (qd > 0)
            {
                float t = (-qb - sqrt(qd)) / qa;
                float3 hp = so + t * sd;
                if (t > 0 && t < best && hp.y >= 0)
                {
                    best = t;
                    normal = normalize(hp * scale);
                    hitCell = cell;
                }
            }
            // Its flat base, from below.
            if (dir.y > 1.0e-4 && o.y < base)
            {
                float t = (base - o.y) / dir.y;
                float2 hp = oc + t * dir.xz;
                if (t < best && dot(hp, hp) < r * r)
                {
                    best = t;
                    normal = float3(0, -1, 0);
                    hitCell = cell;
                }
            }
        }
    }
    return float4(normal, best);
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
    float4 layer = float4(0, 0, 0, 1);
    float4 anvil = float4(0, 0, 0, 1);
    float4 towerLight = float4(0, 0, 0, 1);
    float layerT = 3.4e38;
    float anvilT = 3.4e38;
    float towerT = 3.4e38;
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
                float tau = (top - base) * f[0].types[type].column * f[0].densityScale;
                float4 l = slabLight(f, dir, tm, middle, tau, at.coverage,
                    cloudPhase(f, type, dot(dir, f[0].sunDirection), 1), top);
                layerT = tm;
                layer = l;
            }
        }
    }
    // The storms' anvils: a slab of ice at their middle, the maps' anvil cover and top there.
    float anvilMiddle = f[0].anvilTop - 0.5 * f[0].anvilThickness;
    if (f[0].anvilTop > 100 && abs(dir.y) > 1.0e-4)
    {
        float ta = (anvilMiddle - y) / dir.y;
        if (ta > 0 && ta < distance)
        {
            float3 q = push.camera + dir * ta;
            float cover = channelsAt(f, q.xz).w;
            if (cover > 1.0e-3)
            {
                float thick = anvilThick(f, cover);
                float tau = thick * f[0].anvilColumn * f[0].densityScale;
                anvilT = ta;
                anvil = slabLight(f, dir, ta, anvilMiddle, tau, saturate(cover / 0.15),
                    henyeyGreenstein(dot(dir, f[0].sunDirection), 0.75), f[0].anvilTop);
            }
        }
    }
    // The storms' towers: thick clouds' surfaces, lit by the sun on the side facing it and the sky (more on the
    // upper side), the ground's light from below; their liquid towers fading as they dissipate.
    uint cell;
    float4 tower = nearestTower(f, dir, distance, cell);
    if (tower.w < distance)
    {
        float3 n = tower.xyz;
        float3 toSun = f[0].sunDirection;
        float3 hit = push.camera + dir * tower.w;
        float3 sun = f[0].sunIlluminance * sunTransmittance(f, hit.y);
        float3 sky = f[0].skyIlluminance * (0.5 + 0.5 * n.y);
        float3 ground = f[0].groundAlbedo * (sun * max(toSun.y, 0) + f[0].skyIlluminance) * (0.5 - 0.5 * n.y);
        // A thick cloud reflects about nine tenths of what falls on it, diffusely (1 - the two-stream transmittance).
        float3 cloud = 0.9 * (sun * max(dot(n, toSun), 0) + sky + 0.25 * ground) / PI;
        float cover = max(f[0].cells[cell].water, 0.2 * f[0].cells[cell].glaciation);
        float3 l = cloud * cover;
        float tt = 1 - cover;
        if (push.scale > 0)
        {
            float3 ap = aerialTransmittance(f, y, hit.y, tower.w);
            l = l * ap + f[0].apRadiance * (1 - ap) * (1 - tt);
        }
        towerT = tower.w;
        towerLight = float4(l, tt);
    }
    // Nearest first: the layer, the anvils and the towers in the order the ray meets them.
    float4 parts[3] = { layer, anvil, towerLight };
    float at3[3] = { layerT, anvilT, towerT };
    for (uint i = 0; i < 3; i++)
    {
        uint nearest = i;
        for (uint j = i + 1; j < 3; j++)
        {
            nearest = at3[j] < at3[nearest] ? j : nearest;
        }
        float4 part = parts[nearest];
        float swapT = at3[i];
        at3[i] = at3[nearest];
        at3[nearest] = swapT;
        parts[nearest] = parts[i];
        light += transmittance * part.rgb;
        transmittance *= part.a;
    }
    float4 scene = images[push.color][id.xy];
    images[push.color][id.xy] = float4(min(scene.rgb * transmittance + light, 65000.0), scene.a);
}
