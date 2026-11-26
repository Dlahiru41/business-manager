package com.bmp.tenant.api.spi;

import com.bmp.shared.id.TenantId;

/**
 * Tells the tenant module whether a tenant has business documents, which locks its currency and time zone (TEN-02).
 * Downstream modules implement it for their own documents (dependency inversion: tenant is upstream of them); sales
 * answers for bills.
 *
 * <p>Every probe is asked and the strongest answer counts ({@link ActivityEvidence#NONE} &lt;
 * {@link ActivityEvidence#ASSUMED} &lt; {@link ActivityEvidence#CONFIRMED}, ADR-027): an implementation answers
 * {@code CONFIRMED} when it holds documents of the tenant and {@code NONE} otherwise, and never speaks for other
 * modules. The tenant module's conservative default ({@code ASSUMED} once the tenant is older than one day) stays as
 * the floor for documents no probe knows about.
 */
@FunctionalInterface
public interface BusinessActivityProbe {

    ActivityEvidence evidenceOf(TenantId tenant);
}
