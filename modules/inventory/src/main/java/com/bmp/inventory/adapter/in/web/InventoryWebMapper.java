package com.bmp.inventory.adapter.in.web;

import com.bmp.inventory.adapter.in.web.generated.model.CountStatus;
import com.bmp.inventory.adapter.in.web.generated.model.MovementType;
import com.bmp.inventory.adapter.in.web.generated.model.PostedMovements;
import com.bmp.inventory.adapter.in.web.generated.model.RunInput;
import com.bmp.inventory.adapter.in.web.generated.model.RunOutput;
import com.bmp.inventory.adapter.in.web.generated.model.StockBalance;
import com.bmp.inventory.adapter.in.web.generated.model.StockBalancePagePage;
import com.bmp.inventory.adapter.in.web.generated.model.StockBalanceStockValue;
import com.bmp.inventory.adapter.in.web.generated.model.StockCount;
import com.bmp.inventory.adapter.in.web.generated.model.StockCountLine;
import com.bmp.inventory.adapter.in.web.generated.model.StockFlag;
import com.bmp.inventory.adapter.in.web.generated.model.StockMovement;
import com.bmp.inventory.adapter.in.web.generated.model.TransformationRun;
import com.bmp.inventory.adapter.in.web.generated.model.Valuation;
import com.bmp.inventory.adapter.in.web.generated.model.ValuationGroup;
import com.bmp.inventory.adapter.in.web.generated.model.ValuationRow;
import com.bmp.inventory.application.query.MovementView;
import com.bmp.inventory.application.query.StockQueryService;
import com.bmp.inventory.application.query.StockReadModel;
import com.bmp.inventory.domain.model.RunLine;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Inventory read models to API representations: quantities become decimal strings (02 §5), money an amount with at
 * least the currency's minor units and its currency.
 */
@Mapper(componentModel = "spring")
interface InventoryWebMapper {

    default StockMovement movement(MovementView m) {
        return new StockMovement()
                .id(m.id())
                .variantId(m.variantId())
                .locationId(m.locationId())
                .stockState(m.stockState())
                .batchId(m.batchId())
                .qtyBase(decimal(m.qtyBase()))
                .unitCost(decimal(m.unitCost()))
                .costAmount(money(m.costAmount(), m.currency()))
                .type(MovementType.valueOf(m.type()))
                .reasonCode(m.reasonCode())
                .sourceType(m.sourceType())
                .sourceId(m.sourceId())
                .sourceLineId(m.sourceLineId())
                .occurredAt(time(m.occurredAt()))
                .recordedAt(time(m.recordedAt()))
                .actorId(m.actorId())
                .flags(m.flags().stream().map(StockFlag::valueOf).toList());
    }

    default PostedMovements posted(List<MovementView> movements) {
        return new PostedMovements()
                .movements(movements.stream().map(this::movement).toList());
    }

    default TransformationRun run(com.bmp.inventory.domain.model.TransformationRun run) {
        return new TransformationRun()
                .id(run.id())
                .definitionId(run.definitionId())
                .defVersion(run.defVersion())
                .kind(run.kind())
                .locationId(run.locationId())
                .runs(decimal(run.runs()))
                .inputs(run.inputs().stream().map(l -> input(l, run.currency())).toList())
                .outputs(run.outputs().stream()
                        .map(l -> output(l, run.currency()))
                        .toList())
                .yieldVarianceQty(decimal(run.yieldVarianceQty()))
                .totalCost(money(run.totalCost(), run.currency()))
                .note(run.note())
                .occurredAt(time(run.occurredAt()))
                .actorId(run.actorId())
                .flags(run.negativeStock() ? List.of(StockFlag.NEGATIVE_STOCK) : List.of());
    }

    default RunInput input(RunLine line, String currency) {
        return new RunInput()
                .variantId(line.variantId())
                .stockState(line.stockState())
                .qtyBase(decimal(line.qtyBase()))
                .costAmount(money(line.costAmount(), currency));
    }

