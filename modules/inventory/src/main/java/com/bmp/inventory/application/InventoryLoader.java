package com.bmp.inventory.application;

import com.bmp.inventory.domain.error.InventoryErrorCode;
import com.bmp.inventory.domain.model.StockCount;
import com.bmp.inventory.domain.model.TransformationRun;
import com.bmp.inventory.domain.port.StockCountRepository;
import com.bmp.inventory.domain.port.TransformationRunStore;
import com.bmp.shared.error.NotFoundException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Loads inventory records of the current tenant, or answers 404 (also for other tenants' ids). */
@Component
public class InventoryLoader {

    private final TransformationRunStore runs;
    private final StockCountRepository counts;

    public InventoryLoader(TransformationRunStore runs, StockCountRepository counts) {
        this.runs = runs;
        this.counts = counts;
    }

    public TransformationRun run(UUID id) {
        return runs.find(id)
                .orElseThrow(() ->
                        new NotFoundException(InventoryErrorCode.TRANSFORMATION_RUN_NOT_FOUND, Map.of("runId", id)));
    }

    public StockCount count(UUID id) {
        return counts.findById(id)
                .orElseThrow(
                        () -> new NotFoundException(InventoryErrorCode.STOCK_COUNT_NOT_FOUND, Map.of("countId", id)));
    }
}
