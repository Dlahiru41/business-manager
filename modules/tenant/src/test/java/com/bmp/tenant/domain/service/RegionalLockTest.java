package com.bmp.tenant.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.BmpException;
import com.bmp.tenant.api.spi.ActivityEvidence;
import com.bmp.tenant.domain.error.TenantErrorCode;
import org.junit.jupiter.api.Test;

class RegionalLockTest {

    @Test
    void ten02AllowsChangesWithoutDocuments() {
        assertThatCode(() -> RegionalLock.check("currency", ActivityEvidence.NONE, false))
                .doesNotThrowAnyException();
    }

    @Test
    void ten02LetsOnlyAPlatformAdministratorOverrideAnAssumption() {
        assertThatThrownBy(() -> RegionalLock.check("currency", ActivityEvidence.ASSUMED, false))
                .isInstanceOfSatisfying(
                        BmpException.class, e -> assertThat(e.errorCode()).isEqualTo(TenantErrorCode.SETTING_LOCKED));
        assertThatCode(() -> RegionalLock.check("currency", ActivityEvidence.ASSUMED, true))
                .doesNotThrowAnyException();
    }

    @Test
    void ten02ConfirmedDocumentsLockEveryonePlatformAdministratorsIncluded() {
        assertThatThrownBy(() -> RegionalLock.check("timeZone", ActivityEvidence.CONFIRMED, true))
                .isInstanceOfSatisfying(
                        BmpException.class, e -> assertThat(e.errorCode()).isEqualTo(TenantErrorCode.SETTING_LOCKED));
    }
}
