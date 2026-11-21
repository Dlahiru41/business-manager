package com.bmp.tenant.application.query;

import com.bmp.tenant.domain.model.Tenant;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Read model of a tenant profile. */
public record TenantView(
        UUID id,
        String name,
        @Nullable String legalName,
        String businessType,
        String currency,
        String timeZone,
        int businessDayCutoffHour,
        String status,
        @Nullable String plan,
        long version,
        Instant createdAt) {

    public static TenantView of(Tenant tenant) {
        var profile = tenant.profile();
        var regional = tenant.regional();
        return new TenantView(
                tenant.id().value(),
                profile.name(),
                profile.legalName(),
                profile.businessType(),
                regional.currency().code(),
                regional.timeZone().getId(),
                regional.businessDayCutoffHour(),
                tenant.status().name(),
                tenant.plan(),
                tenant.version(),
                tenant.createdAt());
    }
}
