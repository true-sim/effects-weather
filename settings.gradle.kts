pluginManagement {
    // Use ../build-logic when present; override with -Ptruesim.build-logic=<path> (or false).
    providers.gradleProperty("truesim.build-logic").orElse("../build-logic").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
    repositories {
        gradlePluginPortal()
        maven("https://maven.pkg.github.com/true-sim/build-logic") {
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}

plugins {
    id("truesim.settings") version "0.19.0"
}

rootProject.name = "effects-weather"

// Each module is published on its own and runs without the engine: weather-clouds (cloud types, profiles, noise) and
// weather-lightning (flashes, bolts, thunder) need only the JDK and common-math; weather-model (the weather map,
// presets, brushes, wind, the storm simulation) adds scene components and effects-atmosphere; weather-snow is the first
// render plugin: snow lying on surfaces as a surface shading hook of the engine.
include("weather-clouds", "weather-lightning", "weather-model", "weather-snow", "weather-render")
project(":weather-clouds").projectDir = file("clouds")
project(":weather-lightning").projectDir = file("lightning")
project(":weather-model").projectDir = file("model")
project(":weather-snow").projectDir = file("snow")
project(":weather-render").projectDir = file("render")

// Sibling checkouts are used as included builds when present (override with -Ptruesim.<repo>=<path>, or false).
val truesimRepos = listOf("build-logic", "common-math", "common-state", "common-jobs", "common-graph", "scene", "ui", "engine",
    "effects-atmosphere")
for (repo in truesimRepos) {
    providers.gradleProperty("truesim.$repo").orElse("../$repo").get()
        .takeIf { file(it).isDirectory }?.let { includeBuild(it) }
}

dependencyResolutionManagement {
    repositories {
        for (repo in truesimRepos) {
            maven("https://maven.pkg.github.com/true-sim/$repo") {
                credentials {
                    username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                    password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
                }
            }
        }
    }
}
