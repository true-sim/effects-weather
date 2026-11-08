// The snow's maps (SimpleSnow; effects-weather#4, part 1): captureMain keeps the top-down depth draw's depth as the
// occlusion map (a persistent image the surface hook and the accumulation sample); accumulateMain updates the
// accumulation, a texel per world column round the camera (toroidal: a column keeps its texel while it stays in the
// window): its snow's amount, the height of the surface it lies on and how long that surface has been covered.
//
// Per column, each frame: a column new to the window starts at the initial amount on its topmost surface. While the
// topmost surface is the one the snow lies on, snow falls on it (fill per second, times the snowfall). Something above
// it (a car driving over, a tarpaulin) covers it: nothing falls, what lies stays; covered longer than coverSeconds,
// the cover is the new surface, bare. A surface below it revealed (a parked car leaving) never had snow fall on it: it
// starts bare. Melting takes everything down alike.
import bindless;

/**
 * Rows x and y of the map's view-projection (origin-relative positions to clip space), and row y of its inverse (clip
 * space to the height above the origin): an orthographic view, w 1 throughout.
 */
struct SnowMapFrame
{
    float4 toMapX;
    float4 toMapY;
    float4 fromMapY;
};

struct SnowMapPush
{
    SnowMapFrame* frame;           // the map's matrices this frame
    uint depth;                    // the depth draw's depth, sampled slot
    uint occlusion;                // the occlusion map: storage slot (capture), sampled slot (accumulate)
    uint mapSize;                  // texels a side
    uint accumulation;             // storage slot
    uint size;                     // the accumulation's texels a side
    uint fresh;                    // 1: no window before (every column new)
    int windowX;                   // the window's first column, world cells
    int windowZ;
    int previousX;                 // last frame's
    int previousZ;
    int originX;                   // the map origin's cell
    int originZ;
    float texel;                   // metres per cell
    float seconds;                 // since last frame
    float fill;                    // amount per second while it snows (times the snowfall)
    float melt;                    // amount per second melting
    float coverSeconds;            // covered this long, the cover is the new surface
    float surfaceChange;           // metres of height that are another surface
    float initial;                 // a new column's amount
    float originY;                 // the map origin's height, world
};

[[vk::push_constant]]
ConstantBuffer<SnowMapPush> push;

/** a mod b, 0 to b - 1. */
int2 wrap(int2 a, int b)
{
    return ((a % b) + b) % b;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void captureMain(uint3 id : SV_DispatchThreadID)
{
    uint size = push.mapSize;
    if (id.x >= size || id.y >= size)
    {
        return;
    }
    float top = textures[push.depth].Load(int3(int2(id.xy), 0)).r;
    images[push.occlusion][id.xy] = float4(top, 0, 0, 0);
}

[shader("compute")]
[numthreads(8, 8, 1)]
void accumulateMain(uint3 id : SV_DispatchThreadID)
{
    int size = int(push.size);
    if (id.x >= uint(size) || id.y >= uint(size))
    {
        return;
    }
    int2 t = int2(id.xy);
    int2 window = int2(push.windowX, push.windowZ);
    int2 previous = int2(push.previousX, push.previousZ);
    int2 cell = window + wrap(t - window, size);
    bool same = push.fresh == 0 && all(cell == previous + wrap(t - previous, size));

    // The column's topmost surface: where its centre (at the origin's height) falls on the map
    SnowMapFrame* frame = push.frame;
    float4 tx = frame[0].toMapX;
    float4 ty = frame[0].toMapY;
    float2 rel = (float2(cell - int2(push.originX, push.originZ)) + 0.5) * push.texel;
    float3 p = float3(rel.x, 0, rel.y);
    float2 clip = float2(dot(tx.xyz, p) + tx.w, dot(ty.xyz, p) + ty.w);
    float2 uv = clip * 0.5 + 0.5;
    float height = -1e6; // nothing there
    if (all(uv >= 0) && all(uv <= 1))
    {
        int2 m = min(int2(uv * float(push.mapSize)), int2(int(push.mapSize) - 1));
        float top = textures[push.occlusion].Load(int3(m, 0)).r;
        if (top > 0)
        {
            float4 c = float4(clip, top, 1);
            height = dot(frame[0].fromMapY, c) + push.originY;
        }
    }

    float4 old = images[push.accumulation][t];
    float amount = old.r;
    float surface = old.g;
    float covered = old.b;
    float dt = push.seconds;
    if (!same)
    {
        amount = push.initial;
        surface = height;
        covered = 0;
    }
    else
    {
        float rise = height - surface;
        if (rise < -push.surfaceChange)
        {
            surface = height; // a surface revealed: no snow fell on it
            amount = 0;
            covered = 0;
        }
        else if (rise > push.surfaceChange)
        {
            covered += dt; // covered: nothing falls, what lies stays
            if (covered > push.coverSeconds)
            {
                surface = height;
                amount = 0;
                covered = 0;
            }
        }
        else
        {
            covered = 0;
            amount += push.fill * dt;
        }
        amount -= push.melt * dt;
    }
    images[push.accumulation][t] = float4(saturate(amount), surface, covered, 0);
}
