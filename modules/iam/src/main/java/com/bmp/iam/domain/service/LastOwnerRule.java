package com.bmp.iam.domain.service;

import com.bmp.iam.domain.error.IamErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.UserId;
import java.util.Map;

/**
 * Rule IAM-01: a tenant always keeps at least one active owner, so nobody can lock the business out of its own data.
 * The caller counts the active owners (including the user being changed) under a lock that serializes owner changes.
 */
public final class LastOwnerRule {

    private LastOwnerRule() {}

    /**
     * Throws {@code LAST_OWNER} when the change would leave the tenant without an active owner.
     *
     * @param wasActiveOwner the user is active and holds the owner role before the change
     * @param staysActiveOwner the user is active and holds the owner role after the change
     * @param activeOwners active owners of the tenant before the change, including this user
     */
    public static void check(UserId user, boolean wasActiveOwner, boolean staysActiveOwner, long activeOwners) {
        if (wasActiveOwner && !staysActiveOwner && activeOwners <= 1) {
            throw new BusinessRuleViolation(IamErrorCode.LAST_OWNER, Map.of("userId", user));
        }
    }
}
