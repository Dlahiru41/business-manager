package com.bmp.catalog.api.events;

/**
 * What a product's stock tracks, as carried by catalog events.
 *
 * @param batches stock is kept per delivery batch
 * @param expiry batches carry an expiry date
 * @param serials items carry serial numbers
 * @param decimalQty quantities may be fractional
 * @param weighed sold by weight on a scale
 */
public record ProductTracking(boolean batches, boolean expiry, boolean serials, boolean decimalQty, boolean weighed) {}
