package com.bmp.sales.adapter.in.web;

import com.bmp.sales.adapter.in.web.generated.model.BillDiscountInput;
import com.bmp.sales.adapter.in.web.generated.model.BillLineInput;
import com.bmp.sales.adapter.in.web.generated.model.BillPage;
import com.bmp.sales.adapter.in.web.generated.model.CreditNotePage;
import com.bmp.sales.adapter.in.web.generated.model.RegisterPagePage;
import com.bmp.sales.adapter.in.web.generated.model.TenderAmount;
import com.bmp.sales.adapter.in.web.generated.model.TenderInput;
import com.bmp.sales.adapter.in.web.generated.model.TotalsInput;
import com.bmp.sales.application.LinePricer;
import com.bmp.sales.application.command.BillHandlers;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.BillLine;
import com.bmp.sales.domain.model.BillTender;
import com.bmp.sales.domain.model.CreditNote;
import com.bmp.sales.domain.model.DiscountType;
import com.bmp.sales.domain.model.Register;
import com.bmp.sales.domain.model.Shift;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.web.pagination.PageResponse;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.mapstruct.Mapper;

/** Sales documents to API representations and requests to commands: decimals are strings (02 §5). */
@Mapper(componentModel = "spring")
interface SalesWebMapper {

    default com.bmp.sales.adapter.in.web.generated.model.Bill bill(Bill bill) {
        String currency = bill.currency();
        BillDiscount discount = bill.discount();
        Totals device = bill.deviceTotals();
        return new com.bmp.sales.adapter.in.web.generated.model.Bill()
                .id(bill.id())
                .number(bill.number())
                .registerId(bill.registerId())
                .shiftId(bill.shiftId())
                .locationId(bill.locationId())
                .deviceId(bill.deviceId())
                .channel(com.bmp.sales.adapter.in.web.generated.model.SalesChannel.valueOf(
                        bill.channel().name()))
                .customerId(bill.customerId())
                .priceListId(bill.priceListId())
                .priceListVersion(bill.priceListVersion())
                .status(com.bmp.sales.adapter.in.web.generated.model.BillStatus.valueOf(
                        bill.status().name()))
                .lines(bill.lines().stream().map(l -> line(l, currency)).toList())
                .billDiscount(
                        discount == null
                                ? null
                                : new com.bmp.sales.adapter.in.web.generated.model.BillDiscount()
                                        .type(com.bmp.sales.adapter.in.web.generated.model.DiscountType.valueOf(
                                                discount.type().name()))
                                        .value(decimal(discount.value()))
                                        .reason(discount.reason())
                                        .approvedBy(discount.approvedBy()))
                .totals(totals(bill.totals(), currency))
                .deviceTotals(device == null ? null : totals(device, currency))
                .tenders(bill.tenders().stream().map(t -> tender(t, currency)).toList())
                .businessDate(bill.businessDate())
                .completedAt(time(bill.completedAt()))
                .cashierId(bill.cashierId())
                .offline(bill.offline())
                .flags(bill.flags().stream()
                        .map(BillFlag::name)
                        .sorted()
                        .map(com.bmp.sales.adapter.in.web.generated.model.BillFlag::valueOf)
                        .toList())
                .currency(currency)
                .voidReason(bill.voidReason())
                .voidedAt(time(bill.voidedAt()))
                .version(bill.version())
                .createdAt(java.util.Objects.requireNonNull(time(bill.audit().createdAt())));
    }

    default com.bmp.sales.adapter.in.web.generated.model.BillLine line(BillLine l, String currency) {
        return new com.bmp.sales.adapter.in.web.generated.model.BillLine()
                .id(l.id())
                .variantId(l.variantId())
                .packagingUnitId(l.packagingUnitId())
                .description(l.description())
                .qty(decimal(l.qty()))
                .qtyBase(decimal(l.qtyBase()))
                .stockState(l.stockState())
                .weighed(l.weighed())
                .unitPrice(money(l.unitPrice(), currency))
                .lineDiscount(money(l.lineDiscount(), currency))
                .ruleDiscount(money(l.ruleDiscount(), currency))
                .allocatedBillDiscount(money(l.allocatedBillDiscount(), currency))
                .taxAmount(money(l.taxAmount(), currency))
                .lineTotal(money(l.lineTotal(), currency))
                .priceSource(l.priceSource())
                .returnedQty(decimal(l.returnedQty()));
    }

    default com.bmp.sales.adapter.in.web.generated.model.BillTotals totals(Totals t, String currency) {
        return new com.bmp.sales.adapter.in.web.generated.model.BillTotals()
                .subtotal(money(t.subtotal(), currency))
                .discountTotal(money(t.discountTotal(), currency))
                .taxTotal(money(t.taxTotal(), currency))
                .grandTotal(money(t.grandTotal(), currency));
    }

    default com.bmp.sales.adapter.in.web.generated.model.Tender tender(BillTender t, String currency) {
        return new com.bmp.sales.adapter.in.web.generated.model.Tender()
                .id(t.id())
                .method(com.bmp.sales.adapter.in.web.generated.model.TenderMethod.valueOf(
                        t.method().name()))
                .amount(money(t.amount(), currency))
                .tendered(t.tendered() == null ? null : money(t.tendered(), currency))
                .change(t.changeGiven() == null ? null : money(t.changeGiven(), currency))
                .reference(t.reference());
    }

