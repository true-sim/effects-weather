package com.truesim.effects.weather.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;

import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_SAMPLED_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

import com.truesim.engine.graph.Access;
import com.truesim.engine.graph.ExternalImage;
import com.truesim.engine.graph.FrameGraph;
import com.truesim.engine.graph.GraphBuilder;
import com.truesim.engine.graph.Image;
import com.truesim.engine.graph.ImageDesc;
import com.truesim.engine.graph.Key;
import com.truesim.engine.graph.Technique;
import com.truesim.engine.vulkan.Format;
import com.truesim.engine.vulkan.GpuImage;
import com.truesim.engine.vulkan.VulkanDevice;
import com.truesim.engine.vulkan.VulkanException;
import com.truesim.engine.vulkan.VulkanInstance;
import java.util.ArrayDeque;
import java.util.Deque;

/** A validated headless device and a frame graph for the tests; closes what is given to it, last first. */
final class TestGpu implements AutoCloseable {

    final VulkanInstance instance;
    final VulkanDevice device;
    final FrameGraph graph;
    private final Deque<AutoCloseable> owned = new ArrayDeque<>();

    private TestGpu(VulkanInstance instance, VulkanDevice device, FrameGraph graph) {
        this.instance = instance;
        this.device = device;
        this.graph = graph;
    }

    /**
     * A device on the best GPU, validated (synchronization too; GPU-assisted with TRUESIM_GPU_ASSISTED_VALIDATION=true),
     * or the test skipped without Vulkan.
     */
    static TestGpu open() {
        VulkanInstance instance;
        boolean validation = !"true".equals(System.getenv("TRUESIM_BENCHMARK"))
            && !"true".equals(System.getenv("TRUESIM_SCREENSHOTS"));
        try {
            // TRUESIM_GPU_ASSISTED_VALIDATION=true: the layer also checks shader reads and writes as they run.
            instance = VulkanInstance.builder().applicationName("tests").validation(validation)
                .synchronizationValidation(validation).gpuAssistedValidation(validation
                    && "true".equals(System.getenv("TRUESIM_GPU_ASSISTED_VALIDATION"))).surfaces(false).build();
        } catch (VulkanException | UnsatisfiedLinkError e) {
            assumeThat(e).as("no Vulkan available").isNull();
            throw e;
        }
        if (instance.gpus().isEmpty()) {
            instance.close();
            assumeThat(false).as("no Vulkan 1.3 GPU").isTrue();
        }
        VulkanDevice device = VulkanDevice.createHeadless(instance.bestGpu());
        return new TestGpu(instance, device, new FrameGraph(device, 2));
    }

    /** The key of {@link #sink}'s output. */
    static final Key<Image> SINK = Key.image("test.sink");

    /**
     * A 1 x 1 view whose output nothing but {@code work} provides: the graph builds a frame-level technique's work
     * only where something needs it, so tests of such work give it this.
     */
    void sink(Technique work) {
        int usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
        GpuImage image = own(new GpuImage(device, 1, 1, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT));
        ExternalImage out = new ExternalImage("sink", 1, 1, Format.R8G8B8A8_UNORM, usage, VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).set(image.handle(), image.view());
        graph.addView("sink").size(1, 1).add(work).output(SINK, out);
    }

    /** Clears a 1 x 1 image as {@link #SINK}: what a sink's work ends with. */
    static void provideSink(GraphBuilder g) {
        Image sink = g.create("sink", ImageDesc.of(Format.R8G8B8A8_UNORM, 1, 1));
        g.pass("sink").write(sink, Access.TRANSFER_DST).record(ctx -> ctx.clear(sink.whole(), 0, 0, 0, 1));
        g.provide(SINK, sink);
    }

    /** Renders {@code n} frames, {@code beforeEach} after each frame begins, and waits for the GPU. */
    void frames(int n, Runnable beforeEach) {
        for (int f = 0; f < n; f++) {
            graph.beginFrame();
            beforeEach.run();
            graph.submit(0, 0);
        }
        device.waitIdle();
    }

    /** {@code resource}, closed with this before the graph and the device. */
    <T extends AutoCloseable> T own(T resource) {
        owned.push(resource);
        return resource;
    }

    @Override
    public void close() {
        device.waitIdle();
        int errors = instance.validationErrors();
        graph.close();
        while (!owned.isEmpty()) {
            try {
                owned.pop().close();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        device.close();
        instance.close();
        assertThat(errors).as("validation errors").isZero();
    }
}
