package com.bmp.persistence.tenant;

/** SQL-level names shared by the tenant isolation migrations and the Java code that drives them. */
public final class TenantSql {

    /** Transaction-local PostgreSQL setting read by every RLS policy. */
    public static final String TENANT_SETTING = "app.tenant_id";

    /**
     * SQLSTATE raised by {@code platform.current_tenant_id()} when a tenant-owned table is touched without a tenant
     * (fail closed instead of returning no rows). Mapped to {@code TENANT_CONTEXT_MISSING}.
     */
    public static final String TENANT_CONTEXT_MISSING_SQLSTATE = "BM001";

    private TenantSql() {}
}
