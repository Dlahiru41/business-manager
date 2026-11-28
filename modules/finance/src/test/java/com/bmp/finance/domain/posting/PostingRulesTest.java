package com.bmp.finance.domain.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.finance.domain.error.FinanceErrorCode;
import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.JournalLine;
import com.bmp.finance.domain.model.PaidFrom;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.Period;
import com.bmp.finance.domain.model.PostedJournal;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.shared.error.TechnicalException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Rule FIN-02: every row of posting-rules.md §2 with a worked example from the pilot shop, as whole journals
 * ({@code Dr|Cr KEY amount}, in line order).
 */
class PostingRulesTest {

    static final LocalDate DAY = LocalDate.parse("2026-11-25");
    static final UUID EVENT = UUID.fromString("0194a9a0-0000-7000-8000-00000000e201");
    static final UUID BILL = UUID.fromString("0193f100-0000-7000-8000-000000000001");
    static final UUID CUSTOMER = UUID.fromString("0193d000-0000-7000-8000-000000000102");
    static final UUID SUPPLIER = UUID.fromString("0193c000-0000-7000-8000-000000000201");
    static final UUID LOCATION = UUID.fromString("0193a700-0000-7000-8000-000000000001");
    static final UUID EGGS = UUID.fromString("0193b200-0000-7000-8000-000000000001");
    static final UUID OIL = UUID.fromString("0193b200-0000-7000-8000-000000000011");
    static final UUID LINE_EGGS = UUID.fromString("0193f100-0000-7000-8000-000000000011");
    static final UUID LINE_OIL = UUID.fromString("0193f100-0000-7000-8000-000000000012");
    static final UUID RENT = UUID.fromString("0194a000-0000-7000-8000-000000006000");
    static final UUID SOURCE = UUID.fromString("0194a200-0000-7000-8000-000000000001");

    /** Eggs 550.00 (no tax) and oil 1000.00 with 152.54 VAT inside and 50.00 of discounts. */
    static SaleCompletedRule.Sale sale(UUID customer, String grandTotal) {
        return new SaleCompletedRule.Sale(
                EVENT,
                BILL,
                "R1-000123",
                DAY,
                customer,
                LOCATION,
                "RETAIL",
                new BigDecimal(grandTotal),
                List.of(
                        new SaleCompletedRule.Line(
                                LINE_EGGS, EGGS, new BigDecimal("550.00"), BigDecimal.ZERO, BigDecimal.ZERO),
                        new SaleCompletedRule.Line(
                                LINE_OIL,
                                OIL,
                                new BigDecimal("1000.00"),
                                new BigDecimal("152.54"),
                                new BigDecimal("50.00"))));
    }

    static Arguments example(String name, Supplier<Optional<JournalEntry>> journal, String... lines) {
        return Arguments.of(name, journal, List.of(lines));
    }

