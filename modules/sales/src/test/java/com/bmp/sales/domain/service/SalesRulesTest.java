package com.bmp.sales.domain.service;

import static com.bmp.sales.domain.SalesFixtures.ROUNDING;
import static com.bmp.sales.domain.SalesFixtures.VAT;
import static com.bmp.sales.domain.SalesFixtures.assertViolation;
import static com.bmp.sales.domain.SalesFixtures.card;
import static com.bmp.sales.domain.SalesFixtures.cash;
import static com.bmp.sales.domain.SalesFixtures.credit;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.sales.domain.error.SalesErrorCode;
import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.DiscountType;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.Tender;
import com.bmp.sales.domain.model.Totals;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.money.CurrencyCode;
import com.bmp.shared.money.TaxSplit;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SAL-04..SAL-07, SAL-15 and SAL-01 numbers as pure rules. */
class SalesRulesTest {

    static final CurrencyCode LKR = CurrencyCode.of("LKR");

    static BillTotalsCalculator.PricedLine priced(
            String qty, String price, String discount, boolean inclusive, TaxSplit.Rate... rates) {
        return new BillTotalsCalculator.PricedLine(
                UUID.randomUUID(),
                new BigDecimal(qty),
                new BigDecimal(price),
                new BigDecimal(discount),
                inclusive,
                List.of(rates));
    }

    static BillTotalsCalculator.Result calc(BillDiscount discount, BillTotalsCalculator.PricedLine... lines) {
        return BillTotalsCalculator.calculate(List.of(lines), discount, LKR, ROUNDING);
    }

    static BillDiscount percent(String pct) {
        return new BillDiscount(DiscountType.PERCENT, new BigDecimal(pct), "Regular", null);
    }

    static BillDiscount amount(String value) {
        return new BillDiscount(DiscountType.AMOUNT, new BigDecimal(value), "Round down", null);
    }

    static TenderValidator.Rules rules(boolean referenceRequired, boolean withCustomer, boolean creditAllowed) {
        return new TenderValidator.Rules(referenceRequired, withCustomer ? UUID.randomUUID() : null, creditAllowed);
    }

    @Test
    void sal04LinesAreRoundedThenDiscountedAndTheGrandTotalIsTheirSum() {
        BillTotalsCalculator.Result r = calc(
                null,
                priced("10", "55.00", "0", true),
                priced("0.350", "2200.00", "20.00", true),
                priced("3", "33.335", "0", true));

        assertThat(r.lines()).extracting(l -> l.gross().toPlainString()).containsExactly("550.00", "770.00", "100.01");
        assertThat(r.lines().get(1).lineTotal()).isEqualByComparingTo("750.00");
        assertThat(r.totals()
                        .differsFrom(
                                new Totals(
                                        new BigDecimal("1420.01"),
                                        new BigDecimal("20.00"),
                                        BigDecimal.ZERO,
                                        new BigDecimal("1400.01")),
                                BigDecimal.ZERO))
                .isFalse();
    }

