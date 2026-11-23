package com.bmp.inventory.domain.model;

/** Why stock was lost (INV-11). */
public enum WastageReason {
    BREAKAGE,
    SPOILAGE,
    SPILLAGE,
    EXPIRED,
    THEFT,
    DAMAGED,
    OTHER;

    /** OTHER needs a note saying what happened. */
    public boolean needsNote() {
        return this == OTHER;
    }
}
