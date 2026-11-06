plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Cloud types for true-sim: vertical density profiles per type, physical parameters with art overrides, and CPU reference noise (Perlin-Worley, Worley fbm, curl) that GPU generators match."

dependencies {
    api("com.truesim.effects:atmosphere-ephemeris:0.1.0") // Quality presets, planet, time, sun
    api("com.truesim.common:common-math:0.6.0")
}

coverage {
    minimum = 0.9
}
