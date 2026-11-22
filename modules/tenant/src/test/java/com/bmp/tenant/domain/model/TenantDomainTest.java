package com.bmp.tenant.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.event.EventStamp;
import com.bmp.shared.event.EventStampSource;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.tenant.api.events.LocationCreated;
import com.bmp.tenant.api.events.TenantCreated;
import com.bmp.tenant.domain.error.TenantErrorCode;
import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TenantDomainTest {

    private static final Instant NOW = Instant.parse("2026-11-21T09:00:00Z");
    private static final EventStampSource STAMPS = () -> new EventStamp(UUID.randomUUID(), NOW, EventMetadata.empty());
    private static final Tenant.Regional COLOMBO = new Tenant.Regional(CurrencyCode.LKR, ZoneId.of("Asia/Colombo"), 0);

    private static Tenant tenant() {
        return Tenant.create(
                TenantId.of(UUID.randomUUID()),
                new Tenant.TenantProfile("Perera Stores", null, "RETAIL"),
                COLOMBO,
                "STANDARD",
                null,
                STAMPS);
    }

    @Test
    void creatingATenantRecordsTenantCreatedOnce() {
        Tenant tenant = tenant();

        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.pullEvents()).singleElement().isInstanceOfSatisfying(TenantCreated.class, e -> {
            assertThat(e.tenantId()).isEqualTo(tenant.id());
            assertThat(e.currency()).isEqualTo("LKR");
            assertThat(e.timeZone()).isEqualTo("Asia/Colombo");
            assertThat(e.occurredAt()).isEqualTo(NOW);
        });
        assertThat(tenant.pullEvents()).isEmpty();
        assertThat(tenant.plan()).isEqualTo("STANDARD");
    }

    @Test
    void ten04OnlyActiveTenantsCanBeSuspendedAndOnlySuspendedOnesReactivated() {
        Tenant tenant = tenant();

        assertThatThrownBy(tenant::reactivate)
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(TenantErrorCode.INVALID_TENANT_STATUS_TRANSITION));
        tenant.suspend();
        assertThat(tenant.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThatThrownBy(tenant::suspend).isInstanceOf(BusinessRuleViolation.class);
        tenant.reactivate();
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
    }

    @Test
    void profileAndRegionalChangesReplaceTheCurrentValues() {
        Tenant tenant = tenant();

        tenant.updateProfile(new Tenant.TenantProfile("Perera", "Perera (Pvt) Ltd", "WHOLESALE"));
        tenant.changeRegional(new Tenant.Regional(CurrencyCode.of("USD"), ZoneId.of("UTC"), 4));

        assertThat(tenant.profile().legalName()).isEqualTo("Perera (Pvt) Ltd");
        assertThat(tenant.regional().currency().code()).isEqualTo("USD");
        assertThat(tenant.regional().businessDayCutoffHour()).isEqualTo(4);
    }

    @Test
    void regionalSettingsRejectAnInvalidCutoffHour() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tenant.Regional(CurrencyCode.LKR, ZoneId.of("UTC"), -1));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new Tenant.Regional(CurrencyCode.LKR, ZoneId.of("UTC"), 24));
    }

    @Test
    void auditStampsAreSetByThePersistenceLayer() {
        Tenant tenant = tenant();
        UserId user = UserId.of(UUID.randomUUID());

        assertThat(tenant.createdAt()).as("before the first insert").isEqualTo(Instant.EPOCH);
        tenant.recordCreation(NOW, user);
        tenant.recordModification(NOW.plusSeconds(60), null);

        assertThat(tenant.createdAt()).isEqualTo(NOW);
        assertThat(tenant.createdBefore(NOW.plusSeconds(1))).isTrue();
        assertThat(tenant.createdBefore(NOW)).isFalse();
        AuditStamps stamps = new AuditStamps();
        assertThat(stamps.updatedAt()).as("falls back to creation").isEqualTo(Instant.EPOCH);
    }

    @Test
    void locationsAreCreatedActiveAndCanBeDeactivatedAndReactivated() {
        TenantId tenant = TenantId.of(UUID.randomUUID());
        Location location =
                Location.create(LocationId.of(UUID.randomUUID()), tenant, "Store", LocationType.STORE, null, STAMPS);

        assertThat(location.pullEvents())
                .singleElement()
                .isInstanceOfSatisfying(
                        LocationCreated.class, e -> assertThat(e.type()).isEqualTo("STORE"));
        assertThat(location.isActive()).isTrue();
        location.deactivate();
        assertThat(location.isActive()).isFalse();
        assertThat(location.status()).isEqualTo(LocationStatus.INACTIVE);
        location.activate();
        location.rename("Back store");
        location.changeType(LocationType.WAREHOUSE);
        location.changeAddress("{\"city\":\"Kandy\"}");
        location.recordCreation(NOW, null);
        location.recordModification(NOW, UserId.of(UUID.randomUUID()));
        assertThat(location.name()).isEqualTo("Back store");
        assertThat(location.address()).contains("Kandy");
        assertThat(location.createdAt()).isEqualTo(NOW);
    }

    @Test
    void receiptLayoutsDefaultEmptyPartsAndRejectUnsupportedValues() {
        var layout = new ReceiptTemplate.Layout("80 mm", 80, " ", "", null, "si");

        assertThat(layout.header()).isEqualTo("{}");
        assertThat(layout.footer()).isEqualTo("{}");
        assertThat(layout.fields()).isEqualTo("{}");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReceiptTemplate.Layout("x", 72, "{}", "{}", "{}", "en"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ReceiptTemplate.Layout("x", 58, "{}", "{}", "{}", "fr"));
    }

    @Test
    void receiptTemplatesTrackTheDefaultFlag() {
        var template = new ReceiptTemplate(
                ReceiptTemplateId.of(UUID.randomUUID()),
                TenantId.of(UUID.randomUUID()),
                new ReceiptTemplate.Layout("80 mm", 80, "{}", "{}", "{}", "en"),
                false);

        template.markDefault();
        assertThat(template.isDefault()).isTrue();
        template.unmarkDefault();
        template.changeLayout(new ReceiptTemplate.Layout("58 mm", 58, "{}", "{}", "{}", "ta"));
        template.recordCreation(NOW, null);
        template.recordModification(NOW, null);
        assertThat(template.isDefault()).isFalse();
        assertThat(template.layout().paperWidthMm()).isEqualTo(58);
        assertThat(template.id().toString()).hasSize(36);
    }

    @Test
    void settingGroupsAreAddressedByLowerCaseCodes() {
        assertThat(SettingGroup.fromCode("sales")).contains(SettingGroup.SALES);
        assertThat(SettingGroup.fromCode("SALES")).isEmpty();
        assertThat(SettingGroup.NUMBERING.code()).isEqualTo("numbering");
        assertThat(TenantErrorCode.SETTING_LOCKED.module()).isEqualTo("tenant");
    }
}
