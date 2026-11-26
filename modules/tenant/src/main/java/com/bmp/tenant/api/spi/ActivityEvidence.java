package com.bmp.tenant.api.spi;

/**
 * How sure a {@link BusinessActivityProbe} is that a tenant has business documents (TEN-02), from the weakest to the
 * strongest answer: the order is significant.
 */
public enum ActivityEvidence {
    /** Known to have none: currency and time zone may change. */
    NONE,
    /** Not known, assumed to exist (the default probe's guess): only a platform administrator may change them. */
    ASSUMED,
    /** Documents exist: nobody may change them, platform administrators included. */
    CONFIRMED
}
