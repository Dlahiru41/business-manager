package com.bmp.inventory.application.query;

import com.bmp.inventory.application.InventorySettings;
import com.bmp.inventory.application.query.StockReadModel.BalanceFilter;
import com.bmp.inventory.application.query.StockReadModel.BalanceRow;
import com.bmp.inventory.application.query.StockReadModel.Grouping;
import com.bmp.inventory.application.query.StockReadModel.MovementFilter;
import com.bmp.inventory.application.query.StockReadModel.ReorderRow;
import com.bmp.inventory.application.query.StockReadModel.ValuationRow;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Balances, movements, valuation and the reorder list (T02-07). */
@Service
public class StockQueryService {

    private final StockReadModel model;
    private final InventorySettings settings;
    private final Clock clock;

    public StockQueryService(StockReadModel model, InventorySettings settings, Clock clock) {
        this.model = model;
        this.settings = settings;
        this.clock = clock;
    }

    /** The value of the stock at a moment, in the tenant's currency. */
    public record Valuation(
            Instant asOf,
            Grouping groupBy,
            @Nullable UUID locationId,
            List<ValuationRow> rows,
            BigDecimal total,
            String currency) {}

    @Transactional(readOnly = true)
    public PageResponse<BalanceRow> balances(BalanceFilter filter, PageQuery query) {
        List<String> after = query.cursor() == null ? null : cursor(query, 4);
        if (after != null) {
            try {
                UUID.fromString(after.get(0));
                UUID.fromString(after.get(1));
                UUID.fromString(after.get(3));
            } catch (IllegalArgumentException e) {
                throw invalidCursor();
            }
        }
        return PageResponse.of(model.balances(filter, after, query.fetchSize()), query, BalanceRow::cursor);
    }

    @Transactional(readOnly = true)
    public PageResponse<MovementView> movements(MovementFilter filter, PageQuery query) {
        Instant beforeAt = null;
        UUID beforeId = null;
        if (query.cursor() != null) {
            List<String> values = cursor(query, 2);
            try {
                beforeAt = Instant.parse(values.get(0));
                beforeId = UUID.fromString(values.get(1));
            } catch (RuntimeException e) {
                throw invalidCursor();
            }
        }
        return PageResponse.of(
                model.movements(filter, beforeAt, beforeId, query.fetchSize()),
                query,
                m -> List.of(m.occurredAt().toString(), m.id().toString()));
    }

    /** Σ of movement costs up to {@code asOf} (default now), grouped. */
    @Transactional(readOnly = true)
    public Valuation valuation(@Nullable Instant asOf, Grouping groupBy, @Nullable UUID locationId) {
        Instant at = asOf != null ? asOf : clock.instant();
        List<ValuationRow> rows = model.valuation(at, groupBy, locationId);
        BigDecimal total = rows.stream().map(ValuationRow::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Valuation(
                at, groupBy, locationId, rows, total, settings.tenant().currency());
    }

    @Transactional(readOnly = true)
    public List<ReorderRow> reorderList(@Nullable UUID locationId) {
        return model.reorderList(locationId);
    }

    /** The tenant's currency, for amounts shown beside balances and movements. */
    @Transactional(readOnly = true)
    public String currency() {
        return settings.tenant().currency();
    }

    private static List<String> cursor(PageQuery query, int values) {
        try {
            return CursorCodec.decode(java.util.Objects.requireNonNull(query.cursor()), values);
        } catch (IllegalArgumentException e) {
            throw invalidCursor();
        }
    }

    private static BusinessRuleViolation invalidCursor() {
        return new BusinessRuleViolation(
                PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
    }
}
