package com.bmp.inventory.application.command;

import com.bmp.inventory.application.StockReferences;
import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.ReorderPolicy;
import com.bmp.inventory.domain.port.BalanceStore;
import com.bmp.inventory.domain.port.ReorderPolicyStore;
import com.bmp.inventory.domain.port.StockItemStore;
import com.bmp.inventory.domain.service.ReorderRule;
import com.bmp.shared.error.NotFoundException;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reorder levels per variant and location (INV-13). */
@Service
public class ReorderPolicyHandler {

    private static final Logger log = LoggerFactory.getLogger(ReorderPolicyHandler.class);

    private final ReorderPolicyStore policies;
    private final StockItemStore items;
    private final BalanceStore balances;
    private final StockReferences references;

    public ReorderPolicyHandler(
            ReorderPolicyStore policies, StockItemStore items, BalanceStore balances, StockReferences references) {
        this.policies = policies;
        this.items = items;
        this.balances = balances;
        this.references = references;
    }

    /**
     * Creates or replaces the policy; an existing one changes only under its version ({@code If-Match}). The low flag
     * clears when stock is above the new level; an alert comes with the next out-movement at or below it (INV-13).
     */
    @Transactional
    @Observed(name = "bmp.inventory.reorder-policy")
    public ReorderPolicy set(
            UUID variantId,
            UUID locationId,
            BigDecimal reorderLevel,
            BigDecimal reorderQty,
            @Nullable UUID preferredSupplierId,
            @Nullable String ifMatch) {
        if (items.find(variantId).isEmpty()) {
            throw new NotFoundException(InventoryErrorCode.STOCK_ITEM_NOT_FOUND, Map.of("variantId", variantId));
        }
        references.location(locationId);
        Optional<ReorderPolicy> existing = policies.lock(variantId, locationId);
        existing.ifPresent(p -> IfMatch.check(IfMatch.expectedVersion(ifMatch), p.version()));
        boolean low = existing.map(ReorderPolicy::lowFlag).orElse(false)
                && ReorderRule.decide(balances.available(variantId, locationId), reorderLevel, true, false)
                        != ReorderRule.Decision.CLEAR;
        ReorderPolicy saved = policies.save(
                new ReorderPolicy(variantId, locationId, reorderLevel, reorderQty, preferredSupplierId, low, 0));
        log.atInfo()
                .addKeyValue("variantId", variantId)
                .addKeyValue("locationId", locationId)
                .log("Reorder policy set");
        return saved;
    }
}