    static Stream<Arguments> rows() {
        return Stream.of(
                example(
                        "SaleCompleted, walk-in",
                        () -> new SaleCompletedRule().post(sale(null, "1550.00")),
                        "Dr POS_CLEARING 1550.0000",
                        "Cr SALES 550.0000",
                        "Cr SALES 897.4600",
                        "Dr SALES_DISCOUNTS 50.0000",
                        "Cr TAX_PAYABLE 152.5400"),
                example(
                        "SaleCompleted, a customer's bill",
                        () -> new SaleCompletedRule().post(sale(CUSTOMER, "1550.00")),
                        "Dr AR 1550.0000",
                        "Cr SALES 550.0000",
                        "Cr SALES 897.4600",
                        "Dr SALES_DISCOUNTS 50.0000",
                        "Cr TAX_PAYABLE 152.5400"),
                example(
                        "SaleCompleted, offline bill paid 0.01 above the lines",
                        () -> new SaleCompletedRule().post(sale(null, "1550.01")),
                        "Dr POS_CLEARING 1550.0100",
                        "Cr SALES 550.0000",
                        "Cr SALES 897.4600",
                        "Dr SALES_DISCOUNTS 50.0000",
                        "Cr TAX_PAYABLE 152.5400",
                        "Cr SALES 0.0100"),
                example(
                        "SaleCompleted, offline bill paid 0.02 below the lines",
                        () -> new SaleCompletedRule().post(sale(null, "1549.98")),
                        "Dr POS_CLEARING 1549.9800",
                        "Cr SALES 550.0000",
                        "Cr SALES 897.4600",
                        "Dr SALES_DISCOUNTS 50.0000",
                        "Cr TAX_PAYABLE 152.5400",
                        "Dr SALES_DISCOUNTS 0.0200"),
                example(
                        "PaymentCaptured, cash for a walk-in bill",
                        () -> new PaymentCapturedRule()
                                .post(new PaymentCapturedRule.Payment(
                                        EVENT, SOURCE, false, BILL, null, "CASH", new BigDecimal("1000.00"), DAY)),
                        "Dr CASH_DRAWER 1000.0000",
                        "Cr POS_CLEARING 1000.0000"),
                example(
                        "PaymentCaptured, card for a customer's bill",
                        () -> new PaymentCapturedRule()
                                .post(new PaymentCapturedRule.Payment(
                                        EVENT, SOURCE, false, BILL, CUSTOMER, "CARD", new BigDecimal("550.00"), DAY)),
                        "Dr CARD_CLEARING 550.0000",
                        "Cr AR 550.0000"),
                example(
                        "PaymentCaptured, an account collection by bank transfer",
                        () -> new PaymentCapturedRule()
                                .post(new PaymentCapturedRule.Payment(
                                        EVENT,
                                        SOURCE,
                                        true,
                                        null,
                                        CUSTOMER,
                                        "BANK_TRANSFER",
                                        new BigDecimal("500.00"),
                                        DAY)),
                        "Dr TRANSFER_CLEARING 500.0000",
                        "Cr AR 500.0000"),
                example(
                        "PaymentCaptured, mobile wallet and cheque",
                        () -> new PaymentCapturedRule()
                                .post(new PaymentCapturedRule.Payment(
                                        EVENT, SOURCE, false, BILL, null, "MOBILE_WALLET", new BigDecimal("1"), DAY)),
                        "Dr TRANSFER_CLEARING 1.0000",
                        "Cr POS_CLEARING 1.0000"),
                example(
                        "PaymentCaptured, cheque",
                        () -> new PaymentCapturedRule()
                                .post(new PaymentCapturedRule.Payment(
                                        EVENT, SOURCE, true, null, CUSTOMER, "CHEQUE", new BigDecimal("2500"), DAY)),
                        "Dr CHEQUES 2500.0000",
                        "Cr AR 2500.0000"),
                example(
                        "StockIssued, a sale with a true-up",
                        () -> new StockIssuedRule()
                                .post(new StockIssuedRule.Issue(
                                        EVENT,
                                        "SALE",
                                        BILL,
                                        BILL,
                                        LOCATION,
                                        DAY,
                                        List.of(
                                                new StockCost(LINE_EGGS, EGGS, new BigDecimal("-300.00")),
                                                new StockCost(LINE_OIL, OIL, new BigDecimal("-529.5012"))),
                                        List.of(new StockCost(OIL, new BigDecimal("-10.00"))),
                                        null)),
                        "Cr INVENTORY 300.0000",
                        "Dr COGS 300.0000",
                        "Cr INVENTORY 529.5012",
                        "Dr COGS 529.5012",
                        "Cr INVENTORY 10.0000",
                        "Dr COGS 10.0000"),
                example(
                        "StockIssued, a restocked return",
                        () -> new StockIssuedRule()
                                .post(new StockIssuedRule.Issue(
                                        EVENT,
                                        "SALE_RETURN",
                                        SOURCE,
                                        BILL,
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(LINE_OIL, OIL, new BigDecimal("529.5012"))),
                                        List.of(),
                                        null)),
                        "Dr INVENTORY 529.5012",
                        "Cr COGS 529.5012"),
                example(
                        "SaleReturned, the oil back to a walk-in customer",
                        () -> new SaleReturnedRule()
                                .post(new SaleReturnedRule.Return(
                                        EVENT,
                                        SOURCE,
                                        "CN-000001",
                                        BILL,
                                        null,
                                        LOCATION,
                                        DAY,
                                        new BigDecimal("1000.00"),
                                        List.of(new SaleReturnedRule.Line(
                                                LINE_OIL, OIL, new BigDecimal("1000.00"), new BigDecimal("152.54"))))),
                        "Dr SALES_RETURNS 847.4600",
                        "Dr TAX_PAYABLE 152.5400",
                        "Cr POS_CLEARING 1000.0000"),
                example(
                        "SaleReturned, a customer's eggs",
                        () -> new SaleReturnedRule()
                                .post(new SaleReturnedRule.Return(
                                        EVENT,
                                        SOURCE,
                                        "CN-000002",
                                        BILL,
                                        CUSTOMER,
                                        LOCATION,
                                        DAY,
                                        new BigDecimal("275.00"),
                                        List.of(new SaleReturnedRule.Line(
                                                LINE_EGGS, EGGS, new BigDecimal("275.00"), BigDecimal.ZERO)))),
                        "Dr SALES_RETURNS 275.0000",
                        "Cr AR 275.0000"),
                example(
                        "PaymentRefunded, cash back to a walk-in customer",
                        () -> new PaymentRefundedRule()
                                .post(new PaymentRefundedRule.Refund(
                                        EVENT, SOURCE, BILL, null, "CASH", new BigDecimal("1000.00"), DAY, null)),
                        "Dr POS_CLEARING 1000.0000",
                        "Cr CASH_DRAWER 1000.0000"),
                example(
                        "PaymentRefunded, card back to a customer",
                        () -> new PaymentRefundedRule()
                                .post(new PaymentRefundedRule.Refund(
                                        EVENT, SOURCE, BILL, CUSTOMER, "CARD", new BigDecimal("550.00"), DAY, null)),
                        "Dr AR 550.0000",
                        "Cr CARD_CLEARING 550.0000"),
                example(
                        "GoodsReceived, eggs and a return line",
                        () -> new GoodsReceivedRule()
                                .post(new GoodsReceivedRule.Receipt(
                                        EVENT,
                                        SOURCE,
                                        "GRN-000004",
                                        SUPPLIER,
                                        LOCATION,
                                        DAY,
                                        List.of(
                                                new GoodsReceivedRule.Line(LINE_EGGS, EGGS, new BigDecimal("48000.00")),
                                                new GoodsReceivedRule.Line(LINE_OIL, OIL, new BigDecimal("-500.00"))))),
                        "Dr INVENTORY 48000.0000",
                        "Cr INVENTORY 500.0000",
                        "Cr GRNI 47500.0000"),
                example(
                        "SupplierInvoicePosted, 150.00 above the receipt, tax not reclaimed",
                        () -> new SupplierInvoicePostedRule()
                                .post(new SupplierInvoicePostedRule.Invoice(
                                        EVENT,
                                        SOURCE,
                                        "INV-778",
                                        SUPPLIER,
                                        DAY,
                                        new BigDecimal("100.00"),
                                        new BigDecimal("48750.00"),
                                        new BigDecimal("48500.00"),
                                        new BigDecimal("150.00"),
                                        false)),
                        "Dr GRNI 48500.0000",
                        "Dr PPV 150.0000",
                        "Dr PPV 100.0000",
                        "Cr AP 48750.0000"),
                example(
                        "SupplierInvoicePosted, below the receipt, tax reclaimed",
                        () -> new SupplierInvoicePostedRule()
                                .post(new SupplierInvoicePostedRule.Invoice(
                                        EVENT,
                                        SOURCE,
                                        "INV-779",
                                        SUPPLIER,
                                        DAY,
                                        new BigDecimal("18.00"),
                                        new BigDecimal("98.00"),
                                        new BigDecimal("100.00"),
                                        new BigDecimal("-20.00"),
                                        true)),
                        "Dr GRNI 100.0000",
                        "Cr PPV 20.0000",
                        "Dr TAX_PAYABLE 18.0000",
                        "Cr AP 98.0000"),
                example(
                        "Supplier payment from the bank",
                        () -> new SupplierPaymentRule()
                                .post(new SupplierPaymentRule.Payment(
                                        SOURCE, SUPPLIER, new BigDecimal("48750.00"), false, DAY)),
                        "Dr AP 48750.0000",
                        "Cr BANK 48750.0000"),
                example(
                        "Supplier payment from the drawer",
                        () -> new SupplierPaymentRule()
                                .post(new SupplierPaymentRule.Payment(
                                        SOURCE, SUPPLIER, new BigDecimal("5000"), true, DAY)),
                        "Dr AP 5000.0000",
                        "Cr CASH_DRAWER 5000.0000"),
                example(
                        "StockAdjusted, breakage",
                        () -> new StockAdjustedRule()
                                .post(new StockAdjustedRule.Adjustment(
                                        EVENT,
                                        SOURCE,
                                        "BREAKAGE",
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(EGGS, new BigDecimal("-540.00"))))),
                        "Cr INVENTORY 540.0000",
                        "Dr WASTAGE 540.0000"),
                example(
                        "StockAdjusted, theft",
                        () -> new StockAdjustedRule()
                                .post(new StockAdjustedRule.Adjustment(
                                        EVENT,
                                        SOURCE,
                                        "THEFT",
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(OIL, new BigDecimal("-650.00"))))),
                        "Cr INVENTORY 650.0000",
                        "Dr COUNT_VARIANCE 650.0000"),
                example(
                        "StockCountPosted, a gain and a loss",
                        () -> new StockCountPostedRule()
                                .post(new StockCountPostedRule.Count(
                                        EVENT,
                                        SOURCE,
                                        LOCATION,
                                        DAY,
                                        List.of(
                                                new StockCost(EGGS, new BigDecimal("80.00")),
                                                new StockCost(OIL, new BigDecimal("-12.50"))))),
                        "Dr INVENTORY 80.0000",
                        "Cr COUNT_VARIANCE 80.0000",
                        "Cr INVENTORY 12.5000",
                        "Dr COUNT_VARIANCE 12.5000"),
                example(
                        "StockTransformed, a can opened: cost absorbed",
                        () -> new StockTransformedRule()
                                .post(new StockTransformedRule.Transformation(
                                        EVENT,
                                        SOURCE,
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(OIL, new BigDecimal("-10000.00"))),
                                        List.of(new StockCost(OIL, new BigDecimal("10000.00"))),
                                        List.of()))),
                example(
                        "StockTransformed, a residual and a true-up",
                        () -> new StockTransformedRule()
                                .post(new StockTransformedRule.Transformation(
                                        EVENT,
                                        SOURCE,
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(OIL, new BigDecimal("-10000.00"))),
                                        List.of(new StockCost(OIL, new BigDecimal("9999.99"))),
                                        List.of(new StockCost(OIL, new BigDecimal("2.00"))))),
                        "Cr INVENTORY 0.0100",
                        "Dr WASTAGE 0.0100",
                        "Dr INVENTORY 2.0000",
                        "Cr COGS 2.0000"),
                example(
                        "StockReceived, an opening balance",
                        () -> new StockReceivedRule()
                                .post(new StockReceivedRule.Receipt(
                                        EVENT,
                                        "OPENING_BALANCE",
                                        SOURCE,
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(EGGS, new BigDecimal("10000.00"))),
                                        List.of(),
                                        Map.of())),
                        "Dr INVENTORY 10000.0000",
                        "Cr EQUITY 10000.0000"),
                example(
                        "StockReceived, a transfer moves no value",
                        () -> new StockReceivedRule()
                                .post(new StockReceivedRule.Receipt(
                                        EVENT,
                                        "TRANSFER",
                                        SOURCE,
                                        LOCATION,
                                        DAY,
                                        List.of(new StockCost(EGGS, new BigDecimal("300.00"))),
                                        List.of(),
                                        Map.of()))),
                example(
                        "StockReceived, a goods receipt with a return at current cost and a true-up",
                        () -> new StockReceivedRule()
                                .post(new StockReceivedRule.Receipt(
                                        EVENT,
                                        "GOODS_RECEIPT",
                                        SOURCE,
                                        LOCATION,
                                        DAY,
                                        List.of(
                                                new StockCost(LINE_EGGS, EGGS, new BigDecimal("48000.00")),
                                                new StockCost(LINE_OIL, OIL, new BigDecimal("-529.50"))),
                                        List.of(new StockCost(EGGS, new BigDecimal("-3.00"))),
                                        Map.of(
                                                LINE_EGGS,
                                                new BigDecimal("48000.00"),
                                                LINE_OIL,
                                                new BigDecimal("-500.00")))),
                        "Cr INVENTORY 29.5000",
                        "Dr PPV 29.5000",
                        "Cr INVENTORY 3.0000",
                        "Dr COGS 3.0000"),
                example(
                        "ExpenseRecorded, rent from the bank",
                        () -> new ExpenseRule()
                                .post(new ExpenseRule.Expense(
                                        SOURCE,
                                        RENT,
                                        new BigDecimal("25000.00"),
                                        PaidFrom.BANK,
                                        null,
                                        DAY,
                                        "Rent November")),
                        "Dr account:" + RENT + " 25000.0000",
                        "Cr BANK 25000.0000"),
                example(
                        "ExpenseRecorded, bottles from the drawer",
                        () -> new ExpenseRule()
                                .post(new ExpenseRule.Expense(
                                        SOURCE,
                                        RENT,
                                        new BigDecimal("2000.00"),
                                        PaidFrom.CASH_DRAWER,
                                        null,
                                        DAY,
                                        "200 empty bottles")),
                        "Dr account:" + RENT + " 2000.0000",
                        "Cr CASH_DRAWER 2000.0000"),
                example(
                        "ExpenseRecorded, electricity payable",
                        () -> new ExpenseRule()
                                .post(new ExpenseRule.Expense(
                                        SOURCE,
                                        RENT,
                                        new BigDecimal("4300.00"),
                                        PaidFrom.PAYABLE,
                                        SUPPLIER,
                                        DAY,
                                        "Electricity")),
                        "Dr account:" + RENT + " 4300.0000",
                        "Cr AP 4300.0000"),
                example(
                        "DrawerReconciled, 100.00 short",
                        () -> new DrawerReconciledRule()
                                .post(new DrawerReconciledRule.Reconciliation(
                                        EVENT, SOURCE, new BigDecimal("-100.00"), DAY)),
                        "Cr CASH_DRAWER 100.0000",
                        "Dr CASH_OVER_SHORT 100.0000"),
                example(
                        "DrawerReconciled, 20.00 over",
                        () -> new DrawerReconciledRule()
                                .post(new DrawerReconciledRule.Reconciliation(
                                        EVENT, SOURCE, new BigDecimal("20.00"), DAY)),
                        "Dr CASH_DRAWER 20.0000",
                        "Cr CASH_OVER_SHORT 20.0000"),
                example(
                        "DrawerReconciled, no variance",
                        () -> new DrawerReconciledRule()
                                .post(new DrawerReconciledRule.Reconciliation(EVENT, SOURCE, BigDecimal.ZERO, DAY))),
                example(
                        "Cash drop to the bank",
                        () -> new CashDropRule().post(new CashDropRule.Drop(SOURCE, new BigDecimal("40000.00"), DAY)),
                        "Dr BANK 40000.0000",
                        "Cr CASH_DRAWER 40000.0000"));
    }

    @ParameterizedTest(name = "fin02 {0}")
    @MethodSource("rows")
    void fin02EachEventPostsItsJournal(String name, Supplier<Optional<JournalEntry>> journal, List<String> expected) {
        Optional<JournalEntry> entry = journal.get();

        if (expected.isEmpty()) {
            assertThat(entry).as(name).isEmpty();
        } else {
            assertThat(entry).as(name).isPresent();
            assertThat(entry.orElseThrow().lines())
                    .extracting(PostingRulesTest::line)
                    .containsExactlyElementsOf(expected);
            assertThat(entry.orElseThrow().totalDebit())
                    .isEqualByComparingTo(entry.orElseThrow().totalCredit());
        }
    }

    @Test
    void fin02ASaleNamesTheBillItsCustomerAndTheEvent() {
        JournalEntry entry =
                new SaleCompletedRule().post(sale(CUSTOMER, "1550.00")).orElseThrow();

        assertThat(entry.sourceType()).isEqualTo(SourceType.SALE);
        assertThat(entry.sourceId()).isEqualTo(BILL);
        assertThat(entry.sourceEventId()).isEqualTo(EVENT);
        assertThat(entry.entryDate()).isEqualTo(DAY);
        assertThat(entry.memo()).isEqualTo("Bill R1-000123");
        JournalLine receivable = entry.lines().getFirst();
        assertThat(receivable.party()).isEqualTo(PartyRef.customer(CUSTOMER));
        assertThat(receivable.dimensions()).isEqualTo(Dimensions.bill(BILL, LOCATION, "RETAIL"));
        assertThat(entry.lines().get(2).dimensions().variantId()).isEqualTo(OIL);
        assertThat(entry.lines().get(2).party()).isNull();
        assertThat(new SaleCompletedRule()
                        .post(sale(null, "1550.01"))
                        .orElseThrow()
                        .memo())
                .isEqualTo("Bill R1-000123; device totals difference");
    }

    @Test
    void fin02EveryRuleSaysWhatItPostedFor() {
        assertThat(new PaymentCapturedRule()
                        .post(new PaymentCapturedRule.Payment(
                                EVENT, SOURCE, true, null, CUSTOMER, "CASH", BigDecimal.TEN, DAY))
                        .orElseThrow())
                .satisfies(e -> {
                    assertThat(e.sourceType()).isEqualTo(SourceType.COLLECTION);
                    assertThat(e.lines().get(1).party()).isEqualTo(PartyRef.customer(CUSTOMER));
                    assertThat(e.memo()).isEqualTo("Collection CASH");
                });
        assertThat(new PaymentCapturedRule()
                        .post(new PaymentCapturedRule.Payment(
                                EVENT, SOURCE, false, BILL, null, "CASH", BigDecimal.TEN, DAY))
                        .orElseThrow()
                        .sourceType())
                .isEqualTo(SourceType.BILL_PAYMENT);
        UUID original = UUID.randomUUID();
        JournalEntry refund = new PaymentRefundedRule()
                .post(new PaymentRefundedRule.Refund(EVENT, SOURCE, BILL, null, "CASH", BigDecimal.TEN, DAY, original))
                .orElseThrow();
        assertThat(refund.reversesId()).isEqualTo(original);
        assertThat(refund.sourceType()).isEqualTo(SourceType.PAYMENT_REFUND);
        JournalEntry issue = new StockIssuedRule()
                .post(new StockIssuedRule.Issue(
                        EVENT,
                        "SALE_VOID",
                        BILL,
                        BILL,
                        LOCATION,
                        DAY,
                        List.of(new StockCost(OIL, BigDecimal.ONE)),
                        List.of(),
                        original))
                .orElseThrow();
        assertThat(issue.reversesId()).isEqualTo(original);
        assertThat(issue.memo()).isEqualTo("Stock SALE_VOID");
        assertThat(issue.lines().getFirst().dimensions()).isEqualTo(new Dimensions(OIL, null, null, LOCATION, BILL));
        JournalEntry receipt = new GoodsReceivedRule()
                .post(new GoodsReceivedRule.Receipt(
                        EVENT,
                        SOURCE,
                        "GRN-1",
                        SUPPLIER,
                        LOCATION,
                        DAY,
                        List.of(new GoodsReceivedRule.Line(LINE_EGGS, EGGS, BigDecimal.TEN))))
                .orElseThrow();
        assertThat(receipt.lines().get(1).party()).isEqualTo(PartyRef.supplier(SUPPLIER));
        assertThat(receipt.memo()).isEqualTo("Goods receipt GRN-1");
        JournalEntry payable = new ExpenseRule()
                .post(new ExpenseRule.Expense(
                        SOURCE, RENT, BigDecimal.TEN, PaidFrom.PAYABLE, SUPPLIER, DAY, "Electricity"))
                .orElseThrow();
        assertThat(payable.lines().get(1).party()).isEqualTo(PartyRef.supplier(SUPPLIER));
        assertThat(payable.memo()).isEqualTo("Electricity");
        assertThat(new ExpenseRule()
                        .post(new ExpenseRule.Expense(
                                SOURCE, RENT, BigDecimal.TEN, PaidFrom.PAYABLE, null, DAY, "Electricity"))
                        .orElseThrow()
                        .lines()
                        .get(1)
                        .party())
                .isNull();
    }

    @Test
    void fin02AVoidIsTheExactReversalOfTheSale() {
        UUID saleJournal = UUID.randomUUID();
        UUID ar = UUID.randomUUID();
        UUID sales = UUID.randomUUID();
        PostedJournal posted = new PostedJournal(
                saleJournal,
                "JE-000001",
                DAY,
                Period.of(DAY),
                SourceType.SALE,
                BILL,
                null,
                List.of(
                        new PostedJournal.Line(
                                ar,
                                new BigDecimal("550.0000"),
                                BigDecimal.ZERO,
                                PartyRef.customer(CUSTOMER),
                                Dimensions.NONE),
                        new PostedJournal.Line(
                                sales, BigDecimal.ZERO, new BigDecimal("550.0000"), null, Dimensions.NONE)));

        JournalEntry voided = new SaleVoidedRule()
                .post(new SaleVoidedRule.Voided(EVENT, BILL, "R1-000123", DAY.plusDays(1), posted))
                .orElseThrow();

        assertThat(voided.reversesId()).isEqualTo(saleJournal);
        assertThat(voided.sourceType()).isEqualTo(SourceType.SALE_VOID);
        assertThat(voided.memo()).isEqualTo("Void of bill R1-000123");
        assertThat(voided.lines())
                .extracting(PostingRulesTest::line)
                .containsExactly("Cr account:" + ar + " 550.0000", "Dr account:" + sales + " 550.0000");
    }

    @Test
    void aMethodOrKindFinanceDoesNotKnowIsABugNotAJournal() {
        assertThatThrownBy(() -> TenderAccounts.of("BITCOIN"))
                .isInstanceOfSatisfying(
                        TechnicalException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(FinanceErrorCode.POSTING_RULE_MISSING));
        assertThatThrownBy(() -> new StockReceivedRule()
                        .post(new StockReceivedRule.Receipt(
                                EVENT, "GIFT", SOURCE, LOCATION, DAY, List.of(), List.of(), Map.of())))
                .isInstanceOf(TechnicalException.class);
        assertThatThrownBy(() -> new StockReceivedRule()
                        .post(new StockReceivedRule.Receipt(
                                EVENT,
                                "GOODS_RECEIPT",
                                SOURCE,
                                LOCATION,
                                DAY,
                                List.of(new StockCost(LINE_OIL, OIL, BigDecimal.ONE)),
                                List.of(),
                                Map.of())))
                .isInstanceOfSatisfying(
                        TechnicalException.class,
                        e -> assertThat(e.details()).containsEntry("missing", "receipt line " + LINE_OIL));
        assertThatThrownBy(() -> new StockReceivedRule()
                        .post(new StockReceivedRule.Receipt(
                                EVENT,
                                "GOODS_RECEIPT",
                                SOURCE,
                                LOCATION,
                                DAY,
                                List.of(new StockCost(OIL, BigDecimal.ONE)),
                                List.of(),
                                Map.of())))
                .isInstanceOf(TechnicalException.class);
    }

    static String line(JournalLine line) {
        String account = switch (line.account()) {
            case AccountRef.Seeded seeded -> seeded.key().name();
            case AccountRef.Id id -> "account:" + id.accountId();
        };
        return line.debit().signum() > 0
                ? "Dr " + account + " " + line.debit().toPlainString()
                : "Cr " + account + " " + line.credit().toPlainString();
    }

    @Test
    void theWastageReasonsAreTheSpoiltOnes() {
        assertThat(StockAdjustedRule.WASTAGE_REASONS)
                .containsExactlyInAnyOrder("BREAKAGE", "SPOILAGE", "SPILLAGE", "EXPIRED", "DAMAGED");
        assertThat(SystemKey.WASTAGE.type()).isEqualTo(com.bmp.finance.domain.model.AccountType.EXPENSE);
    }
}