    default RunOutput output(RunLine line, String currency) {
        BigDecimal expected = line.expectedQtyBase() != null ? line.expectedQtyBase() : line.qtyBase();
        BigDecimal share = line.costSharePct() != null ? line.costSharePct() : new BigDecimal("100");
        return new RunOutput()
                .variantId(line.variantId())
                .stockState(line.stockState())
                .expectedQtyBase(decimal(expected))
                .actualQtyBase(decimal(line.qtyBase()))
                .costSharePct(decimal(share))
                .costAmount(money(line.costAmount(), currency))
                .unitCost(decimal(line.unitCost()));
    }

    default StockCount count(com.bmp.inventory.domain.model.StockCount count, String currency) {
        return new StockCount()
                .id(count.id())
                .locationId(count.locationId())
                .status(CountStatus.valueOf(count.status().name()))
                .note(count.note())
                .variantIds(count.variantIds().isEmpty() ? null : count.variantIds())
                .lines(count.lines().stream().map(l -> line(l, currency)).toList())
                .startedAt(time(count.startedAt()))
                .submittedAt(count.submittedAt() == null ? null : time(count.submittedAt()))
                .postedAt(count.postedAt() == null ? null : time(count.postedAt()))
                .postedBy(count.postedBy())
                .version(count.version());
    }

    default StockCountLine line(com.bmp.inventory.domain.model.StockCountLine line, String currency) {
        BigDecimal variance = line.varianceQty();
        BigDecimal cost = line.varianceCost();
        return new StockCountLine()
                .id(line.id())
                .variantId(line.variantId())
                .stockState(line.stockState())
                .batchId(
                        line.batchId().equals(com.bmp.inventory.domain.model.BalanceKey.NO_BATCH)
                                ? null
                                : line.batchId())
                .systemQty(decimal(line.systemQty()))
                .countedQty(decimal(line.countedQty()))
                .varianceQty(variance == null ? null : decimal(variance))
                .varianceCost(cost == null ? null : money(cost, currency))
                .varianceReason(line.varianceReason())
                .snapshotAt(time(line.snapshotAt()));
    }

    default StockBalance balance(StockReadModel.BalanceRow b, String currency) {
        return new StockBalance()
                .variantId(b.variantId())
                .productId(b.productId())
                .sku(b.sku())
                .locationId(b.locationId())
                .stockState(b.stockState())
                .batchId(b.batchId())
                .batchCode(b.batchCode())
                .expiresOn(b.expiresOn())
                .onHand(decimal(b.onHand()))
                .reserved(decimal(b.reserved()))
                .available(decimal(b.available()))
                .avgUnitCost(decimal(b.avgUnitCost()))
                .stockValue(money(b.stockValue(), currency))
                .reorderLevel(b.reorderLevel() == null ? null : decimal(b.reorderLevel()))
                .belowReorder(b.belowReorder())
                .negative(b.onHand().signum() < 0)
                .lastMovementAt(b.lastMovementAt() == null ? null : time(b.lastMovementAt()));
    }

    default Valuation valuation(StockQueryService.Valuation v) {
        return new Valuation()
                .asOf(time(v.asOf()))
                .groupBy(ValuationGroup.valueOf(v.groupBy().name()))
                .locationId(v.locationId())
                .rows(v.rows().stream()
                        .map(r -> new ValuationRow()
                                .key(r.key())
                                .label(r.label())
                                .qtyBase(decimal(r.qtyBase()))
                                .value(money(r.value(), v.currency())))
                        .toList())
                .total(money(v.total(), v.currency()));
    }

    default StockBalanceStockValue money(BigDecimal amount, String currency) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return new StockBalanceStockValue()
                .amount(stripped.setScale(Math.max(2, stripped.scale()), java.math.RoundingMode.UNNECESSARY)
                        .toPlainString())
                .currency(currency);
    }

    default String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    default OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    default StockBalancePagePage page(PageResponse<?> page) {
        return new StockBalancePagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }
}
