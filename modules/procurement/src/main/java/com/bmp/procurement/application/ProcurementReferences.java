package com.bmp.procurement.application;

import com.bmp.catalog.api.CatalogQueries;
import com.bmp.catalog.api.dto.PackagingUnitInfo;
import com.bmp.catalog.api.dto.VariantInfo;
import com.bmp.party.api.PartyQueries;
import com.bmp.party.api.dto.PartyInfo;
import com.bmp.procurement.domain.error.ProcurementErrorCode;
import com.bmp.procurement.domain.model.Purchasable;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import com.bmp.tenant.api.TenantQueries;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Checks what procurement documents refer to, through the other modules' APIs. */
@Component
public class ProcurementReferences {

    private final PartyQueries parties;
    private final CatalogQueries catalog;
    private final TenantQueries tenants;

    public ProcurementReferences(PartyQueries parties, CatalogQueries catalog, TenantQueries tenants) {
        this.parties = parties;
        this.catalog = catalog;
        this.tenants = tenants;
    }

    /** An active supplier (SUPPLIER or BOTH). */
    public PartyInfo supplier(UUID supplierId) {
        return parties.findParty(PartyId.of(supplierId))
                .filter(p -> p.active() && p.isSupplier())
                .orElseThrow(() -> new BusinessRuleViolation(
                        ProcurementErrorCode.UNKNOWN_SUPPLIER, Map.of("supplierId", supplierId)));
    }

    /** Whether the party is an active supplier (SUPPLIER or BOTH). */
    public boolean isActiveSupplier(UUID partyId) {
        return parties.findParty(PartyId.of(partyId))
                .filter(p -> p.active() && p.isSupplier())
                .isPresent();
    }

    public boolean isActiveLocation(UUID locationId) {
        return tenants.isActiveLocation(LocationId.of(locationId));
    }

    public void location(UUID locationId) {
        if (!tenants.isActiveLocation(LocationId.of(locationId))) {
            throw new BusinessRuleViolation(ProcurementErrorCode.UNKNOWN_LOCATION, Map.of("locationId", locationId));
        }
    }

    /** Rule PRO-01: a packaging unit the variant can be bought in. */
    public Purchasable purchasable(UUID variantId, UUID packagingUnitId) {
        VariantInfo variant = catalog.findVariant(VariantId.of(variantId))
                .filter(VariantInfo::active)
                .orElseThrow(() -> new BusinessRuleViolation(
                        ProcurementErrorCode.UNKNOWN_VARIANT, Map.of("variantId", variantId)));
        PackagingUnitInfo unit = variant.packagingUnits().stream()
                .filter(u -> u.id().value().equals(packagingUnitId))
                .findFirst()
                .orElseThrow(() -> new BusinessRuleViolation(
                        ProcurementErrorCode.UNKNOWN_PACKAGING_UNIT, Map.of("packagingUnitId", packagingUnitId)));
        if (!unit.canBuy() || !unit.active()) {
            throw new BusinessRuleViolation(
                    ProcurementErrorCode.UNIT_NOT_PURCHASABLE, Map.of("packagingUnitId", packagingUnitId));
        }
        return new Purchasable(variantId, packagingUnitId, unit.qtyInBase(), unit.stockState(), variant.trackExpiry());
    }

    /** The first active packaging unit the variant can be bought in, if any (PRO-06). */
    public java.util.Optional<Purchasable> firstPurchasable(UUID variantId) {
        return catalog.findVariant(VariantId.of(variantId))
                .filter(VariantInfo::active)
                .flatMap(v -> v.packagingUnits().stream()
                        .filter(u -> u.canBuy() && u.active())
                        .findFirst()
                        .map(u -> new Purchasable(
                                variantId, u.id().value(), u.qtyInBase(), u.stockState(), v.trackExpiry())));
    }
}
