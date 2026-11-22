package com.bmp.catalog.domain.port;

/**
 * The tenant's catalog version (catalog snapshot, CAT-13). Taking the next version locks the tenant's row until the
 * transaction ends, so versions become visible in order and a till's delta never misses a change.
 */
public interface CatalogRevisions {

    /** Takes the next catalog version for the current change. */
    long next();

    /** The latest committed catalog version (0 when nothing changed yet). */
    long current();
}
