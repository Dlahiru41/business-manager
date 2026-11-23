package com.bmp.procurement.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.bmp.catalog.api.CatalogQueries;
import com.bmp.catalog.api.dto.PackagingUnitInfo;
import com.bmp.catalog.api.dto.VariantInfo;
import com.bmp.party.api.PartyQueries;
import com.bmp.party.api.dto.PartyInfo;
import com.bmp.persistence.sequence.NumberSequences;
import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import com.bmp.tenant.api.TenantQueries;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

/** The edges of what procurement checks through other modules' APIs, and its numbering settings (PRO-01, PRO-07). */
class ProcurementReferencesTest {

    final PartyQueries parties = mock(PartyQueries.class);
    final CatalogQueries catalog = mock(CatalogQueries.class);
    final TenantQueries tenants = mock(TenantQueries.class);
    final ProcurementReferences references = new ProcurementReferences(parties, catalog, tenants);

    static void assertViolation(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    static PartyInfo party(String type, boolean active) {
        return new PartyInfo(PartyId.of(UUID.randomUUID()), type, "Farm", null, active, false, BigDecimal.ZERO, 7);
    }

    static PackagingUnitInfo unit(boolean canBuy, boolean active) {
        PackagingUnitInfo unit = mock(PackagingUnitInfo.class);
        when(unit.id()).thenReturn(PackagingUnitId.of(UUID.randomUUID()));
        when(unit.canBuy()).thenReturn(canBuy);
        when(unit.active()).thenReturn(active);
        when(unit.qtyInBase()).thenReturn(new BigDecimal("30"));
        when(unit.stockState()).thenReturn("DEFAULT");
        return unit;
    }

    VariantInfo variant(UUID id, boolean active, PackagingUnitInfo... units) {
        VariantInfo variant = mock(VariantInfo.class);
        when(variant.active()).thenReturn(active);
        when(variant.packagingUnits()).thenReturn(List.of(units));
        when(variant.trackExpiry()).thenReturn(true);
        when(catalog.findVariant(VariantId.of(id))).thenReturn(Optional.of(variant));
        return variant;
    }

    @Test
    void onlyActiveSuppliersSupply() {
        UUID both = UUID.randomUUID();
        UUID inactive = UUID.randomUUID();
        UUID customer = UUID.randomUUID();
        when(parties.findParty(PartyId.of(both))).thenReturn(Optional.of(party("BOTH", true)));
        when(parties.findParty(PartyId.of(inactive))).thenReturn(Optional.of(party("SUPPLIER", false)));
        when(parties.findParty(PartyId.of(customer))).thenReturn(Optional.of(party("CUSTOMER", true)));

        assertThat(references.supplier(both).paymentTermsDays()).isEqualTo(7);
        assertViolation(() -> references.supplier(inactive), ProcurementErrorCode.UNKNOWN_SUPPLIER);
        assertViolation(() -> references.supplier(customer), ProcurementErrorCode.UNKNOWN_SUPPLIER);
        assertViolation(() -> references.supplier(UUID.randomUUID()), ProcurementErrorCode.UNKNOWN_SUPPLIER);
    }

    @Test
    void pro01OnlyActiveBuyableUnitsOfActiveVariantsAreBought() {
        UUID id = UUID.randomUUID();
        PackagingUnitInfo retired = unit(true, false);
        PackagingUnitInfo sellOnly = unit(false, true);
        PackagingUnitInfo tray = unit(true, true);
        variant(id, true, retired, sellOnly, tray);
        UUID inactive = UUID.randomUUID();
        variant(inactive, false, unit(true, true));

        assertViolation(
                () -> references.purchasable(id, retired.id().value()), ProcurementErrorCode.UNIT_NOT_PURCHASABLE);
        assertViolation(
                () -> references.purchasable(id, sellOnly.id().value()), ProcurementErrorCode.UNIT_NOT_PURCHASABLE);
        assertThat(references.purchasable(id, tray.id().value()).trackExpiry()).isTrue();
        assertViolation(
                () -> references.purchasable(inactive, tray.id().value()), ProcurementErrorCode.UNKNOWN_VARIANT);
        assertThat(references.firstPurchasable(id).orElseThrow().packagingUnitId())
                .as("the first active buyable unit")
                .isEqualTo(tray.id().value());
        assertThat(references.firstPurchasable(inactive)).isEmpty();
        UUID nothingToBuy = UUID.randomUUID();
        variant(nothingToBuy, true, sellOnly);
        assertThat(references.firstPurchasable(nothingToBuy)).isEmpty();
        assertThat(references.firstPurchasable(UUID.randomUUID())).isEmpty();
    }

    @Test
    void pro07NumbersUseTheTenantsPrefixOrTheDefault() {
        NumberSequences numbers = mock(NumberSequences.class);
        when(numbers.next("PURCHASE_ORDER")).thenReturn(7L);
        when(numbers.next("GOODS_RECEIPT")).thenReturn(1234567L);
        ProcurementSettings settings = new ProcurementSettings(tenants, numbers);
        when(tenants.setting("numbering", "purchaseOrderPrefix")).thenReturn(Optional.empty());
        when(tenants.setting("numbering", "goodsReceiptPrefix")).thenReturn(Optional.of("\"KPF-GRN-\""));

        assertThat(settings.nextOrderNumber()).isEqualTo("PO-000007");
        assertThat(settings.nextReceiptNumber())
                .as("more than six digits are kept")
                .isEqualTo("KPF-GRN-1234567");
        assertThat(ProcurementSettings.unquoted(" \"PO-\" ")).isEqualTo("PO-");
        assertThat(ProcurementSettings.unquoted("PO-")).isEqualTo("PO-");
        assertThat(ProcurementSettings.unquoted("\"PO-")).isEqualTo("\"PO-");
        assertThat(ProcurementSettings.unquoted("\"")).isEqualTo("\"");
        when(tenants.currentTenant()).thenReturn(Optional.empty());
        assertThatThrownBy(settings::currency).isInstanceOf(TechnicalException.class);
    }
}
