package com.bmp.finance.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());

    @Test
    void fin01ASystemAccountMayBeRenamedAndRenumberedButKeepsItsTypeAndStaysActive() {
        Account cash = Account.seeded(UUID.randomUUID(), TENANT, SystemKey.CASH_DRAWER);
        assertThat(cash.code()).isEqualTo("1000");
        assertThat(cash.name()).isEqualTo("Cash in drawer");
        assertThat(cash.type()).isEqualTo(AccountType.ASSET);
        assertThat(cash.systemKey()).isEqualTo(SystemKey.CASH_DRAWER);

        cash.rename("Till cash");
        cash.renumber("1001");
        cash.changeType(AccountType.ASSET);
        cash.changeStatus(AccountStatus.ACTIVE);
        assertThat(cash.name()).isEqualTo("Till cash");
        assertThat(cash.code()).isEqualTo("1001");

        assertThatThrownBy(() -> cash.changeType(AccountType.EXPENSE))
                .isInstanceOfSatisfying(BusinessRuleViolation.class, e -> {
                    assertThat(e.errorCode()).isEqualTo(FinanceErrorCode.SYSTEM_ACCOUNT_LOCKED);
                    assertThat(e.details()).containsEntry("field", "type").containsEntry("systemKey", "CASH_DRAWER");
                });
        assertThatThrownBy(() -> cash.changeStatus(AccountStatus.INACTIVE))
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.details()).containsEntry("field", "status"));
        assertThat(cash.type()).isEqualTo(AccountType.ASSET);
        assertThat(cash.isActive()).isTrue();
    }

    @Test
    void anAccountTheTenantAddedMayChangeTypeAndBeDeactivated() {
        UUID parent = UUID.randomUUID();
        Account bottles = Account.create(UUID.randomUUID(), TENANT, "6410", "Bottles", AccountType.ASSET, null, parent);
        assertThat(bottles.systemKey()).isNull();
        assertThat(bottles.parentId()).isEqualTo(parent);

        bottles.changeType(AccountType.EXPENSE);
        bottles.classify("packing");
        bottles.changeStatus(AccountStatus.INACTIVE);

        assertThat(bottles.type()).isEqualTo(AccountType.EXPENSE);
        assertThat(bottles.subtype()).isEqualTo("packing");
        assertThat(bottles.status()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(bottles.isActive()).isFalse();
        bottles.changeStatus(AccountStatus.ACTIVE);
        assertThat(bottles.isActive()).isTrue();
    }

    @Test
    void anAccountKeepsWhoCreatedIt() {
        Account account = Account.create(UUID.randomUUID(), TENANT, "6410", "Bottles", AccountType.EXPENSE, null, null);
        UUID user = UUID.randomUUID();
        Instant at = Instant.parse("2026-11-25T10:00:00Z");
        assertThat(account.audit().createdAt()).isEqualTo(Instant.EPOCH);

        account.recordCreation(at, UserId.of(user));
        account.recordModification(at.plusSeconds(60), null);

        assertThat(account.audit().createdAt()).isEqualTo(at);
        assertThat(account.audit().createdBy()).isEqualTo(user);
        assertThat(account.version()).isZero();
    }

    @Test
    void typesKnowTheirNormalSideAndWhetherTheyCloseAtYearEnd() {
        assertThat(AccountType.ASSET.debitNormal()).isTrue();
        assertThat(AccountType.EXPENSE.debitNormal()).isTrue();
        assertThat(AccountType.LIABILITY.debitNormal()).isFalse();
        assertThat(AccountType.EQUITY.debitNormal()).isFalse();
        assertThat(AccountType.INCOME.debitNormal()).isFalse();
        assertThat(AccountType.INCOME.isProfitAndLoss()).isTrue();
        assertThat(AccountType.EXPENSE.isProfitAndLoss()).isTrue();
        assertThat(AccountType.ASSET.isProfitAndLoss()).isFalse();
        assertThat(AccountType.EQUITY.isProfitAndLoss()).isFalse();
        assertThat(AccountType.LIABILITY.isProfitAndLoss()).isFalse();
        assertThat(SourceType.MANUAL.isManual()).isTrue();
        assertThat(SourceType.REVERSAL.isManual()).isTrue();
        assertThat(SourceType.SALE.isManual()).isFalse();
    }
}
