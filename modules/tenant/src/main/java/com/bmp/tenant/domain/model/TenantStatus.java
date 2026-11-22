package com.bmp.tenant.domain.model;

/** Lifecycle of a tenant. */
public enum TenantStatus {
    ACTIVE,
    /** TEN-04: users keep read-only access. */
    SUSPENDED,
    CLOSED
}