    @Test
    void sal04TheBillDiscountIsAllocatedByLargestRemainderOnLineNet() {
        BillTotalsCalculator.Result byPercent = calc(
                percent("10"),
                priced("1", "100.00", "0", true),
                priced("1", "50.00", "0", true),
                priced("1", "50.00", "0", true));
        BillTotalsCalculator.Result uneven = calc(
                amount("10.00"),
                priced("1", "33.33", "0", true),
                priced("1", "33.33", "0", true),
                priced("1", "33.34", "0", true));

        assertThat(byPercent.billDiscount()).isEqualByComparingTo("20.00");
        assertThat(byPercent.lines())
                .extracting(l -> l.allocatedDiscount().toPlainString())
                .containsExactly("10.00", "5.00", "5.00");
        assertThat(uneven.lines())
                .extracting(l -> l.allocatedDiscount().toPlainString())
                .containsExactly("3.33", "3.33", "3.34");
        assertThat(uneven.totals().grandTotal()).isEqualByComparingTo("90.00");
        assertThat(uneven.totals().discountTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void sal04TaxFollowsTheLinesPriceInclusiveOrExclusive() {
        BillTotalsCalculator.Result inclusive = calc(null, priced("1", "1180.00", "0", true, VAT));
        BillTotalsCalculator.Result exclusive = calc(percent("10"), priced("1", "1000.00", "0", false, VAT));

        assertThat(inclusive.totals().taxTotal()).isEqualByComparingTo("180.00");
        assertThat(inclusive.totals().grandTotal()).isEqualByComparingTo("1180.00");
        assertThat(exclusive.lines().getFirst().tax())
                .as("tax on the discounted 900")
                .isEqualByComparingTo("162.00");
        assertThat(exclusive.totals().grandTotal()).isEqualByComparingTo("1062.00");
    }

    @Test
    void discountsStayWithinTheirLines() {
        assertViolation(() -> calc(null, priced("1", "10.00", "10.01", true)), PlatformErrorCode.VALIDATION_FAILED);
        assertViolation(() -> calc(null, priced("1", "10.00", "-1", true)), PlatformErrorCode.VALIDATION_FAILED);
        assertViolation(
                () -> calc(percent("101"), priced("1", "10.00", "0", true)), PlatformErrorCode.VALIDATION_FAILED);
        assertViolation(
                () -> calc(amount("10.01"), priced("1", "10.00", "0", true)), PlatformErrorCode.VALIDATION_FAILED);
        assertViolation(() -> calc(amount("-1"), priced("1", "10.00", "0", true)), PlatformErrorCode.VALIDATION_FAILED);
        assertThat(DiscountAllocator.allocate(new BigDecimal("5"), List.of(BigDecimal.ZERO, BigDecimal.ZERO), LKR))
                .containsExactly(BigDecimal.ZERO, BigDecimal.ZERO);
        assertThat(calc(amount("10.00"), priced("1", "10.00", "0", true))
                        .totals()
                        .grandTotal())
                .isZero();
    }

    @ParameterizedTest
    @CsvSource({
        "5.00,100.00,5.00,false,ONLINE,",
        "5.01,100.00,5.00,true,ONLINE,",
        "5.01,100.00,5.00,false,OFFLINE,DISCOUNT_LIMIT_EXCEEDED",
        "0,0,5.00,false,ONLINE,",
        "1,0,5.00,false,OFFLINE,DISCOUNT_LIMIT_EXCEEDED"
    })
    void sal05DiscountsAboveTheCashierLimitNeedApproval(
            String discount, String subtotal, String max, boolean approved, Mode mode, BillFlag flag) {
        assertThat(DiscountPolicy.check(
                        new BigDecimal(discount), new BigDecimal(subtotal), new BigDecimal(max), approved, mode))
                .isEqualTo(java.util.Optional.ofNullable(flag));
    }

    @Test
    void sal05OnlineDiscountsAboveTheLimitAreRefused() {
        assertViolation(
                () -> DiscountPolicy.check(
                        new BigDecimal("6"), new BigDecimal("100"), new BigDecimal("5"), false, Mode.ONLINE),
                SalesErrorCode.DISCOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void sal06MixedTendersAddUpToTheGrandTotalWithChangeForCash() {
        Tender cash = cash("1000.00", "2000.00");
        assertThat(TenderValidator.validate(
                        List.of(card("650.00", "APPR 1234"), cash),
                        new BigDecimal("1650.00"),
                        rules(true, false, false),
                        Mode.ONLINE))
                .isEmpty();
        assertThat(((Tender.Cash) cash).change()).isEqualByComparingTo("1000.00");
        assertViolation(
                () -> TenderValidator.validate(
                        List.of(cash("100.00", "100.00")),
                        new BigDecimal("100.01"),
                        rules(true, false, false),
                        Mode.ONLINE),
                SalesErrorCode.TENDER_MISMATCH);
        assertThat(TenderValidator.validate(
                        List.of(cash("100.00", "100.00")),
                        new BigDecimal("100.01"),
                        rules(true, false, false),
                        Mode.OFFLINE))
                .containsExactly(BillFlag.TENDER_MISMATCH);
    }

    @Test
    void sal06CashCoversItsAmountAndCardsCarryAReference() {
        assertViolation(
                () -> TenderValidator.validate(
                        List.of(cash("100.00", "50.00")),
                        new BigDecimal("100.00"),
                        rules(true, false, false),
                        Mode.ONLINE),
                SalesErrorCode.TENDER_INVALID);
        assertViolation(
                () -> TenderValidator.validate(
                        List.of(card("100.00", " ")), new BigDecimal("100.00"), rules(true, false, false), Mode.ONLINE),
                SalesErrorCode.TENDER_INVALID);
        assertThat(TenderValidator.validate(
                        List.of(card("100.00", null)),
                        new BigDecimal("100.00"),
                        rules(false, false, false),
                        Mode.ONLINE))
                .as("the tenant relaxed references")
                .isEmpty();
        assertThat(TenderValidator.validate(
                        List.of(card("100.00", null), cash("1.00", "0.50")),
                        new BigDecimal("101.00"),
                        rules(true, false, false),
                        Mode.OFFLINE))
                .as("offline the bill already happened")
                .isEmpty();
        assertViolation(
                () -> TenderValidator.validate(List.of(), BigDecimal.ONE, rules(true, false, false), Mode.OFFLINE),
                SalesErrorCode.TENDER_INVALID);
        assertViolation(
                () -> TenderValidator.validate(
                        List.of(cash("0", "0")), BigDecimal.ZERO, rules(true, false, false), Mode.OFFLINE),
                SalesErrorCode.TENDER_INVALID);
    }

    @Test
    void pay05CardNumbersAreRefusedEvenOffline() {
        assertViolation(
                () -> TenderValidator.validate(
                        List.of(card("100.00", "4111 1111 1111 1111")),
                        new BigDecimal("100.00"),
                        rules(true, false, false),
                        Mode.OFFLINE),
                SalesErrorCode.CARD_DATA_NOT_ALLOWED);
    }

    @Test
    void sal07CreditNeedsACustomerAllowedCredit() {
        BigDecimal total = new BigDecimal("500.00");
        assertThat(TenderValidator.validate(List.of(credit("500.00")), total, rules(true, true, true), Mode.ONLINE))
                .isEmpty();
        assertViolation(
                () -> TenderValidator.validate(
                        List.of(credit("500.00")), total, rules(true, false, true), Mode.OFFLINE),
                SalesErrorCode.CREDIT_NOT_ALLOWED);
        assertViolation(
                () -> TenderValidator.validate(List.of(credit("500.00")), total, rules(true, true, false), Mode.ONLINE),
                SalesErrorCode.CREDIT_NOT_ALLOWED);
        assertThat(TenderValidator.validate(List.of(credit("500.00")), total, rules(true, true, false), Mode.OFFLINE))
                .containsExactly(BillFlag.CREDIT_LIMIT_EXCEEDED);
    }

    @Test
    void sal07CreditStaysWithinTheLimitUnlessApproved() {
        BigDecimal limit = new BigDecimal("50000.00");
        assertThat(CreditCheck.check(new BigDecimal("49000"), new BigDecimal("1000"), limit, false, Mode.ONLINE))
                .isEmpty();
        assertThat(CreditCheck.check(new BigDecimal("49000"), new BigDecimal("1000.01"), limit, true, Mode.ONLINE))
                .isEmpty();
        assertThat(CreditCheck.check(new BigDecimal("49000"), new BigDecimal("1000.01"), limit, false, Mode.OFFLINE))
                .contains(BillFlag.CREDIT_LIMIT_EXCEEDED);
        assertThat(CreditCheck.check(new BigDecimal("99999"), BigDecimal.ZERO, limit, false, Mode.ONLINE))
                .isEmpty();
        assertViolation(
                () -> CreditCheck.check(new BigDecimal("49000"), new BigDecimal("1000.01"), limit, false, Mode.ONLINE),
                SalesErrorCode.CREDIT_LIMIT_EXCEEDED);
    }

    @ParameterizedTest
    @CsvSource({
        "true,true,0.350,350,g,true",
        "true,true,0.3505,350.5,g,false",
        "true,true,350,350,g,true",
        "true,true,350.5,350.5,g,false",
        "true,false,0.350,350,g,false",
        "false,false,1.5,1.5,piece,true",
        "true,true,1.25,1250,ml,true"
    })
    void sal15WeighedQuantitiesHaveWholeGramsAndThreeDecimalsInKg(
            boolean weighed, boolean weighable, String qty, String qtyBase, String baseUnit, boolean valid) {
        UUID line = UUID.randomUUID();
        if (valid) {
            WeighedQuantity.check(line, weighed, weighable, new BigDecimal(qty), new BigDecimal(qtyBase), baseUnit);
        } else {
            assertViolation(
                    () -> WeighedQuantity.check(
                            line, weighed, weighable, new BigDecimal(qty), new BigDecimal(qtyBase), baseUnit),
                    SalesErrorCode.WEIGHED_QTY_INVALID);
        }
    }

    @Test
    void sal01BillNumbersArePrefixAndSixDigits() {
        assertThat(BillNumbers.parse("R1-000042")).contains(new BillNumbers.BillNumber("R1", 42));
        assertThat(BillNumbers.parse("R1-42")).isEmpty();
        assertThat(BillNumbers.parse("r1-000042")).isEmpty();
        assertThat(BillNumbers.format("R1", 43)).isEqualTo("R1-000043");
    }

    @Test
    void sal10ReturnsArePaidTheirShareAndTheLastOneTakesWhatIsLeft() {
        ReturnAmounts.Share first = ReturnAmounts.of(
                new BigDecimal("1"),
                new BigDecimal("3"),
                BigDecimal.ZERO,
                new BigDecimal("100.00"),
                new BigDecimal("15.25"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                ROUNDING);
        ReturnAmounts.Share last = ReturnAmounts.of(
                new BigDecimal("2"),
                new BigDecimal("3"),
                new BigDecimal("1"),
                new BigDecimal("100.00"),
                new BigDecimal("15.25"),
                first.amount(),
                first.tax(),
                ROUNDING);

        assertThat(first).isEqualTo(new ReturnAmounts.Share(new BigDecimal("33.33"), new BigDecimal("5.08")));
        assertThat(last.amount().add(first.amount())).isEqualByComparingTo("100.00");
        assertThat(last.tax().add(first.tax())).isEqualByComparingTo("15.25");
    }
}
