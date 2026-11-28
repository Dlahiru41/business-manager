package com.bmp.finance;

import static com.bmp.finance.FinanceShop.DAY;
import static com.bmp.finance.FinanceShop.SOLD;
import static com.bmp.finance.FinanceShop.cost;
import static com.bmp.finance.FinanceShop.line;
import static com.bmp.finance.FinanceShop.returnedLine;
import static com.bmp.finance.FinanceShop.tender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bmp.finance.application.JournalPoster;
import com.bmp.finance.application.OpenItems;
import com.bmp.finance.domain.model.AccountRef;
import com.bmp.finance.domain.model.Dimensions;
import com.bmp.finance.domain.model.DocumentType;
import com.bmp.finance.domain.model.JournalEntry;
import com.bmp.finance.domain.model.PartyRef;
import com.bmp.finance.domain.model.SourceType;
import com.bmp.finance.domain.model.SystemKey;
import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.TechnicalException;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.TenantId;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The rest of posting-rules.md §2 through the listeners, and the ordering and redelivery guards. */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinanceEdgeCasesIT {

    static final UUID EGGS = UUID.randomUUID();
    static final UUID OIL = UUID.randomUUID();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    JournalPoster poster;

    @Autowired
    OpenItems openItems;

    private FinanceShop shop(String name) {
        return new FinanceShop(mvc, jdbc, transactions, publisher, name);
    }

    @Test
    void stockLossesCountsTransformationsOpeningsAndTransfersPostByTheirRules() {
        FinanceShop shop = shop("Finance stock");
        LocationId location = LocationId.of(shop.location);
        shop.publish(new StockReceived(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                "OPENING_BALANCE",
                UUID.randomUUID(),
                location,
                "LKR",
                List.of(cost(null, EGGS, "10000.00")),
                List.of()));
        shop.publish(new StockReceived(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                "TRANSFER",
                UUID.randomUUID(),
                location,
                "LKR",
                List.of(cost(null, EGGS, "300.00")),
                List.of()));
        shop.publish(new StockAdjusted(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                "BREAKAGE",
                null,
                location,
                "LKR",
                List.of(cost(null, EGGS, "-540.00"))));
        shop.publish(new StockAdjusted(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                "THEFT",
                null,
                location,
                "LKR",
                List.of(cost(null, OIL, "-650.00"))));
        shop.publish(new StockCountPosted(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                location,
                "LKR",
                List.of(cost(null, EGGS, "80.00")),
                new BigDecimal("80.00")));
        shop.publish(new StockTransformed(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "OPEN",
                location,
                "LKR",
                List.of(cost(null, OIL, "-10000.00")),
                List.of(cost(null, OIL, "9999.99")),
                BigDecimal.ZERO,
                new BigDecimal("10000.00"),
                List.of(cost(null, OIL, "2.00"))));
        shop.publish(new StockTransformed(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "OPEN",
                location,
                "LKR",
                List.of(cost(null, OIL, "-500.00")),
                List.of(cost(null, OIL, "500.00")),
                BigDecimal.ZERO,
                new BigDecimal("500.00"),
                List.<StockMovementLine>of()));

        shop.awaitJournals("STOCK_RECEIPT", 1);
        shop.awaitJournals("STOCK_ADJUSTMENT", 2);
        shop.awaitJournals("STOCK_COUNT", 1);
        shop.awaitJournals("STOCK_TRANSFORMATION", 1);
        assertThat(shop.balance("EQUITY")).isEqualByComparingTo("-10000.00");
        assertThat(shop.balance("WASTAGE")).isEqualByComparingTo("540.01");
        assertThat(shop.balance("COUNT_VARIANCE")).isEqualByComparingTo("570.00");
        assertThat(shop.balance("COGS")).isEqualByComparingTo("-2.00");
        assertThat(shop.balance("INVENTORY")).isEqualByComparingTo("8891.99");
        assertThat(shop.unbalanced()).isZero();
    }

    @Test
    void returnsAndVoidsBringCostAndMoneyBackAgainstTheirOwnJournals() {
        FinanceShop shop = shop("Finance money back");
        UUID returnedBill = UUID.randomUUID();
        UUID returnedLine = UUID.randomUUID();
        UUID returnedPayment = UUID.randomUUID();
        shop.publish(shop.sale(
                returnedBill,
                null,
                DAY,
                List.of(line(returnedLine, OIL, "1000.00", "0.00", "0.00")),
                List.of(tender("CASH", "1000.00"))));
        UUID voidedBill = UUID.randomUUID();
        UUID voidedLine = UUID.randomUUID();
        UUID voidedPayment = UUID.randomUUID();
        SaleCompleted voided = shop.sale(
                voidedBill,
                null,
                DAY,
                List.of(line(voidedLine, EGGS, "500.00", "0.00", "0.00")),
                List.of(tender("CASH", "500.00")));
        shop.publish(voided);
        shop.awaitJournals("SALE", 2);
        shop.publish(shop.payment(returnedPayment, returnedBill, null, "CASH", "1000.00"));
        shop.publish(shop.payment(voidedPayment, voidedBill, null, "CASH", "500.00"));
        shop.publish(shop.issued("SALE", returnedBill, returnedBill, List.of(cost(returnedLine, OIL, "-700.00"))));
        shop.publish(shop.issued("SALE", voidedBill, voidedBill, List.of(cost(voidedLine, EGGS, "-300.00"))));
        shop.awaitJournals("STOCK_ISSUE", 2);
        shop.awaitJournals("BILL_PAYMENT", 2);

        UUID creditNote = UUID.randomUUID();
        shop.publish(shop.returned(
                creditNote,
                returnedBill,
                null,
                "400.00",
                List.of(returnedLine(returnedLine, OIL, "400.00", "0.00", true)),
                List.of(new RefundTender(UUID.randomUUID(), "CASH", new BigDecimal("400.00"), null, null))));
        shop.publish(shop.voided(voided));
        shop.awaitJournals("SALE_RETURN", 1);
        shop.awaitJournals("SALE_VOID", 1);
        shop.publish(shop.issued("SALE_RETURN", creditNote, returnedBill, List.of(cost(returnedLine, OIL, "280.00"))));
        shop.publish(shop.refund(returnedPayment, returnedBill, creditNote, "CASH", "400.00"));
        shop.publish(shop.issued("SALE_VOID", voidedBill, voidedBill, List.of(cost(voidedLine, EGGS, "300.00"))));
        shop.publish(shop.refund(voidedPayment, voidedBill, null, "CASH", "500.00"));
        shop.awaitJournals("STOCK_ISSUE", 4);
        shop.awaitJournals("PAYMENT_REFUND", 2);

        assertThat(shop.balance("COGS")).isEqualByComparingTo("420.00");
        assertThat(shop.balance("INVENTORY")).isEqualByComparingTo("-420.00");
        assertThat(shop.balance("CASH_DRAWER")).isEqualByComparingTo("600.00");
        assertThat(shop.balance("POS_CLEARING")).isEqualByComparingTo("0");
        assertThat(shop.count("""
                        SELECT count(*) FROM finance.journal_entry v
                          JOIN finance.journal_entry s ON s.id = v.reverses_id
                         WHERE (v.source_type, s.source_type) IN (('STOCK_ISSUE', 'STOCK_ISSUE'),
                                                                  ('PAYMENT_REFUND', 'BILL_PAYMENT'),
                                                                  ('SALE_VOID', 'SALE'))
                        """))
                .as("the void's stock, money and sale point at what they reverse; the return's do not")
                .isEqualTo(3L);
        assertThat(shop.unbalanced()).isZero();
    }

    @Test
    void aVoidedCreditBillLeavesTheCustomerOwingNothingAndARefundedCollectionIsOwedAgain() {
        FinanceShop shop = shop("Finance credit void");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 14);
        UUID bill = UUID.randomUUID();
        SaleCompleted sale = shop.sale(
                bill,
                customer,
                DAY,
                List.of(line(UUID.randomUUID(), EGGS, "750.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "750.00")));
        shop.publish(sale);
        shop.awaitJournals("SALE", 1);
        shop.publish(shop.voided(sale));
        shop.awaitJournals("SALE_VOID", 1);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(open(shop, customer)).isEqualByComparingTo("0"));

        UUID collection = UUID.randomUUID();
        shop.publish(shop.payment(collection, null, customer, "CASH", "200.00"));
        shop.awaitJournals("COLLECTION", 1);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(open(shop, customer)).isEqualByComparingTo("-200.00"));
        var refund = new com.bmp.payments.api.events.PaymentRefunded(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                collection,
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                collection,
                null,
                null,
                customer,
                new BigDecimal("200.00"),
                "CASH",
                "Paid by mistake",
                null,
                "LKR",
                SOLD);
        shop.publish(refund);
        shop.awaitJournals("PAYMENT_REFUND", 1);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(open(shop, customer)).isEqualByComparingTo("0"));
        assertThat(shop.balance("AR")).isEqualByComparingTo("0");
        assertThat(shop.balance("CASH_DRAWER")).isEqualByComparingTo("0");
    }

    @Test
    void aDrawerVariancePostsToCashOverShort() {
        FinanceShop shop = shop("Finance drawer");
        UUID session = UUID.randomUUID();
        shop.publish(new DrawerReconciled(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                session,
                3,
                EventMetadata.empty(),
                session,
                shop.register,
                new BigDecimal("5550.00"),
                new BigDecimal("5450.00"),
                new BigDecimal("-100.00"),
                "LKR",
                SOLD));
        shop.publish(new DrawerReconciled(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                3,
                EventMetadata.empty(),
                UUID.randomUUID(),
                shop.register,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                "LKR",
                SOLD));

        shop.awaitJournals("DRAWER_RECONCILIATION", 1);
        assertThat(shop.balance("CASH_OVER_SHORT")).isEqualByComparingTo("100.00");
        assertThat(shop.balance("CASH_DRAWER")).isEqualByComparingTo("-100.00");
    }

    @Test
    void anAccountNotThereMakesAnEventWaitAndACommandFail() {
        TenantId unseeded = TenantId.of(UUID.randomUUID());
        JournalEntry fromEvent = entry(UUID.randomUUID());
        JournalEntry fromCommand = entry(null);

        assertThatThrownBy(() -> inTenant(unseeded, () -> poster.post(() -> Optional.of(fromEvent))))
                .isInstanceOf(OutOfOrderEventException.class);
        assertThatThrownBy(() -> inTenant(unseeded, () -> poster.post(() -> Optional.of(fromCommand))))
                .isInstanceOf(TechnicalException.class);
        FinanceShop shop = shop("Finance unknown account");
        JournalEntry toNowhere = JournalEntry.builder(SourceType.MANUAL, UUID.randomUUID(), null, DAY)
                .debit(AccountRef.of(UUID.randomUUID()), BigDecimal.ONE)
                .credit(SystemKey.BANK, BigDecimal.ONE, Dimensions.NONE)
                .build();
        assertThatThrownBy(() -> inTenant(shop.tenant, () -> poster.post(() -> Optional.of(toNowhere))))
                .isInstanceOf(TechnicalException.class);
        assertThat(inTenant(shop.tenant, () -> poster.post(Optional::empty))).isEmpty();
    }

    @Test
    void anItemIsOpenedOncePerDocumentAndNeverForNothing() {
        FinanceShop shop = shop("Finance open item guard");
        PartyRef supplier = PartyRef.supplier(UUID.randomUUID());
        UUID invoice = UUID.randomUUID();

        assertThat(inTenant(
                        shop.tenant,
                        () -> openItems.open(
                                supplier,
                                DocumentType.SUPPLIER_INVOICE,
                                invoice,
                                BigDecimal.ZERO,
                                DAY,
                                DAY,
                                List.of())))
                .isEmpty();
        assertThat(inTenant(
                        shop.tenant,
                        () -> openItems.open(
                                supplier, DocumentType.SUPPLIER_INVOICE, invoice, BigDecimal.TEN, DAY, DAY, List.of())))
                .isPresent();
        assertThat(inTenant(
                        shop.tenant,
                        () -> openItems.open(
                                supplier, DocumentType.SUPPLIER_INVOICE, invoice, BigDecimal.TEN, DAY, DAY, List.of())))
                .isEmpty();
        assertThat(inTenant(shop.tenant, () -> openItems.openItemsOf(supplier))).hasSize(1);
    }

    private <T> T inTenant(TenantId tenant, java.util.function.Supplier<T> action) {
        return TenantContext.callWith(tenant, () -> new TransactionTemplate(transactions).execute(s -> action.get()));
    }

    private static JournalEntry entry(UUID eventId) {
        return JournalEntry.builder(SourceType.CASH_DROP, UUID.randomUUID(), eventId, LocalDate.parse("2026-11-25"))
                .debit(SystemKey.BANK, BigDecimal.ONE, Dimensions.NONE)
                .credit(SystemKey.CASH_DRAWER, BigDecimal.ONE, Dimensions.NONE)
                .build();
    }

    private static BigDecimal open(FinanceShop shop, UUID customer) {
        return shop.sql(
                j -> j.sql("""
                        SELECT coalesce(sum(amount - settled), 0) FROM finance.open_item
                         WHERE party_id = :c AND status = 'OPEN'
                        """).param("c", customer).query(BigDecimal.class).single());
    }
}
