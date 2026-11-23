package com.bmp.inventory.application.query;

import com.bmp.inventory.application.InventoryLoader;
import com.bmp.inventory.domain.model.CountStatus;
import com.bmp.inventory.domain.model.StockCount;
import com.bmp.inventory.domain.model.TransformationRun;
import com.bmp.inventory.domain.port.StockCountRepository;
import com.bmp.inventory.domain.port.TransformationRunStore;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.pagination.CursorCodec;
import com.bmp.web.pagination.PageQuery;
import com.bmp.web.pagination.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read models of transformation runs and stock counts. */
@Service
public class InventoryQueryService {

    private final TransformationRunStore runs;
    private final StockCountRepository counts;
    private final InventoryLoader loader;

    public InventoryQueryService(TransformationRunStore runs, StockCountRepository counts, InventoryLoader loader) {
        this.runs = runs;
        this.counts = counts;
        this.loader = loader;
    }

    @Transactional(readOnly = true)
    public PageResponse<TransformationRun> runs(
            @Nullable UUID definitionId, @Nullable UUID locationId, PageQuery query) {
        List<TransformationRun> page = runs.findPage(definitionId, locationId, before(query), query.fetchSize());
        return PageResponse.of(page, query, r -> List.of(r.id().toString()));
    }

    @Transactional(readOnly = true)
    public TransformationRun run(UUID id) {
        return loader.run(id);
    }

    @Transactional(readOnly = true)
    public PageResponse<StockCount> counts(@Nullable CountStatus status, @Nullable UUID locationId, PageQuery query) {
        List<StockCount> page = counts.findPage(status, locationId, before(query), query.fetchSize());
        page.forEach(StockCount::lines);
        return PageResponse.of(page, query, c -> List.of(c.id().toString()));
    }

    @Transactional(readOnly = true)
    public StockCount count(UUID id) {
        StockCount count = loader.count(id);
        count.lines();
        return count;
    }

    /** The id an id-cursor points after, or {@code null} on the first page. */
    public static @Nullable UUID before(PageQuery query) {
        String cursor = query.cursor();
        if (cursor == null) {
            return null;
        }
        try {
            return UUID.fromString(CursorCodec.decode(cursor, 1).getFirst());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleViolation(
                    PlatformErrorCode.INVALID_PARAMETER, "The cursor is invalid", Map.of("parameter", "cursor"));
        }
    }
}
