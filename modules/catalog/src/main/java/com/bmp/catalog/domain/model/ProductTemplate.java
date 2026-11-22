package com.bmp.catalog.domain.model;

/**
 * Presets that configure a product (domain-model.md §2). Templates only set defaults; everything can be edited
 * afterwards. Their defaults live in {@code ProductTemplateApplier}.
 */
public enum ProductTemplate {
    SIMPLE,
    VARIANT_MATRIX,
    BULK_BREAK,
    BRANDED_PACKET,
    LOOSE_REPACK,
    RECIPE,
    SERVICE
}
