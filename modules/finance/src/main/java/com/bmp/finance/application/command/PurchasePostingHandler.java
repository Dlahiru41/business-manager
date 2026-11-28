package com.bmp.finance.application.command;

import com.bmp.finance.application.FinanceSettings;
import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.OpenItems;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.port.ReceiptLines;
import com.bmp.finance.domain.posting.GoodsReceivedRule;
import com.bmp.finance.domain.posting.SupplierInvoicePostedRule;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.GoodsReceivedLine;
import com.bmp.procurement.api.events.SupplierInvoicePosted;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Journals and payables of purchases: goods receipts and supplier invoices (posting-rules.md §2, FIN-07). */
@Service
public class PurchasePostingHandler {

    private static final GoodsReceivedRule RECEIVED = new GoodsReceivedRule();
    private static final SupplierInvoicePostedRule INVOICED = new SupplierInvoicePostedRule();

    private final JournalPoster poster;
    private final OpenItems openItems;
    private final ReceiptLines receiptLines;
    private final FinanceSettings settings;

    public PurchasePostingHandler(
            JournalPoster poster, OpenItems openItems, ReceiptLines receiptLines, FinanceSettings settings) {
        this.poster = poster;
        this.openItems = openItems;
        this.receiptLines = receiptLines;
        this.settings = settings;
    }

    /** Stock at its line totals against goods received not invoiced; the totals are kept for the stock event. */
    @Transactional
    public void goodsReceived(GoodsReceived e) {
        Map<UUID, BigDecimal> totals = new LinkedHashMap<>();
        e.lines().forEach(l -> totals.put(l.lineId(), l.lineTotal()));
        receiptLines.save(e.receiptId(), totals);
        List<GoodsReceivedRule.Line> lines =
                e.lines().stream().map(PurchasePostingHandler::line).toList();
        poster.post(() -> RECEIVED.post(new GoodsReceivedRule.Receipt(
                e.eventId(),
                e.receiptId(),
                e.number(),
                e.supplierId().value(),
                e.locationId().value(),
                settings.businessDate(e.receivedAt()),
                lines)));
    }

    /** GRNI cleared, the price difference to PPV, the total payable to the supplier by its due date. */
    @Transactional
    public void invoicePosted(SupplierInvoicePosted e) {
        boolean reclaimable = settings.inputTaxReclaimable();
        poster.post(() -> INVOICED.post(new SupplierInvoicePostedRule.Invoice(
                e.eventId(),
                e.invoiceId(),
                e.supplierRef(),
                e.supplierId().value(),
                e.invoiceDate(),
                e.tax(),
                e.total(),
                e.receiptValue(),
                e.priceVariance(),
                reclaimable)));
        openItems.open(
                PartyRef.supplier(e.supplierId().value()),
                DocumentType.SUPPLIER_INVOICE,
                e.invoiceId(),
                e.total(),
                e.invoiceDate(),
                e.dueDate(),
                List.of());
    }

    private static GoodsReceivedRule.Line line(GoodsReceivedLine l) {
        return new GoodsReceivedRule.Line(l.lineId(), l.variantId().value(), l.lineTotal());
    }
}
