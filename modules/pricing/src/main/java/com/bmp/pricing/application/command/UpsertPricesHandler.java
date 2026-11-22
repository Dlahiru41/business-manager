package com.bmp.pricing.application.command;

import com.bmp.pricing.application.PriceChangeGuard;
import com.bmp.pricing.application.PricingEvents;
import com.bmp.pricing.application.PricingLoader;
import com.bmp.pricing.application.query.PriceChangeView;
import com.bmp.pricing.application.query.PriceListVersionView;
import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.pricing.domain.model.Price;
import com.bmp.pricing.domain.model.PriceKey;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.VariantRef;
import com.bmp.pricing.domain.port.PriceListRepository;
import com.bmp.pricing.domain.port.PriceListVersionLog;
import com.bmp.pricing.domain.port.PriceRepository;
import com.bmp.pricing.domain.port.VariantRefStore;
import com.bmp.pricing.domain.service.PriceSchedule;
import com.bmp.shared.context.ActorContext;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.IdGenerator;
import com.bmp.shared.id.UserId;
import com.bmp.web.etag.IfMatch;
import io.micrometer.observation.annotation.Observed;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Creates and changes up to 1,000 prices of a list in one new version (PRC-02, PRC-03, PRC-08): validates every row
 * first, applies them in one transaction under the list's row lock, records the version with its change summary and
 * publishes {@code PriceListChanged}.
 */
@Service
public class UpsertPricesHandler {

    private final PricingLoader loader;
    private final PriceListRepository lists;
    private final PriceRepository prices;
    private final PriceListVersionLog versions;
    private final VariantRefStore variants;
    private final PriceChangeGuard guard;
    private final PricingEvents events;
    private final IdGenerator ids;
    private final Clock clock;
    private final JsonMapper json;

    public UpsertPricesHandler(
            PricingLoader loader,
            PriceListRepository lists,
            PriceRepository prices,
            PriceListVersionLog versions,
            VariantRefStore variants,
            PriceChangeGuard guard,
            PricingEvents events,
            IdGenerator ids,
            Clock clock,
            JsonMapper json) {
        this.loader = loader;
        this.lists = lists;
        this.prices = prices;
        this.versions = versions;
        this.variants = variants;
        this.guard = guard;
        this.events = events;
        this.ids = ids;
        this.clock = clock;
        this.json = json;
    }

    /**
     * One requested price.
     *
     * @param minQty in the packaging unit; default 1
     */
    public record PriceInput(
            UUID variantId,
            UUID packagingUnitId,
            @Nullable BigDecimal minQty,
            BigDecimal amount,
            @Nullable Instant validFrom,
            @Nullable Instant validTo) {}

    /**
     * A bulk upsert.
     *
     * @param expectedPriceVersion the list price version from {@code If-Match}
     */
    public record UpsertPrices(
            UUID priceListId,
            long expectedPriceVersion,
            @Nullable String note,
            List<PriceInput> prices) {
        public UpsertPrices {
            prices = List.copyOf(prices);
        }
    }

    @Transactional
    @Observed(name = "bmp.pricing.upsert-prices")
    public PriceListVersionView handle(UpsertPrices command) {
        guard.requireAllowed();
        PriceList list = loader.lockedList(command.priceListId());
        IfMatch.check(command.expectedPriceVersion(), list.currentVersion());
        List<PriceSchedule.Row> rows = rows(command.prices());
        List<UUID> unitIds =
                rows.stream().map(r -> r.key().packagingUnitId()).distinct().toList();
        List<Price> existing = prices.findByListsAndUnits(List.of(list.id()), unitIds);
        Instant now = clock.instant();
        int version = list.nextVersion();
        List<PriceSchedule.Change> changes = PriceSchedule.apply(
                TenantContext.require().value(), list.id(), rows, existing, now, version, ids::newUuid);
        prices.saveAll(
                changes.stream().map(PriceSchedule.Change::price).distinct().toList());
        PriceList saved = lists.saveAndFlush(list);
        List<PriceChangeView> summary =
                changes.stream().map(PriceChangeView::of).toList();
        UserId actor = ActorContext.currentOrSystem().userId();
        UUID actorId = actor != null ? actor.value() : null;
        versions.append(
                saved.id(), version, now, actorId, command.note(), summary.size(), json.writeValueAsString(summary));
        events.priceListChanged(saved, changes);
        return new PriceListVersionView(saved.id(), version, now, actorId, command.note(), summary.size(), summary);
    }

    /** Rule PRC-02: every row names a variant and one of its packaging units known from the catalog. */
    private List<PriceSchedule.Row> rows(List<PriceInput> inputs) {
        Map<UUID, VariantRef> known = variants.findAll(
                inputs.stream().map(PriceInput::variantId).distinct().toList());
        return inputs.stream()
                .map(input -> {
                    VariantRef ref = known.get(input.variantId());
                    if (ref == null) {
                        throw new BusinessRuleViolation(
                                PricingErrorCode.UNKNOWN_VARIANT, Map.of("variantId", input.variantId()));
                    }
                    if (ref.unit(input.packagingUnitId()).isEmpty()) {
                        throw new BusinessRuleViolation(
                                PricingErrorCode.UNKNOWN_PACKAGING_UNIT,
                                Map.of("packagingUnitId", input.packagingUnitId()));
                    }
                    BigDecimal minQty = input.minQty();
                    return new PriceSchedule.Row(
                            new PriceKey(
                                    input.variantId(),
                                    input.packagingUnitId(),
                                    minQty != null ? minQty : BigDecimal.ONE),
                            input.amount(),
                            input.validFrom(),
                            input.validTo());
                })
                .toList();
    }
}
