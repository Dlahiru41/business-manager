package com.bmp.pricing.application.query;

import com.bmp.pricing.application.PricingLoader;
import com.bmp.pricing.domain.error.PricingErrorCode;
import com.bmp.pricing.domain.model.ListChannel;
import com.bmp.pricing.domain.model.PriceList;
import com.bmp.pricing.domain.model.PriceRule;
import com.bmp.pricing.domain.model.PricingStatus;
import com.bmp.pricing.domain.model.RuleKind;
import com.bmp.pricing.domain.port.PriceListRepository;
import com.bmp.pricing.domain.port.PriceListVersionLog;
import com.bmp.pricing.domain.port.PriceRepository;
import com.bmp.pricing.domain.port.PriceRuleRepository;
import com.bmp.pricing.domain.port.TaxCategoryRepository;
import com.bmp.pricing.domain.port.TaxRateRepository;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.NotFoundException;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Read side of pricing: lists, prices, versions, snapshots (PRC-09), rules and taxes. */
@Service
public class PricingQueryService {

    private static final TypeReference<List<PriceChangeView>> CHANGES = new TypeReference<>() {};

    private final PriceListRepository lists;
    private final PriceRepository prices;
    private final PriceListVersionLog versions;
    private final PriceRuleRepository rules;
    private final TaxCategoryRepository categories;
    private final TaxRateRepository rates;
    private final PricingLoader loader;
    private final JsonMapper json;
    private final Clock clock;

    public PricingQueryService(
            PriceListRepository lists,
            PriceRepository prices,
            PriceListVersionLog versions,
            PriceRuleRepository rules,
            TaxCategoryRepository categories,
            TaxRateRepository rates,
            PricingLoader loader,
            JsonMapper json,
            Clock clock) {
        this.lists = lists;
        this.prices = prices;
        this.versions = versions;
        this.rules = rules;
        this.categories = categories;
        this.rates = rates;
        this.loader = loader;
        this.json = json;
        this.clock = clock;
    }

    /** Lists by ascending priority then code; a tenant has few, so the page is cut from the ordered set. */
    @Transactional(readOnly = true)
    public PageResponse<PriceListView> priceLists(
            @Nullable ListChannel channel, @Nullable PricingStatus status, PageQuery query) {
        List<PriceListView> all = lists.findAll().stream()
                .filter(l -> channel == null || l.channel() == channel)
                .filter(l -> status == null || l.status() == status)
                .map(PriceListView::of)
                .toList();
        return page(all, PriceListView::id, query);
    }

    @Transactional(readOnly = true)
    public PriceListView priceList(UUID id) {
        return PriceListView.of(loader.list(id));
    }

    /** A page of prices with the list's price version, read first (see {@link PricePageView}). */
    @Transactional(readOnly = true)
    public PricePageView prices(
            UUID listId, @Nullable UUID variantId, @Nullable UUID unitId, @Nullable Instant at, PageQuery query) {
        PriceList list = loader.list(listId);
        int version = list.currentVersion();
        List<PriceView> page =
                prices.findPage(listId, variantId, unitId, at, afterId(query), query.fetchSize()).stream()
                        .map(p -> PriceView.of(p, list.currency()))
                        .toList();
        return new PricePageView(version, PageResponse.of(page, query, p -> List.of(p.id().toString())));
    }

    @Transactional(readOnly = true)
    public PageResponse<PriceListVersionView> versions(UUID listId, PageQuery query) {
        loader.list(listId);
        String cursor = query.cursor();
        Integer before =
                cursor != null ? parseVersion(CursorCodec.decode(cursor, 1).getFirst()) : null;
        List<PriceListVersionView> page = versions.findPage(listId, before, query.fetchSize()).stream()
                .map(e -> new PriceListVersionView(
                        e.priceListId(),
                        e.version(),
                        e.publishedAt(),
                        e.publishedBy(),
                        e.note(),
                        e.changeCount(),
                        null))
                .toList();
        return PageResponse.of(page, query, v -> List.of(String.valueOf(v.version())));
    }

