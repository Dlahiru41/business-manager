package com.bmp.tenant.domain.model;

/** Locations are deactivated, never deleted (06-persistence-and-data.md §9). */
public enum LocationStatus {
    ACTIVE,
    INACTIVE
}
