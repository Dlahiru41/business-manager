package com.bmp.iam.api;

import com.bmp.iam.api.dto.ExistingAccount;
import com.bmp.shared.id.TenantId;

/**
 * Gives existing identity provider accounts a profile in a tenant, without calling the identity provider (local
 * seeding, onboarding in phase 05). Seeds the system roles first. Idempotent: an existing profile is left unchanged.
 */
public interface IamProvisioning {

    void provision(TenantId tenant, ExistingAccount account);
}
