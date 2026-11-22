package com.bmp.catalog.domain.model;

/**
 * Lifecycle of a transformation definition (CAT-10): {@code DRAFT} has unpublished content, {@code PUBLISHED} content
 * equals the latest published version, {@code RETIRED} may no longer be used.
 */
public enum DefinitionStatus {
    DRAFT,
    PUBLISHED,
    RETIRED
}