    default com.bmp.sales.adapter.in.web.generated.model.CreditNote creditNote(CreditNote n, String currency) {
        return new com.bmp.sales.adapter.in.web.generated.model.CreditNote()
                .id(n.id())
                .number(n.number())
                .billId(n.billId())
                .reason(n.reason())
                .total(money(n.total(), currency))
                .taxTotal(money(n.taxTotal(), currency))
                .lines(n.lines().stream()
                        .map(l -> new com.bmp.sales.adapter.in.web.generated.model.CreditNoteLine()
                                .billLineId(l.billLineId())
                                .variantId(l.variantId())
                                .qty(decimal(l.qty()))
                                .qtyBase(decimal(l.qtyBase()))
                                .amount(money(l.amount(), currency))
                                .taxAmount(money(l.taxAmount(), currency))
                                .restock(l.restock()))
                        .toList())
                .refundTenders(n.refundTenders().stream()
                        .map(r -> new com.bmp.sales.adapter.in.web.generated.model.RefundTender()
                                .method(r.method().name())
                                .amount(money(r.amount(), currency))
                                .reference(r.reference()))
                        .toList())
                .approvedBy(n.approvedBy())
                .createdAt(java.util.Objects.requireNonNull(time(n.returnedAt())));
    }

    default com.bmp.sales.adapter.in.web.generated.model.Register register(Register r) {
        return new com.bmp.sales.adapter.in.web.generated.model.Register()
                .id(r.id())
                .code(r.code())
                .name(r.name())
                .locationId(r.locationId())
                .deviceId(r.deviceId())
                .billPrefix(r.billPrefix())
                .status(com.bmp.sales.adapter.in.web.generated.model.RegisterStatus.valueOf(r.status()))
                .lastNumber(r.lastNumber())
                .version(r.version())
                .createdAt(java.util.Objects.requireNonNull(time(r.audit().createdAt())));
    }

    default com.bmp.sales.adapter.in.web.generated.model.Shift shift(Shift s) {
        return new com.bmp.sales.adapter.in.web.generated.model.Shift()
                .id(s.id())
                .registerId(s.registerId())
                .cashierId(s.cashierId())
                .status(com.bmp.sales.adapter.in.web.generated.model.ShiftStatus.valueOf(s.status()))
                .openedAt(java.util.Objects.requireNonNull(time(s.openedAt())))
                .closedAt(time(s.closedAt()))
                .offline(s.offline())
                .version(s.version());
    }

    default List<LinePricer.LineInput> lines(List<BillLineInput> lines) {
        return lines.stream()
                .map(l -> new LinePricer.LineInput(
                        l.getId(),
                        l.getVariantId(),
                        l.getPackagingUnitId(),
                        new BigDecimal(l.getQty()),
                        Boolean.TRUE.equals(l.getWeighed()),
                        decimalOrNull(l.getUnitPrice()),
                        decimalOrNull(l.getLineDiscount()),
                        decimalOrNull(l.getRuleDiscount()),
                        l.getDiscountApprovedBy(),
                        l.getPriceOverride() == null
                                ? null
                                : new LinePricer.PriceOverride(
                                        new BigDecimal(l.getPriceOverride().getUnitPrice()),
                                        l.getPriceOverride().getApprovedBy(),
                                        l.getPriceOverride().getReason())))
                .toList();
    }

    default @Nullable BillDiscount discount(@Nullable BillDiscountInput d) {
        return d == null
                ? null
                : new BillDiscount(
                        DiscountType.valueOf(d.getType().name()),
                        new BigDecimal(d.getValue()),
                        d.getReason(),
                        d.getApprovedBy());
    }

    default List<BillHandlers.TenderInput> tenders(@Nullable List<TenderInput> tenders) {
        return tenders == null
                ? List.of()
                : tenders.stream()
                        .map(t -> new BillHandlers.TenderInput(
                                t.getId(),
                                TenderMethod.valueOf(t.getMethod().name()),
                                new BigDecimal(t.getAmount()),
                                decimalOrNull(t.getTendered()),
                                t.getReference()))
                        .toList();
    }

    default @Nullable Totals totals(@Nullable TotalsInput t) {
        return t == null
                ? null
                : new Totals(
                        new BigDecimal(t.getSubtotal()),
                        new BigDecimal(t.getDiscountTotal()),
                        new BigDecimal(t.getTaxTotal()),
                        new BigDecimal(t.getGrandTotal()));
    }

    default BillPage billPage(PageResponse<Bill> page) {
        return new BillPage()
                .items(page.items().stream().map(this::bill).toList())
                .page(page(page));
    }

    default CreditNotePage creditNotePage(PageResponse<CreditNote> page, String currency) {
        return new CreditNotePage()
                .items(page.items().stream().map(n -> creditNote(n, currency)).toList())
                .page(page(page));
    }

    default RegisterPagePage page(PageResponse<?> page) {
        return new RegisterPagePage()
                .limit(page.page().limit())
                .nextCursor(page.page().nextCursor())
                .hasMore(page.page().hasMore());
    }

    default TenderAmount money(BigDecimal amount, String currency) {
        BigDecimal stripped = amount.stripTrailingZeros();
        return new TenderAmount()
                .amount(stripped.setScale(Math.max(2, stripped.scale()), RoundingMode.UNNECESSARY)
                        .toPlainString())
                .currency(currency);
    }

    default String decimal(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    static @Nullable BigDecimal decimalOrNull(@Nullable String value) {
        return value == null ? null : new BigDecimal(value);
    }

    default @Nullable OffsetDateTime time(@Nullable Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable Instant instant(@Nullable OffsetDateTime time) {
        return time == null ? null : time.toInstant();
    }
}
