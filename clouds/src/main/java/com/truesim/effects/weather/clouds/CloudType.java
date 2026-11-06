package com.truesim.effects.weather.clouds;

/**
 * The cloud genera the weather map's type channel selects (WMO International Cloud Atlas). The order is the channel's
 * encoding and is saved: append only. {@link #channelValue()} is what a weather map stores, {@link #fromChannel} reads
 * it back (nearest type).
 */
public enum CloudType {
    /** Low, flat, grey layer (fog banks aloft), 0–600 m by default. */
    STRATUS,
    /** Low lumpy layer, patches with gaps. */
    STRATOCUMULUS,
    /** Fair-weather heaps with flat bases. */
    CUMULUS,
    /** Cumulus congestus: tall, billowing, no anvil. */
    TOWERING_CUMULUS,
    /** Thunderstorm: tall core, anvil spreading downwind at the tropopause, rain and lightning. */
    CUMULONIMBUS,
    /** Mid-level patches and waves. */
    ALTOCUMULUS,
    /** Mid-level uniform sheet. */
    ALTOSTRATUS,
    /** Thick, dark, raining layer from low to mid levels. */
    NIMBOSTRATUS,
    /** High, thin ice layer (cirrus, cirrostratus). */
    CIRRUS;

    private static final CloudType[] VALUES = values();

    /** The value of the weather map's type channel for this type, in [0, 1]. */
    public float channelValue() {
        return (float) ordinal() / (VALUES.length - 1);
    }

    /** The type whose channel value is nearest {@code value} (clamped to [0, 1]). */
    public static CloudType fromChannel(float value) {
        int i = Math.round(Math.clamp(value, 0f, 1f) * (VALUES.length - 1));
        return VALUES[i];
    }

    /** The type of ordinal {@code index}. */
    public static CloudType of(int index) {
        return VALUES[index];
    }

    /** How many types there are. */
    public static int count() {
        return VALUES.length;
    }
}
