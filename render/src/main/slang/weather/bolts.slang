// Lightning bolts (LightningBolts): each segment of the frame's bolts (weather-lightning's BoltGeometry: the branching
// channel by midpoint displacement) as a camera-facing strip of the channel's luminance, added over the scene's colour
// after the transparents. A strip is at least 1.5 pixels wide, its luminance spread so its intensity stays; behind the
// scene's depth it is left out (tested here: the pipeline has no depth attachment).
import bindless;
import meshlets.common;

/** A segment from `a` to `b` (metres from the weather's anchor), its luminance (cd/m^2) and its width. */
[HostLayout]
struct BoltSegment
{
    float3 a;
    float luminance;
    float3 b;
    float width;
};

struct BoltPush
{
    View* view;
    BoltSegment* segments;
    uint depth;          // sampled slot: the scene's depth
    uint size;           // the render region
    float3 camera;       // the camera from the anchor
    float minPixels;     // the narrowest a strip is drawn, pixels
};

[[vk::push_constant]]
ConstantBuffer<BoltPush> push;

struct BoltVertex
{
    float4 position : SV_Position;
    float across : ACROSS;
    float luminance : LUMINANCE;
};

[shader("vertex")]
BoltVertex boltVertexMain(uint vertex : SV_VertexID)
{
    uint segment = vertex / 6;
    uint corner = vertex % 6;
    // Two triangles: (0, 1, 2), (2, 1, 3) of the corners a-, a+, b-, b+.
    uint c = corner < 3 ? corner : (corner == 3 ? 2 : (corner == 4 ? 1 : 3));
    float3 a = push.segments[segment].a - push.camera;
    float3 b = push.segments[segment].b - push.camera;
    float width = push.segments[segment].width;
    float luminance = push.segments[segment].luminance;
    float3 p = c < 2 ? a : b;
    float3 along = b - a;
    float3 side = cross(along, p);
    float sideLength = length(side);
    side = sideLength > 1.0e-6 ? side / sideLength : float3(1, 0, 0);
    // At least minPixels wide where it is: the width a pixel spans there, and the luminance thinned to match.
    float distance = max(length(p), 1.0);
    float pixel = distance / max(push.view[0].projectionScale, 1.0e-6);
    float drawn = max(width, push.minPixels * pixel);
    float sign = (c & 1) == 0 ? -1.0 : 1.0;
    BoltVertex o;
    o.position = mul(push.view[0].viewProjection, float4(p + side * (0.5 * drawn * sign), 1));
    o.across = sign;
    o.luminance = luminance * width / drawn;
    return o;
}

[shader("fragment")]
float4 boltFragmentMain(BoltVertex v) : SV_Target
{
    // Behind the scene: reverse depth, nearer is larger.
    float scene = textures[push.depth].Load(int3(int2(v.position.xy), 0)).r;
    if (v.position.z < scene)
    {
        discard;
    }
    // A soft profile across the strip, (1 - |x|)^2 times 3 so its mean across is 1.
    float profile = 1 - abs(v.across);
    return float4(float3(v.luminance * 3 * profile * profile), 0);
}
