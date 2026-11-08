// The snow's maps (SimpleSnow; effects-weather#4, part 1): captureMain keeps the depth draw along the fall direction as
// the occlusion map (a persistent image the surface hook and the accumulation sample); columnsMain finds, for every
// world column round the camera, the topmost surface in it the falling snow reaches: each map texel's surface point,
// wherever along the (slanted) fall it lies, taken to the column under it, the highest per column (an atomic max);
// accumulateMain updates the accumulation, a texel per world column (toroidal: a column keeps its texel while it stays
// in the window): its snow's amount, the height of the surface it lies on and how long that surface has been covered.
//
// Per column, each frame: a column new to the window starts at the initial amount on its topmost surface. While the
// topmost surface is the one the snow lies on, snow falls on it (fill per second, times the snowfall). Something above
// it (a car driving over, a tarpaulin) covers it: nothing falls, what lies stays; covered longer than coverSeconds,
// the cover is the new surface, bare. A surface below it revealed (a parked car leaving) never had snow fall on it: it
// starts bare. A column the snow reaches none of (in the lee of a wall, with the wind slanting the fall) keeps what lies
// on it; reached again (the wind turning), it lies on and gets more. Melting takes everything down alike. A column
// outside the map is left as it is.
//
// The surfaces are the scene's, not the map's: the map's texels lie on a grid fixed in the world (SimpleSnow snaps its
// origin to whole texels across the fall), so moving the camera, up and down too, finds the same surfaces in the same
// columns, and nothing is covered or revealed by the camera moving.
import bindless;

/**
 * Rows x and y of the map's view-projection (origin-relative positions to clip space), and rows x, y and z of its
 * inverse (clip space and depth to positions relative to the origin): an orthographic view, w 1 throughout.
 */
struct SnowMapFrame
{
    float4 toMapX;
    float4 toMapY;
    float4 fromMapY;
    float4 fromMapX;
    float4 fromMapZ;
};

struct SnowMapPush
{
    SnowMapFrame* frame;           // the map's matrices this frame
    uint* columns;                 // per accumulation texel: the column's topmost reached surface (ordered height bits, 0: none)
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
    int originX;                   // the cell the map origin is in
    int originZ;
    float originFractionX;         // and the origin's offset from that cell's corner, metres
    float originFractionZ;
    float texel;                   // metres per cell
    float seconds;                 // since last frame
    float fill;                    // amount per second while it snows (times the snowfall)
    float melt;                    // amount per second melting
    float coverSeconds;            // covered this long, the cover is the new surface
    float surfaceChange;           // metres of height that are another surface
    float initial;                 // a new column's amount
    float originY;                 // the map origin's height, world
};

/** No surface: the columns' empty value, and an accumulation column's surface while none is known. */
static const float NONE = -1e6;

/** A height as bits that order as the heights do (so an atomic max takes the highest); never 0. */
uint orderedBits(float height)
{
    uint u = asuint(height);
    return (u & 0x80000000u) != 0 ? ~u : u | 0x80000000u;
}

/** The height of {@link orderedBits}' bits. */
float fromOrderedBits(uint u)
{
    return (u & 0x80000000u) != 0 ? asfloat(u & 0x7fffffffu) : asfloat(~u);
}

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
void columnsMain(uint3 id : SV_DispatchThreadID)
{
    uint mapSize = push.mapSize;
    if (id.x >= mapSize || id.y >= mapSize)
    {
        return;
    }
    float top = textures[push.occlusion].Load(int3(int2(id.xy), 0)).r;
    if (top <= 0)
    {
        return; // nothing along this line
    }
    // The texel's surface point, relative to the origin
    SnowMapFrame* frame = push.frame;
    float4 c = float4((float2(id.xy) + 0.5) / float(mapSize) * 2 - 1, top, 1);
    float3 rel = float3(dot(frame[0].fromMapX, c), dot(frame[0].fromMapY, c), dot(frame[0].fromMapZ, c));
    // Its column, in the window
    float2 within = (rel.xz + float2(push.originFractionX, push.originFractionZ)) / push.texel;
    int2 cell = int2(push.originX, push.originZ) + int2(floor(within));
    int size = int(push.size);
    int2 window = int2(push.windowX, push.windowZ);
    if (any(cell < window) || any(cell >= window + size))
    {
        return;
    }
    int2 t = wrap(cell, size);
    InterlockedMax(push.columns[t.y * size + t.x], orderedBits(rel.y + push.originY));
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

    float4 old = images[push.accumulation][t];
    float amount = old.r;
    float surface = old.g;
    float covered = old.b;
    // The column's topmost reached surface, if the map reaches any of it
    uint bits = push.columns[t.y * size + t.x];
    float height = bits != 0 ? fromOrderedBits(bits) : NONE;
    if (bits == 0)
    {
        // None: under something, a vertical face, or beyond the map. Beyond it (its centre at the height last known
        // outside the map's square), nothing is known: as it was
        SnowMapFrame* frame = push.frame;
        float2 rel = (float2(cell - int2(push.originX, push.originZ)) + 0.5) * push.texel
            - float2(push.originFractionX, push.originFractionZ);
        float y = (same && surface > NONE ? surface : push.originY) - push.originY;
        float3 p = float3(rel.x, y, rel.y);
        float2 clip = float2(dot(frame[0].toMapX.xyz, p) + frame[0].toMapX.w, dot(frame[0].toMapY.xyz, p)
            + frame[0].toMapY.w);
        if (any(abs(clip) >= 1))
        {
            if (!same)
            {
                images[push.accumulation][t] = float4(push.initial, NONE, 0, 0);
            }
            return;
        }
    }
    float dt = push.seconds;
    if (!same)
    {
        amount = push.initial;
        surface = height;
        covered = 0;
    }
    else if (height <= NONE)
    {
        // The snow reaches none of it now (sheltered since the wind turned, say): nothing falls, what lies stays
        amount -= push.melt * dt;
    }
    else if (surface <= NONE)
    {
        surface = height; // reached again (the wind turned): what lies on it stays, more falls from now
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
