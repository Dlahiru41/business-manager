package com.bmp.tenant.api.dto;

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Specification of a tenant to provision; unset regional values take the platform defaults (LKR, Asia/Colombo, 0).
 *
 * @param name trading name
 * @param legalName registered name
 * @param businessType classification
 * @param currency ISO 4217 code
 * @param timeZone IANA zone
 * @param businessDayCutoffHour 0–23
 * @param plan subscription plan
 * @param templatePackId onboarding template pack (phase 05)
 */
public record NewTenant(
        String name,
        @Nullable String legalName,
        String businessType,
        @Nullable String currency,
        @Nullable String timeZone,
        @Nullable Integer businessDayCutoffHour,
        @Nullable String plan,
        @Nullable String templatePackId) {

    public NewTenant {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(businessType, "businessType");
    }
}
