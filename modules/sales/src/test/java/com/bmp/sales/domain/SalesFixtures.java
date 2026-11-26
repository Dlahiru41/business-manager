package com.bmp.sales.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.LineDraft;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.Tender;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.sales.domain.service.TenderValidator;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.money.RoundingPolicy;
import com.bmp.shared.money.TaxSplit;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jspecify.annotations.Nullable;

/** Lines, bills and tenders of the pilot shop for the sales domain tests. */
public final class SalesFixtures {

    public static final TenantId TENANT = TenantId.of(UUID.fromString("0193a000-0000-7000-8000-000000000001"));
    public static final UUID REGISTER = UUID.fromString("0193d100-0000-7000-8000-000000000001");
    public static final UUID SHOP = UUID.fromString("0193b000-0000-7000-8000-000000000401");
    public static final UUID CUSTOMER = UUID.fromString("0193d000-0000-7000-8000-000000000102");
    public static final RoundingPolicy ROUNDING = RoundingPolicy.defaultPolicy();
    public static final Instant AT = Instant.parse("2026-11-25T09:15:00Z");
    public static final LocalDate TODAY = LocalDate.parse("2026-11-25");
    public static final TaxSplit.Rate VAT = new TaxSplit.Rate(UUID.randomUUID(), "VAT", new BigDecimal("18"), false);

    private SalesFixtures() {}

    public static Supplier<UUID> ids() {
        AtomicLong next = new AtomicLong();
        return () -> new UUID(0x0193f900L, next.incrementAndGet());
    }

    public static LineDraft line(String qty, String unitPrice, String lineDiscount) {
        return new LineDraft(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Eggs Red L",
                new BigDecimal(qty),
                new BigDecimal(qty),
                "DEFAULT",
                false,
                new BigDecimal(unitPrice),
                new BigDecimal(lineDiscount),
                BigDecimal.ZERO,
                "LIST",
                true,
                List.of());
    }

    public static LineDraft taxed(String qty, String unitPrice, boolean inclusive) {
        LineDraft plain = line(qty, unitPrice, "0");
        return new LineDraft(
                plain.id(),
                plain.variantId(),
                plain.packagingUnitId(),
                "Cooking oil 750 ml",
                plain.qty(),
                plain.qty().multiply(new BigDecimal("750")),
                "OPEN",
                false,
                plain.unitPrice(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                "LIST",
                inclusive,
                List.of(VAT));
    }

    public static Bill draft(@Nullable UUID customer, @Nullable BillDiscount discount, LineDraft... lines) {
        return Bill.draft(
                new Bill.Header(
                        UUID.randomUUID(),
                        TENANT,
                        "R1-000042",
                        REGISTER,
                        null,
                        SHOP,
                        null,
                        SalesChannel.RETAIL,
                        customer,
                        null,
                        3,
                        "LKR",
                        null,
                        false),
                List.of(lines),
                discount,
                ROUNDING);
    }

    public static Tender cash(String amount, String tendered) {
        return Tender.of(UUID.randomUUID(), TenderMethod.CASH, new BigDecimal(amount), new BigDecimal(tendered), null);
    }

    public static Tender card(String amount, @Nullable String reference) {
        return Tender.of(UUID.randomUUID(), TenderMethod.CARD, new BigDecimal(amount), null, reference);
    }

    public static Tender credit(String amount) {
        return Tender.of(UUID.randomUUID(), TenderMethod.CREDIT, new BigDecimal(amount), null, null);
    }

    public static Bill.Completion completion(Bill bill, Mode mode, Totals claimed, Tender... tenders) {
        return new Bill.Completion(
                List.of(tenders),
                claimed,
                AT,
                TODAY,
                null,
                null,
                mode,
                new TenderValidator.Rules(true, bill.customerId(), true),
                Set.of());
    }

    public static Bill completed(Tender... tenders) {
        Bill bill = draft(null, null, line("10", "55.00", "0"), line("2", "1180.00", "0"));
        bill.complete(completion(bill, Mode.ONLINE, bill.totals(), tenders));
        return bill;
    }

    public static Totals totals(String subtotal, String discount, String tax, String grand) {
        return new Totals(
                new BigDecimal(subtotal), new BigDecimal(discount), new BigDecimal(tax), new BigDecimal(grand));
    }

    public static void assertViolation(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        BusinessRuleViolation.class,
                        e -> assertThat(e.errorCode()).isEqualTo(code));
    }
}