    @Transactional(readOnly = true)
    public PriceListVersionView version(UUID listId, int version) {
        loader.list(listId);
        PriceListVersionLog.Entry entry = versions.find(listId, version)
                .orElseThrow(() -> new NotFoundException(
                        PricingErrorCode.PRICE_LIST_VERSION_NOT_FOUND,
                        Map.of("priceListId", listId, "version", version)));
        return new PriceListVersionView(
                entry.priceListId(),
                entry.version(),
                entry.publishedAt(),
                entry.publishedBy(),
                entry.note(),
                entry.changeCount(),
                json.readValue(entry.changesJson(), CHANGES));
    }

    /** The list's price version (the ETag of prices and snapshots). */
    @Transactional(readOnly = true)
    public int priceVersion(UUID listId) {
        return loader.list(listId).currentVersion();
    }

    /**
     * Rule PRC-09: every price not ended yet, or every price changed after {@code sinceVersion} (ended ones
     * included, so a till drops them).
     */
    @Transactional(readOnly = true)
    public PriceSnapshotView snapshot(UUID listId, @Nullable Integer sinceVersion) {
        PriceList list = loader.list(listId);
        List<PriceView> changed = (sinceVersion == null
                        ? prices.findNotEndedAt(listId, clock.instant())
                        : prices.findChangedSince(listId, sinceVersion))
                .stream().map(p -> PriceView.of(p, list.currency())).toList();
        return new PriceSnapshotView(
                PriceListView.of(list), list.currentVersion(), sinceVersion, sinceVersion == null, changed);
    }

    @Transactional(readOnly = true)
    public PageResponse<PriceRuleView> rules(@Nullable RuleKind kind, @Nullable PricingStatus status, PageQuery query) {
        List<PriceRuleView> all = rules.findAll().stream()
                .filter(r -> kind == null || r.kind() == kind)
                .filter(r -> status == null || r.status() == status)
                .map(PriceRuleView::of)
                .toList();
        return page(all, PriceRuleView::id, query);
    }

    @Transactional(readOnly = true)
    public PriceRuleView rule(UUID id) {
        PriceRule rule = loader.rule(id);
        return PriceRuleView.of(rule);
    }

    @Transactional(readOnly = true)
    public PageResponse<TaxCategoryView> taxCategories(PageQuery query) {
        String cursor = query.cursor();
        String after = cursor != null ? CursorCodec.decode(cursor, 1).getFirst() : null;
        List<TaxCategoryView> page = categories.findPage(after, query.fetchSize()).stream()
                .map(TaxCategoryView::of)
                .toList();
        return PageResponse.of(page, query, c -> List.of(c.code()));
    }

    @Transactional(readOnly = true)
    public TaxCategoryView taxCategory(UUID id) {
        return TaxCategoryView.of(loader.category(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaxRateView> taxRates(@Nullable UUID categoryId, PageQuery query) {
        List<TaxRateView> page = rates.findPage(categoryId, afterId(query), query.fetchSize()).stream()
                .map(TaxRateView::of)
                .toList();
        return PageResponse.of(page, query, r -> List.of(r.id().toString()));
    }

    @Transactional(readOnly = true)
    public TaxRateView taxRate(UUID id) {
        return TaxRateView.of(loader.rate(id));
    }

    /** Cuts a page out of a small, already ordered set; the cursor is the id of the last item. */
    private static <T> PageResponse<T> page(List<T> ordered, Function<T, UUID> id, PageQuery query) {
        int start = 0;
        UUID after = afterId(query);
        if (after != null) {
            for (int i = 0; i < ordered.size(); i++) {
                if (id.apply(ordered.get(i)).equals(after)) {
                    start = i + 1;
                    break;
                }
            }
        }
        List<T> fetched = new ArrayList<>(ordered.subList(start, Math.min(ordered.size(), start + query.fetchSize())));
        return PageResponse.of(fetched, query, item -> List.of(id.apply(item).toString()));
    }

    private static @Nullable UUID afterId(PageQuery query) {
        String cursor = query.cursor();
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(CursorCodec.decode(cursor, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static int parseVersion(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw invalidCursor();
        }
    }

    private static BusinessRuleViolation invalidCursor() {
        return new BusinessRuleViolation(
                PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
    }
}
