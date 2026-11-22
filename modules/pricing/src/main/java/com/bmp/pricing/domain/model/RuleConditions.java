package com.bmp.pricing.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * When a price rule applies (PRC-06): every given condition must hold; an empty list or {@code null} matches
 * everything.
 *
 * @param channel the sales channel
 * @param priceListIds the list the price came from
 * @param customerGroupIds the customer group
 * @param customerIds the customer
 * @param productIds the variant's product
 * @param variantIds the variant
 * @param packagingUnitIds the packaging unit sold
 * @param minQtyBase the line quantity in base units at least (300 pieces = 10 trays)
 */
@Embeddable
public record RuleConditions(
        @Enumerated(EnumType.STRING) @Column(name = "cond_channel") @Nullable
        SalesChannel channel,

        @Column(name = "cond_price_list_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> priceListIds,

        @Column(name = "cond_customer_group_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> customerGroupIds,

        @Column(name = "cond_customer_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> customerIds,

        @Column(name = "cond_product_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> productIds,

        @Column(name = "cond_variant_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> variantIds,

        @Column(name = "cond_packaging_unit_ids", nullable = false) @JdbcTypeCode(SqlTypes.ARRAY)
        List<UUID> packagingUnitIds,

        @Column(name = "cond_min_qty_base", precision = 20, scale = 6) @Nullable
        BigDecimal minQtyBase) {

    public static final RuleConditions ALWAYS =
            new RuleConditions(null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null);

    public RuleConditions {
        priceListIds = List.copyOf(priceListIds);
        customerGroupIds = List.copyOf(customerGroupIds);
        customerIds = List.copyOf(customerIds);
        productIds = List.copyOf(productIds);
        variantIds = List.copyOf(variantIds);
        packagingUnitIds = List.copyOf(packagingUnitIds);
    }

    /** Whether the line matches every condition. */
    public boolean matches(RuleContext line) {
        return (channel == null || channel == line.channel())
                && contains(priceListIds, line.priceListId())
                && contains(customerGroupIds, line.customerGroupId())
                && contains(customerIds, line.customerId())
                && contains(productIds, line.productId())
                && contains(variantIds, line.variantId())
                && contains(packagingUnitIds, line.packagingUnitId())
                && (minQtyBase == null || line.qtyBase().compareTo(minQtyBase) >= 0);
    }

    private static boolean contains(List<UUID> allowed, @Nullable UUID value) {
        return allowed.isEmpty() || (value != null && allowed.contains(value));
    }
}
