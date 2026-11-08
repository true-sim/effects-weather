plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "The weather model for true-sim: the weather map over the world, presets with transitions, brushes, wind by altitude and a deterministic storm life-cycle simulation."

dependencies {
    api(project(":weather-clouds"))
    api(project(":weather-lightning"))
    api("com.truesim.effects:atmosphere-ephemeris:0.1.0")
    api("com.truesim.scene:scene-core:0.70.0")
}

coverage {
    minimum = 0.9
}

// The benchmarks (TRUESIM_BENCHMARK=true) measure the code, not its coverage probes.
tasks.test {
    if (providers.environmentVariable("TRUESIM_BENCHMARK").orNull == "true") {
        extensions.configure<JacocoTaskExtension> { isEnabled = false }
    }
}
