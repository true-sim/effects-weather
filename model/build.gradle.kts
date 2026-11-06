plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "The weather model for true-sim: the weather map over the world, presets with transitions, brushes, wind by altitude and a deterministic storm life-cycle simulation."

dependencies {
    api(project(":weather-clouds"))
    api(project(":weather-lightning"))
    api("com.truesim.effects:atmosphere-ephemeris:0.1.0")
    api("com.truesim.scene:scene-core:0.65.0")
}

coverage {
    minimum = 0.9
}
