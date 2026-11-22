package com.bmp.catalog.domain.model;

/** What a transformation definition does: open a bulk unit, repack loose stock, break a pack, or bake a recipe. */
public enum TransformationKind {
    OPEN,
    REPACK,
    BREAK,
    RECIPE
}
