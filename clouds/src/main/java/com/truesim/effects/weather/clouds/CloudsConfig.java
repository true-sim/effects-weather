package com.truesim.effects.weather.clouds;

import com.truesim.effects.atmosphere.Quality;

/**
 * The cloud tiers and the data each needs. A cheap tier never needs the expensive data: {@link Technique#SKYBOX}
 * reads only the weather map's coverage and type (or a static texture), {@link Technique#LAYERED} adds the profiles and
 * the noise volumes, {@link Technique#VOLUMETRIC} adds the voxel cache around the camera (the render plugin's).
 *
 * @param technique the tier, the default kind of the render plugin's slot
 * @param shapeSize the base shape volume's edge (texels): 128 in Schneider 2015
 * @param shapeFrequency lattice cells per tile of the base shape
 * @param detailSize the detail volume's edge: 32 in Schneider 2015
 * @param detailFrequency lattice cells per tile of the detail volume
 * @param curlSize the curl texture's edge
 * @param perlinOctaves octaves of the Perlin fbm in the Perlin–Worley channel
 * @param seed the noises' seed
 */
public record CloudsConfig(Technique technique, int shapeSize, int shapeFrequency, int detailSize, int detailFrequency,
    int curlSize, int perlinOctaves, int seed) {

    /** The cloud tiers, cheapest first. */
    public enum Technique {
        /** No clouds. */
        OFF,
        /** 2D: a cloud layer texture on the sky dome or billboards, driven by the weather map's coverage and type. */
        SKYBOX,
        /** Ray-marched layers (Nubis-style 2.5D): profiles, noise volumes, the weather map. */
        LAYERED,
        /** Layers plus a voxel cache around the camera for flying through clouds. */
        VOLUMETRIC
    }

    public CloudsConfig {
        java.util.Objects.requireNonNull(technique, "technique");
        Checks.range(shapeSize, 4, 512, "shapeSize");
        Checks.range(shapeFrequency, 1, 64, "shapeFrequency");
        Checks.range(detailSize, 4, 256, "detailSize");
        Checks.range(detailFrequency, 1, 64, "detailFrequency");
        Checks.range(curlSize, 4, 1024, "curlSize");
        Checks.range(perlinOctaves, 1, 8, "perlinOctaves");
    }

    /** LOW: 2D clouds; MEDIUM: layers with smaller noise; HIGH: layers (Schneider's sizes); ULTRA: volumetric. */
    public static CloudsConfig forQuality(Quality quality) {
        return switch (quality) {
            case LOW -> new CloudsConfig(Technique.SKYBOX, 32, 4, 16, 2, 64, 3, 1);
            case MEDIUM -> new CloudsConfig(Technique.LAYERED, 64, 4, 32, 2, 128, 3, 1);
            case HIGH -> new CloudsConfig(Technique.LAYERED, 128, 4, 32, 2, 128, 4, 1);
            case ULTRA -> new CloudsConfig(Technique.VOLUMETRIC, 128, 4, 32, 2, 128, 5, 1);
        };
    }

    /** Whether the tier needs the 3D noise volumes. */
    public boolean needsNoiseVolumes() {
        return technique == Technique.LAYERED || technique == Technique.VOLUMETRIC;
    }
}
