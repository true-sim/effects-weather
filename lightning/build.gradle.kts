plugins {
    id("truesim.java-library-conventions")
    id("truesim.allocation-free")
}

description = "Lightning data for true-sim: flash and stroke events, branching bolt geometry from a seed, flicker envelopes and thunder delay, for renderers and audio."

dependencies {
    api("com.truesim.effects:atmosphere-ephemeris:0.1.0") // Quality presets, planet, time, sun
    api("com.truesim.common:common-math:0.6.0")
}

coverage {
    minimum = 0.9
}
