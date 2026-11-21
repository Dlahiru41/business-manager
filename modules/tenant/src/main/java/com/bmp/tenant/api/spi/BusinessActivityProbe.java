package com.bmp.tenant.api.spi;

import com.bmp.shared.id.TenantId;

/**
 * Tells the tenant module whether a tenant may already have business documents, which locks its currency and time
 * zone (TEN-02). Implemented by the sales module in phase 03 (dependency inversion: tenant is upstream of sales).
 *
 * <p>The tenant module ships a conservative default (true once the tenant is older than one day). A downstream
 * implementation must declare a higher precedence ({@code @Order} below {@code Ordered.LOWEST_PRECEDENCE}).
 */
@FunctionalInterface
public interface BusinessActivityProbe {

    boolean hasBusinessDocuments(TenantId tenant);
}
