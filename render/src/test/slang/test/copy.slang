// Tests: a sampled image's texels (a volume, or a 2D image as z = 0) packed as RGBA8 words into a buffer.
import bindless;

struct CopyPush
{
    uint* output;
    uint image;
    uint size;
    uint flat;           // 1: a 2D image
};

[[vk::push_constant]]
ConstantBuffer<CopyPush> push;

[shader("compute")]
[numthreads(4, 4, 4)]
void copyMain(uint3 id : SV_DispatchThreadID)
{
    uint depth = push.flat != 0 ? 1 : push.size;
    if (id.x >= push.size || id.y >= push.size || id.z >= depth)
    {
        return;
    }
    float4 v = push.flat != 0 ? textures[push.image].Load(int3(id.xy, 0)) : volumes[push.image].Load(int4(id, 0));
    uint4 b = uint4(round(saturate(v) * 255));
    push.output[(id.z * push.size + id.y) * push.size + id.x] = b.x | b.y << 8 | b.z << 16 | b.w << 24;
}

struct CopyFloatPush
{
    float4* output;
    uint image;
    uint size;
};

[[vk::push_constant]]
ConstantBuffer<CopyFloatPush> floatPush;

[shader("compute")]
[numthreads(8, 8, 1)]
void copyFloatMain(uint3 id : SV_DispatchThreadID)
{
    if (id.x >= floatPush.size || id.y >= floatPush.size)
    {
        return;
    }
    floatPush.output[id.y * floatPush.size + id.x] = textures[floatPush.image].Load(int3(id.xy, 0));
}
