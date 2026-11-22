package com.bmp.catalog.domain.model;

/** Where a product conversion factor comes from (oil: density, or a measured yield). */
public enum ConversionSource {
    DENSITY,
    MEASURED_YIELD,
    STANDARD
}
