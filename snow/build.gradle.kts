plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
    id("truesim.slang")
}

description = "Snow for true-sim as a surface shading hook of the engine: snow lying where the sky reaches (a top-down " +
    "occlusion map), thresholded noise by coverage, slopes shedding it, accumulating and melting over time."

dependencies {
    api("com.truesim.engine:engine-scene:0.192.0") // the renderer's shading hooks, the scene bridge
    testImplementation(testFixtures("com.truesim.engine:engine-render:0.192.0")) // the headless renderer
    testImplementation("com.truesim.scene:scene-meshbuild:0.70.0")
}

// The snow's record (SnowParameters) and the map's push layout, as Java; the listing (WeatherSnowShadingHooks)
// rebuilds the engine's shading with the snow hook.
slang {
    javaPackage = "com.truesim.effects.weather.snow.hooks"
    pushBase = "com.truesim.engine.vulkan.PushConstants" // push layouts are writers too
}

// LWJGL and mapped GPU memory use native access.
tasks.withType<Test>().configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// The screenshots (TRUESIM_SCREENSHOTS=true) render at full size.
tasks.test {
    if (providers.environmentVariable("TRUESIM_SCREENSHOTS").orNull == "true") {
        maxHeapSize = "4g"
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
    providers.gradleProperty("screenshots").orNull?.let { systemProperty("truesim.screenshots", it) }
}
