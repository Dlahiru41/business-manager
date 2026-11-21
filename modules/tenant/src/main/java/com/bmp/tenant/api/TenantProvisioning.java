package com.bmp.tenant.api;

import com.bmp.shared.id.TenantId;
import com.bmp.tenant.api.dto.NewTenant;
import com.bmp.tenant.api.dto.TenantSummary;

/**
 * Creates tenants with a known id (local seeding, self-service onboarding in phase 05). Idempotent: provisioning an
 * existing id returns it unchanged. The public API ({@code POST /api/v1/tenants}) generates ids instead.
 */
public interface TenantProvisioning {

    TenantSummary provision(TenantId id, NewTenant spec);
}
