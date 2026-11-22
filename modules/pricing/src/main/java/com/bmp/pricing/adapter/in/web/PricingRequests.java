package com.bmp.pricing.adapter.in.web;

import com.bmp.pricing.adapter.in.web.generated.model.ResolvePricesRequest;
import com.bmp.pricing.adapter.in.web.generated.model.RuleConditions;
import com.bmp.pricing.adapter.in.web.generated.model.RuleEffect;
import com.bmp.pricing.adapter.in.web.generated.model.Status;
import com.bmp.pricing.adapter.in.web.generated.model.UpsertPricesRequest;
import com.bmp.pricing.application.command.UpsertPricesHandler;
import com.bmp.pricing.application.query.ResolvePricesService;
import com.bmp.pricing.domain.model.EffectType;
import com.bmp.pricing.domain.model.PricingStatus;
import com.bmp.pricing.domain.model.SalesChannel;
import com.bmp.pricing.domain.service.ResolutionRequest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Request DTOs to application commands; formats were checked by Bean Validation already (04 par. 4). */
final class PricingRequests {

    private PricingRequests() {}

    static List<UpsertPricesHandler.PriceInput> prices(UpsertPricesRequest request) {
        return request.getPrices().stream()
                .map(p -> new UpsertPricesHandler.PriceInput(
                        p.getVariantId(),
                        p.getPackagingUnitId(),
                        decimal(p.getMinQty()),
                        new BigDecimal(p.getAmount()),
                        instant(p.getValidFrom()),
                        instant(p.getValidTo())))
                .toList();
    }

    static ResolvePricesService.Basket basket(ResolvePricesRequest request) {
        return new ResolvePricesService.Basket(
                SalesChannel.valueOf(request.getChannel().getValue()),
                request.getCustomerId(),
                request.getCustomerGroupId(),
                instant(request.getAt()),
                request.getLines().stream()
                        .map(l -> new ResolutionRequest.Line(
                                l.getLineId(), l.getVariantId(), l.getPackagingUnitId(), new BigDecimal(l.getQty())))
                        .toList());
    }

    static com.bmp.pricing.domain.model.RuleConditions conditions(@Nullable RuleConditions conditions) {
        if (conditions == null) {
            return com.bmp.pricing.domain.model.RuleConditions.ALWAYS;
        }
        return new com.bmp.pricing.domain.model.RuleConditions(
                conditions.getChannel() != null
                        ? SalesChannel.valueOf(conditions.getChannel().getValue())
                        : null,
                ids(conditions.getPriceListIds()),
                ids(conditions.getCustomerGroupIds()),
                ids(conditions.getCustomerIds()),
                ids(conditions.getProductIds()),
                ids(conditions.getVariantIds()),
                ids(conditions.getPackagingUnitIds()),
                decimal(conditions.getMinQtyBase()));
    }

    static com.bmp.pricing.domain.model.RuleEffect effect(RuleEffect effect) {
        return new com.bmp.pricing.domain.model.RuleEffect(
                EffectType.valueOf(effect.getType().getValue()), new BigDecimal(effect.getValue()));
    }

    static @Nullable PricingStatus status(@Nullable Status status) {
        return status != null ? PricingStatus.valueOf(status.getValue()) : null;
    }

    static @Nullable BigDecimal decimal(@Nullable String value) {
        return value != null ? new BigDecimal(value) : null;
    }

    static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value != null ? value.toInstant() : null;
    }

    private static List<UUID> ids(@Nullable List<UUID> ids) {
        return ids != null ? ids : List.of();
    }
}
