package com.bmp.catalog.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * What a product's stock tracks. Templates set defaults (loose goods are weighed with decimal quantities); everything
 * can be changed afterwards.
 *
 * @param batches stock is kept per delivery batch
 * @param expiry batches carry an expiry date
 * @param serials items carry serial numbers
 * @param decimalQty quantities may be fractional
 * @param weighed sold by weight on a scale
 */
@Embeddable
public record Tracking(
        @Column(name = "track_batches", nullable = false) boolean batches,
        @Column(name = "track_expiry", nullable = false) boolean expiry,
        @Column(name = "track_serials", nullable = false) boolean serials,
        @Column(name = "decimal_qty", nullable = false) boolean decimalQty,
        @Column(name = "weighed", nullable = false) boolean weighed) {

    public static final Tracking NONE = new Tracking(false, false, false, false, false);

    /** Whole pieces, no batches. */
    public static Tracking counted() {
        return NONE;
    }

    /** Measured goods: fractional quantities. */
    public static Tracking measured() {
        return new Tracking(false, false, false, true, false);
    }

    /** Loose goods: weighed, fractional quantities. */
    public static Tracking weighedLoose() {
        return new Tracking(false, false, false, true, true);
    }
}
