package com.truesim.effects.weather.lightning;

/** The kinds of lightning flash. The order is saved in events: append only. */
public enum FlashType {
    /** Negative cloud-to-ground: the common ground flash, several strokes. */
    NEGATIVE_GROUND,
    /** Positive cloud-to-ground: one strong stroke, often from the anvil, far from the core. */
    POSITIVE_GROUND,
    /** Within the cloud: lights it from inside, no ground strike. */
    INTRACLOUD;

    private static final FlashType[] VALUES = values();

    /** The type of ordinal {@code ordinal}. */
    public static FlashType of(int ordinal) {
        return VALUES[ordinal];
    }

    /** Whether it reaches the ground. */
    public boolean strikesGround() {
        return this != INTRACLOUD;
    }
}
