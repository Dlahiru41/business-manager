package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.port.JournalLedger;
import com.bmp.finance.domain.port.ReceiptLines;
import com.bmp.finance.domain.posting.StockAdjustedRule;
import com.bmp.finance.domain.posting.StockCost;
import com.bmp.finance.domain.posting.StockCountPostedRule;
import com.bmp.finance.domain.posting.StockIssuedRule;
import com.bmp.finance.domain.posting.StockReceivedRule;
import com.bmp.finance.domain.posting.StockTransformedRule;
import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockIssued;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.persistence.event.OutOfOrderEventException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Journals of stock value: issues, receipts, adjustments, counts and transformations (ADR-032 §5). */
@Service
public class StockPostingHandler {

    private static final StockIssuedRule ISSUED = new StockIssuedRule();
    private static final StockReceivedRule RECEIVED = new StockReceivedRule();
    private static final StockAdjustedRule ADJUSTED = new StockAdjustedRule();
    private static final StockCountPostedRule COUNTED = new StockCountPostedRule();
    private static final StockTransformedRule TRANSFORMED = new StockTransformedRule();

    private final JournalPoster poster;
    private final JournalLedger ledger;
    private final ReceiptLines receiptLines;
    private final FinanceSettings settings;

    public StockPostingHandler(
            JournalPoster poster, JournalLedger ledger, ReceiptLines receiptLines, FinanceSettings settings) {
        this.poster = poster;
        this.ledger = ledger;
        this.receiptLines = receiptLines;
        this.settings = settings;
    }

    /**
     * Cost of a bill's lines, dated like the sale, return or void it belongs to, so COGS falls in the revenue's period;
     * waits for that journal (ADR-032 §5). A void's stock points at the sale's stock journal.
     */
    @Transactional
    public void issued(StockIssued e) {
        PostedJournal related = switch (e.sourceType()) {
            case "SALE" -> related(e, SourceType.SALE, e.billId());
            case "SALE_RETURN" -> related(e, SourceType.SALE_RETURN, e.sourceId());
            default -> related(e, SourceType.SALE_VOID, e.billId());
        };
        UUID reverses = e.sourceType().equals("SALE_VOID")
                ? ledger.findFirst(SourceType.STOCK_ISSUE, e.billId())
                        .map(PostedJournal::id)
                        .orElse(null)
                : null;
        poster.post(() -> ISSUED.post(new StockIssuedRule.Issue(
                e.eventId(),
                e.sourceType(),
                e.sourceId(),
                e.billId(),
                e.locationId().value(),
                related.entryDate(),
                costs(e.lines()),
                costs(e.corrections()),
                reverses)));
    }

    /** Openings to equity, true-ups to COGS, returns to the supplier beyond their line totals to PPV. */
    @Transactional
    public void received(StockReceived e) {
        Map<UUID, BigDecimal> totals = Map.of();
        if (e.sourceType().equals("GOODS_RECEIPT")) {
            List<UUID> lineIds = e.lines().stream()
                    .map(StockMovementLine::sourceLineId)
                    .filter(Objects::nonNull)
                    .toList();
            totals = receiptLines.totals(lineIds);
            if (totals.size() < lineIds.stream().distinct().count()) {
                throw new OutOfOrderEventException(e.eventId(), "the goods receipt " + e.sourceId());
            }
        }
        Map<UUID, BigDecimal> receipt = totals;
        poster.post(() -> RECEIVED.post(new StockReceivedRule.Receipt(
                e.eventId(),
                e.sourceType(),
                e.sourceId(),
                e.locationId().value(),
                settings.businessDate(e.occurredAt()),
                costs(e.lines()),
                costs(e.corrections()),
                receipt)));
    }

    @Transactional
    public void adjusted(StockAdjusted e) {
        poster.post(() -> ADJUSTED.post(new StockAdjustedRule.Adjustment(
                e.eventId(),
                e.adjustmentId(),
                e.reason(),
                e.locationId().value(),
                settings.businessDate(e.occurredAt()),
                costs(e.lines()))));
    }

    @Transactional
    public void counted(StockCountPosted e) {
        poster.post(() -> COUNTED.post(new StockCountPostedRule.Count(
                e.eventId(),
                e.countId(),
                e.locationId().value(),
                settings.businessDate(e.occurredAt()),
                costs(e.lines()))));
    }

    @Transactional
    public void transformed(StockTransformed e) {
        poster.post(() -> TRANSFORMED.post(new StockTransformedRule.Transformation(
                e.eventId(),
                e.runId(),
                e.locationId().value(),
                settings.businessDate(e.occurredAt()),
                costs(e.inputs()),
                costs(e.outputs()),
                costs(e.corrections()))));
    }

    private PostedJournal related(StockIssued e, SourceType type, UUID sourceId) {
        return ledger.findFirst(type, sourceId)
                .orElseThrow(
                        () -> new OutOfOrderEventException(e.eventId(), "the " + type + " journal of " + sourceId));
    }

    private static List<StockCost> costs(List<StockMovementLine> lines) {
        return lines.stream()
                .map(l -> new StockCost(l.sourceLineId(), l.variantId().value(), l.costAmount()))
                .toList();
    }
}
