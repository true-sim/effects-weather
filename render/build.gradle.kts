plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "The weather drawn by true-sim's engine: volumetric, layered and 2D clouds from the weather model, cloud " +
    "shadows, rain shafts under storm cells, and lightning lighting the clouds and the scene, with its bolts."

dependencies {
    api(project(":weather-model"))
    api("com.truesim.engine:engine-scene:0.192.0") // the renderer, the frame graph, the scene's lights
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.192.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
}

// The records, push constants and constants of src/main/slang, as Java.
slang {
    pushPackage = "com.truesim.effects.weather.render.layout"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
    constantModules = listOf("weather/clouds")
}

coverage {
    minimum = 0.8
}

// The benchmarks (TRUESIM_BENCHMARK=true) and screenshots measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true"
        || providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
