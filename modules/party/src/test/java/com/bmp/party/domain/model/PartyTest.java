package com.bmp.party.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.party.domain.error.PartyErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ValidationFailure;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PartyTest {

    private static final TenantId TENANT = TenantId.of(UUID.randomUUID());
    private static final Instant NOW = Instant.parse("2026-11-23T09:00:00Z");
    private static final Address ADDRESS = new Address("12 Main Street", null, "Kegalle", null, null, "LK");

    private static Party supplier() {
        return Party.create(
                UUID.randomUUID(),
                TENANT,
                PartyType.SUPPLIER,
                "  Kegalle Poultry Farm ",
                "+94771234567",
                "orders@kegallepoultry.lk",
                "134567890-7000",
                ADDRESS,
                null);
    }

    private static void assertAnonymised(Runnable change) {
        assertThatThrownBy(change::run)
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PartyErrorCode.PARTY_ANONYMISED));
    }

    @Test
    void pty01ANewPartyIsActiveWithoutCreditAndATrimmedName() {
        Party party = supplier();

        assertThat(party.displayName()).isEqualTo("Kegalle Poultry Farm");
        assertThat(party.isActive()).isTrue();
        assertThat(party.status()).isEqualTo(PartyStatus.ACTIVE);
        assertThat(party.creditPolicy()).isEqualTo(CreditPolicy.none());
        assertThat(party.version()).isZero();
        assertThat(party.address()).isEqualTo(ADDRESS);
        assertThat(party.anonymised()).isFalse();
        assertThat(Party.create(
                                UUID.randomUUID(),
                                TENANT,
                                PartyType.CUSTOMER,
                                "Walk-in",
                                null,
                                null,
                                null,
                                new Address(null, null, null, null, null, null),
                                null)
                        .address())
                .as("an empty address is not stored")
                .isNull();
        assertThatThrownBy(() ->
                        Party.create(UUID.randomUUID(), TENANT, PartyType.CUSTOMER, "  ", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pty01PartiesChangeFieldByField() {
        Party party = supplier();
        UUID group = UUID.randomUUID();

        party.changeType(PartyType.BOTH);
        party.rename("Kegalle Farm");
        party.changePhone("+94771234568");
        party.changeEmail("sales@kegalle.lk");
        party.changeTaxId("999");
        party.changeAddress(new Address(null, null, "Colombo", null, null, null));
        party.assignGroup(group);
        party.deactivate();

        assertThat(party.type()).isEqualTo(PartyType.BOTH);
        assertThat(party.displayName()).isEqualTo("Kegalle Farm");
        assertThat(party.phone()).isEqualTo("+94771234568");
        assertThat(party.email()).isEqualTo("sales@kegalle.lk");
        assertThat(party.taxId()).isEqualTo("999");
        assertThat(party.address()).isNotNull();
        assertThat(party.customerGroupId()).isEqualTo(group);
        assertThat(party.isActive()).isFalse();
        party.activate();
        assertThat(party.isActive()).isTrue();
        party.changeAddress(new Address(null, null, null, null, null, null));
        assertThat(party.address()).isNull();
    }

    @Test
    void pty03CreditTermsAreValidatedAndScaled() {
        Party party = supplier();
        party.changeCreditPolicy(new CreditPolicy(true, new BigDecimal("50000.5"), 14));

        assertThat(party.creditPolicy().creditAllowed()).isTrue();
        assertThat(party.creditPolicy().creditLimit()).isEqualByComparingTo("50000.5");
        assertThat(party.creditPolicy().creditLimit().scale()).isEqualTo(4);
        assertThat(party.creditPolicy().paymentTermsDays()).isEqualTo(14);
        assertThatThrownBy(() -> new CreditPolicy(true, new BigDecimal("-1"), 400))
                .isInstanceOfSatisfying(
                        ValidationFailure.class,
                        e -> assertThat(e.fieldErrors()).hasSize(2));
        assertThatThrownBy(() -> new CreditPolicy(true, BigDecimal.ONE, -1)).isInstanceOf(ValidationFailure.class);
        assertThat(new CreditPolicy(false, BigDecimal.ZERO, CreditPolicy.MAX_TERMS_DAYS).paymentTermsDays())
                .isEqualTo(365);
    }

    @Test
    void pty04AnonymisingRemovesThePersonalDataForGood() {
        Party party = supplier();
        party.changeCreditPolicy(new CreditPolicy(true, BigDecimal.TEN, 7));

        assertThat(party.anonymise(NOW)).isTrue();

        assertThat(party.displayName()).isEqualTo(Party.ANONYMISED_NAME);
        assertThat(party.phone()).isNull();
        assertThat(party.email()).isNull();
        assertThat(party.taxId()).isNull();
        assertThat(party.address()).isNull();
        assertThat(party.anonymised()).isTrue();
        assertThat(party.isActive()).isFalse();
        assertThat(party.type()).as("kept").isEqualTo(PartyType.SUPPLIER);
        assertThat(party.creditPolicy().paymentTermsDays()).as("kept").isEqualTo(7);
        assertThat(party.anonymise(NOW)).as("a second time changes nothing").isFalse();
        party.deactivate();
        assertAnonymised(() -> party.rename("Back"));
        assertAnonymised(() -> party.changeType(PartyType.CUSTOMER));
        assertAnonymised(() -> party.changePhone("+94771234567"));
        assertAnonymised(() -> party.changeEmail("a@b.lk"));
        assertAnonymised(() -> party.changeTaxId("1"));
        assertAnonymised(() -> party.changeAddress(ADDRESS));
        assertAnonymised(() -> party.assignGroup(UUID.randomUUID()));
        assertAnonymised(party::activate);
        assertAnonymised(() -> party.changeCreditPolicy(CreditPolicy.none()));
    }

    @Test
    void personalDataNeverAppearsInToString() {
        Party party = supplier();

        assertThat(party.toString())
                .doesNotContain("Kegalle", "+9477", "orders@")
                .contains(party.id().toString());
        assertThat(ADDRESS.toString()).doesNotContain("Main Street");
        assertThat(ADDRESS.isEmpty()).isFalse();
    }

    @Test
    void partiesAndGroupsAreStampedWhenStored() {
        Party party = supplier();
        UserId user = UserId.of(UUID.randomUUID());
        assertThat(party.audit().createdAt()).isEqualTo(Instant.EPOCH);
        party.recordCreation(NOW, user);
        party.recordModification(NOW.plusSeconds(60), null);
        assertThat(party.audit().createdAt()).isEqualTo(NOW);
        assertThat(party.audit().updatedAt()).isEqualTo(NOW.plusSeconds(60));

        CustomerGroup group = CustomerGroup.create(UUID.randomUUID(), TENANT, "hotels", "Hotels");
        group.recordCreation(NOW, user);
        group.recordModification(NOW, user);
        group.rename("Hotels and restaurants");
        group.deactivate();
        assertThat(group.status()).isEqualTo(PartyStatus.INACTIVE);
        group.activate();
        assertThat(group.code()).isEqualTo("HOTELS");
        assertThat(group.name()).isEqualTo("Hotels and restaurants");
        assertThat(group.status()).isEqualTo(PartyStatus.ACTIVE);
        assertThat(group.version()).isZero();
        assertThat(group.audit().createdAt()).isEqualTo(NOW);
    }

    @Test
    void typesSayWhetherAPartyBuysOrSupplies() {
        assertThat(PartyType.CUSTOMER.isCustomer()).isTrue();
        assertThat(PartyType.CUSTOMER.isSupplier()).isFalse();
        assertThat(PartyType.SUPPLIER.isCustomer()).isFalse();
        assertThat(PartyType.SUPPLIER.isSupplier()).isTrue();
        assertThat(PartyType.BOTH.isCustomer()).isTrue();
        assertThat(PartyType.BOTH.isSupplier()).isTrue();
        assertThat(PartyErrorCode.DUPLICATE_PARTY_PHONE.category()).isEqualTo(ErrorCategory.CONFLICT);
        assertThat(PartyErrorCode.PARTY_PERMISSION_REQUIRED.module()).isEqualTo("party");
        assertThat(PartyErrorCode.INVALID_PHONE.code()).isEqualTo("INVALID_PHONE");
    }
}
