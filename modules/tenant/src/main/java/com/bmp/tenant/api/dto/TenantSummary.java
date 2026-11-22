package com.bmp.tenant.api.dto;

import com.bmp.shared.id.TenantId;

/**
 * What other modules need to know about a tenant.
 *
 * @param id the tenant
 * @param name trading name
 * @param currency ISO 4217 code
 * @param timeZone IANA time zone
 * @param businessDayCutoffHour 0–23
 * @param status ACTIVE, SUSPENDED or CLOSED
 */
public record TenantSummary(
        TenantId id, String name, String currency, String timeZone, int businessDayCutoffHour, String status) {}
