package com.bmp.tenant.domain.service;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.tenant.api.spi.ActivityEvidence;
import com.bmp.tenant.domain.error.TenantErrorCode;
import java.util.Map;

/**
 * Rule TEN-02: currency and time zone cannot change once business documents may exist. A platform administrator may
 * override an assumption (the default probe's guess), never confirmed documents: re-denominating existing bills is
 * impossible for everyone.
 */
public final class RegionalLock {

    private RegionalLock() {}

    /** Throws {@code SETTING_LOCKED} for {@code key} unless the change is allowed. */
    public static void check(String key, ActivityEvidence evidence, boolean platformOverride) {
        boolean allowed = switch (evidence) {
            case NONE -> true;
            case ASSUMED -> platformOverride;
            case CONFIRMED -> false;
        };
        if (!allowed) {
            throw new BusinessRuleViolation(TenantErrorCode.SETTING_LOCKED, Map.of("key", key));
        }
    }
}
