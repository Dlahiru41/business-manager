package com.bmp.procurement.adapter.in.web;

import com.bmp.procurement.adapter.in.web.generated.model.GoodsReceiptLine;
import com.bmp.procurement.adapter.in.web.generated.model.GoodsReceiptStatus;
import com.bmp.procurement.adapter.in.web.generated.model.InvoiceMatch;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderLine;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderLineLineTotal;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderPagePage;
import com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrderStatus;
import com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoicePriceVariance;
import com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoiceStatus;
import com.bmp.procurement.domain.model.GoodsReceipt;
import com.bmp.procurement.domain.model.PurchaseOrder;
import com.bmp.procurement.domain.model.ReceiptLine;
import com.bmp.procurement.domain.model.SupplierInvoice;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;

/** Procurement documents to API representations: decimals become strings (02 §5), money carries its currency. */
@Mapper(componentModel = "spring")
interface ProcurementWebMapper {

    default com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrder order(PurchaseOrder order) {
        RoundingPolicy rounding = RoundingPolicy.defaultPolicy();
        return new com.bmp.procurement.adapter.in.web.generated.model.PurchaseOrder()
                .id(order.id())
                .number(order.number())
                .supplierId(order.supplierId())
                .locationId(order.locationId())
                .status(PurchaseOrderStatus.valueOf(order.status().name()))
                .expectedOn(order.expectedOn())
                .notes(order.notes())
                .total(money(order.total(rounding), order.currency()))
                .lines(order.lines().stream()
                        .map(l -> new PurchaseOrderLine()
                                .id(l.id())
                                .variantId(l.variantId())
                                .packagingUnitId(l.packagingUnitId())
                                .qty(decimal(l.qty()))
                                .unitCost(decimal(l.unitCost()))
                                .lineTotal(money(rounding.round(l.qty().multiply(l.unitCost())), order.currency()))
                                .receivedQty(decimal(l.receivedQty())))
                        .toList())
                .version(order.version())
                .createdAt(time(order.audit().createdAt()))
                .sentAt(order.sentAt() == null ? null : time(order.sentAt()))
                .cancelledAt(order.cancelledAt() == null ? null : time(order.cancelledAt()));
    }

    default com.bmp.procurement.adapter.in.web.generated.model.GoodsReceipt receipt(GoodsReceipt receipt) {
        return new com.bmp.procurement.adapter.in.web.generated.model.GoodsReceipt()
                .id(receipt.id())
                .number(receipt.number())
                .supplierId(receipt.supplierId())
                .locationId(receipt.locationId())
                .purchaseOrderId(receipt.purchaseOrderId())
                .correctsReceiptId(receipt.correctsReceiptId())
                .status(GoodsReceiptStatus.valueOf(receipt.status().name()))
                .receivedAt(time(receipt.receivedAt()))
                .notes(receipt.notes())
                .postedAt(receipt.postedAt() == null ? null : time(receipt.postedAt()))
                .postedBy(receipt.postedBy())
                .total(money(receipt.total(), receipt.currency()))
                .lines(receipt.lines().stream()
                        .map(l -> line(l, receipt.currency()))
                        .toList())
                .invoiced(receipt.invoiced())
                .version(receipt.version())
                .createdAt(time(receipt.audit().createdAt()));
    }

    default GoodsReceiptLine line(ReceiptLine line, String currency) {
        return new GoodsReceiptLine()
                .id(line.id())
                .purchaseOrderLineId(line.purchaseOrderLineId())
                .variantId(line.variantId())
                .packagingUnitId(line.packagingUnitId())
                .qty(decimal(line.qty()))
                .qtyBase(decimal(line.qtyBase()))
                .stockState(line.stockState())
                .unitCost(decimal(line.unitCost()))
                .unitCostBase(decimal(line.unitCostBase()))
                .lineTotal(money(line.lineTotal(), currency))
                .batchCode(line.batchCode())
                .expiresOn(line.expiresOn());
    }

    default com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoice invoice(SupplierInvoice invoice) {
        String currency = invoice.currency();
        return new com.bmp.procurement.adapter.in.web.generated.model.SupplierInvoice()
                .id(invoice.id())
                .supplierId(invoice.supplierId())
                .supplierRef(invoice.supplierRef())
                .invoiceDate(invoice.invoiceDate())
                .dueDate(invoice.dueDate())
                .subtotal(money(invoice.subtotal(), currency))
                .tax(money(invoice.tax(), currency))
                .total(money(invoice.total(), currency))
                .status(SupplierInvoiceStatus.valueOf(invoice.status().name()))
                .receipts(invoice.receipts().stream()
                        .map(m -> new InvoiceMatch()
                                .receiptId(m.receiptId())
                                .receiptNumber(m.receiptNumber())
                                .receiptValue(money(m.receiptValue(), currency)))
                        .toList())
                .receiptValue(money(invoice.receiptValue(), currency))
                .priceVariance(new SupplierInvoicePriceVariance()
                        .amount(amount(invoice.priceVariance()))
                        .currency(currency))
                .version(invoice.version())
                .createdAt(time(invoice.audit().createdAt()))
                .postedAt(invoice.postedAt() == null ? null : time(invoice.postedAt()));
    }

    default PurchaseOrderLineLineTotal money(BigDecimal amount, String currency) {
        return new PurchaseOrderLineLineTotal().amount(amount(amount)).currency(currency);
    }

    /** At least the currency's minor units: {@code 13800.00}. */
    default String amount(BigDecimal amount) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return stripped.setScale(Math.max(2, stripped.scale()), RoundingMode.UNNECESSARY)
                .toPlainString();
    }

    default String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    default OffsetDateTime time(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    default PurchaseOrderPagePage page(PageResponse<?> page) {
        return new PurchaseOrderPagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }

    static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
