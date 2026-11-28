package com.bmp.reporting;

import static com.bmp.reporting.ReportingShop.DAY;
import static com.bmp.reporting.ReportingShop.SOLD;
import static com.bmp.reporting.ReportingShop.line;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bmp.catalog.api.events.ProductCreated;
import com.bmp.catalog.api.events.ProductTracking;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.finance.api.events.ExpenseRecorded;
import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.reporting.application.Rebuild;
import com.bmp.reporting.application.Recorder;
import com.bmp.reporting.domain.model.Fact;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.ProductId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

/** Rules REP-01 and REP-02: the read models from the events, in any order, once, and rebuilt identically. */
@SpringBootTest(classes = ReportingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class ReportingReadModelsIT {

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
    Recorder recorder;

    @Autowired
    Rebuild rebuild;

    private ReportingShop shop(String name) {
        return new ReportingShop(mvc, jdbc, transactions, publisher, name);
    }

    private void awaitLog(ReportingShop shop, long entries) {
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(shop.count("SELECT count(*) FROM reporting.event_log"))
                        .isEqualTo(entries));
    }

    @Test
    void rep02TheCostOfALineMeetsItsSaleWhicheverComesFirst() {
        ReportingShop shop = shop("Reporting halves");
        UUID bill = UUID.randomUUID();
        UUID eggsLine = UUID.randomUUID();
        UUID oilLine = UUID.randomUUID();
        SaleCompleted sale = shop.sale(
                bill,
                null,
                DAY,
                List.of(
                        line(eggsLine, EGGS, "550.00", "0.00", "0.00"),
                        line(oilLine, OIL, "1000.00", "152.54", "50.00")),
                "CASH");

        shop.publish(shop.issued("SALE", bill, bill, eggsLine, EGGS, "-300.00"));
        awaitLog(shop, 1);
        shop.publish(sale);
        awaitLog(shop, 2);
        shop.publish(shop.issued("SALE", bill, bill, oilLine, OIL, "-529.50"));
        awaitLog(shop, 3);

        assertThat(shop.sum("SELECT sum(revenue) FROM reporting.fact_sale_line"))
                .isEqualByComparingTo("1397.46");
        assertThat(shop.sum("SELECT sum(cost) FROM reporting.fact_sale_line")).isEqualByComparingTo("829.50");
        assertThat(shop.sum("SELECT sum(discount) FROM reporting.fact_sale_line"))
                .isEqualByComparingTo("50.00");
        assertThat(shop.count("SELECT count(*) FROM reporting.fact_sale_line WHERE has_sale AND cost_known"))
                .isEqualTo(2L);
        assertThat(shop.sum("SELECT revenue FROM reporting.daily_summary")).isEqualByComparingTo("1397.46");
        assertThat(shop.sum("SELECT cost FROM reporting.daily_summary")).isEqualByComparingTo("829.50");
        assertThat(shop.count("SELECT bills FROM reporting.daily_summary")).isEqualTo(1L);
        assertThat(shop.<String>sql(j -> j.sql("SELECT status || hour FROM reporting.fact_bill")
                        .query(String.class)
                        .single()))
                .as("10:00 in Colombo")
                .isEqualTo("COMPLETED10");
    }

    @Test
    void rep02ReturnsAndVoidsComeOffTheDayTheyHappen() {
        ReportingShop shop = shop("Reporting returns");
        UUID bill = UUID.randomUUID();
        UUID oilLine = UUID.randomUUID();
        shop.publish(shop.sale(bill, null, DAY, List.of(line(oilLine, OIL, "1000.00", "0.00", "0.00")), "CASH"));
        shop.publish(shop.issued("SALE", bill, bill, oilLine, OIL, "-700.00"));
        awaitLog(shop, 2);
        UUID note = UUID.randomUUID();
        shop.publish(shop.returned(note, bill, oilLine, OIL, "400.00", "CASH"));
        shop.publish(shop.issued("SALE_RETURN", note, bill, oilLine, OIL, "280.00"));
        awaitLog(shop, 4);

        UUID voidedBill = UUID.randomUUID();
        UUID eggsLine = UUID.randomUUID();
        SaleCompleted voided =
                shop.sale(voidedBill, null, DAY, List.of(line(eggsLine, EGGS, "500.00", "0.00", "0.00")), "CASH");
        shop.publish(voided);
        shop.publish(shop.issued("SALE", voidedBill, voidedBill, eggsLine, EGGS, "-300.00"));
        awaitLog(shop, 6);
        Instant nextDay = Instant.parse("2026-11-26T04:00:00Z");
        shop.publish(shop.voided(voided, nextDay));
        awaitLog(shop, 7);
        shop.publish(shop.issued("SALE_VOID", voidedBill, voidedBill, eggsLine, EGGS, "300.00"));
        awaitLog(shop, 8);

        assertThat(shop.sum("SELECT revenue FROM reporting.daily_summary WHERE business_date = DATE '2026-11-25'"))
                .isEqualByComparingTo("1100.00");
        assertThat(shop.sum("SELECT cost FROM reporting.daily_summary WHERE business_date = DATE '2026-11-25'"))
                .isEqualByComparingTo("720.00");
        assertThat(shop.sum("SELECT revenue FROM reporting.daily_summary WHERE business_date = DATE '2026-11-26'"))
                .isEqualByComparingTo("-500.00");
        assertThat(shop.sum("SELECT cost FROM reporting.daily_summary WHERE business_date = DATE '2026-11-26'"))
                .isEqualByComparingTo("-300.00");
        assertThat(shop.sum("SELECT sum(revenue - cost) FROM reporting.fact_sale_line"))
                .as("the oil kept 300.00 of margin after the return; the voided eggs none")
                .isEqualByComparingTo("180.00");
        assertThat(shop.<String>sql(j -> j.sql("SELECT status || voided_on FROM reporting.fact_bill WHERE bill_id = :b")
                        .param("b", voidedBill)
                        .query(String.class)
                        .single()))
                .isEqualTo("VOIDED2026-11-26");
        assertThat(shop.sum("SELECT expected FROM reporting.return_watch")).isEqualByComparingTo("400.00");
    }

    @Test
    void rep01MoneyExpensesDrawersStockAndNamesAreKeptAndARedeliveryChangesNothing() {
        ReportingShop shop = shop("Reporting money");
        UUID bill = UUID.randomUUID();
        UUID note = UUID.randomUUID();
        PaymentCaptured cash = new PaymentCaptured(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                bill,
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                "BILL",
                bill,
                null,
                null,
                "CASH",
                new BigDecimal("1000.00"),
                null,
                null,
                null,
                shop.register,
                null,
                "LKR",
                SOLD,
                List.of());
        shop.publish(cash);
        shop.publish(cash);
        shop.publish(new PaymentRefunded(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                bill,
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                cash.paymentId(),
                bill,
                note,
                null,
                new BigDecimal("400.00"),
                "CASH",
                "Return",
                null,
                "LKR",
                SOLD));
        shop.publish(new ExpenseRecorded(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                0,
                EventMetadata.empty(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                new BigDecimal("2000.00"),
                "LKR",
                "CASH_DRAWER",
                DAY,
                null,
                null,
                UUID.randomUUID()));
        shop.publish(new DrawerReconciled(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                UUID.randomUUID(),
                3,
                EventMetadata.empty(),
                UUID.randomUUID(),
                shop.register,
                new BigDecimal("5550.00"),
                new BigDecimal("5450.00"),
                new BigDecimal("-100.00"),
                "LKR",
                SOLD));
        LocationId location = LocationId.of(shop.location);
        StockMovementLine broken = new StockMovementLine(
                UUID.randomUUID(),
                null,
                VariantId.of(EGGS),
                "DEFAULT",
                null,
                new BigDecimal("-12"),
                new BigDecimal("45"),
                new BigDecimal("-540.00"));
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
                List.of(broken)));
        StockMovementLine counted = new StockMovementLine(
                UUID.randomUUID(),
                null,
                VariantId.of(OIL),
                "DEFAULT",
                null,
                BigDecimal.ONE,
                BigDecimal.TEN,
                BigDecimal.TEN);
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
                List.of(counted),
                BigDecimal.TEN));
        StockMovementLine in = new StockMovementLine(
                UUID.randomUUID(),
                null,
                VariantId.of(OIL),
                "SEALED",
                null,
                new BigDecimal("-19565"),
                BigDecimal.ONE,
                new BigDecimal("-10000.00"));
        StockMovementLine out = new StockMovementLine(
                UUID.randomUUID(),
                null,
                VariantId.of(OIL),
                "OPEN",
                null,
                new BigDecimal("19400"),
                BigDecimal.ONE,
                new BigDecimal("10000.00"));
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
                List.of(in),
                List.of(out),
                new BigDecimal("-165"),
                new BigDecimal("10000.00"),
                List.of()));
        UUID product = UUID.randomUUID();
        shop.publish(new ProductCreated(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                product,
                1,
                EventMetadata.empty(),
                ProductId.of(product),
                "OIL",
                "Cooking oil",
                "LIQUID",
                "ml",
                new ProductTracking(false, false, false, true, false),
                null,
                null,
                "ACTIVE"));
        shop.publish(new VariantCreated(
                UUID.randomUUID(),
                shop.tenant,
                SOLD,
                OIL,
                1,
                EventMetadata.empty(),
                VariantId.of(OIL),
                ProductId.of(product),
                "OIL",
                "Cooking oil",
                List.of(),
                List.of("SEALED", "OPEN"),
                "BREAK_ON_SALE",
                "ACTIVE",
                "ml",
                null,
                new ProductTracking(false, false, false, true, false),
                List.of()));
        awaitLog(shop, 9);

        assertThat(shop.sum("SELECT sum(amount) FROM reporting.fact_payment")).isEqualByComparingTo("600.00");
        assertThat(shop.sum("SELECT refunded FROM reporting.return_watch")).isEqualByComparingTo("400.00");
        assertThat(shop.sum("SELECT expenses FROM reporting.daily_summary")).isEqualByComparingTo("2000.00");
        assertThat(shop.sum("SELECT variance FROM reporting.fact_drawer")).isEqualByComparingTo("-100.00");
        assertThat(shop.count("SELECT count(*) FROM reporting.fact_stock_movement"))
                .isEqualTo(4L);
        assertThat(shop.sum("SELECT cost_amount FROM reporting.fact_stock_movement WHERE reason = 'BREAKAGE'"))
                .isEqualByComparingTo("-540.00");
        assertThat(shop.<String>sql(j -> j.sql("""
                                SELECT p.name || ' / ' || v.sku FROM reporting.variant_ref v
                                  JOIN reporting.product_ref p ON p.tenant_id = v.tenant_id AND p.product_id = v.product_id
                                """).query(String.class).single()))
                .isEqualTo("Cooking oil / OIL");
    }

    @Test
    void rep01TheRebuildReplaysTheLogIntoTheSameReadModels() {
        ReportingShop shop = shop("Reporting rebuild");
        UUID bill = UUID.randomUUID();
        UUID oilLine = UUID.randomUUID();
        SaleCompleted sale = shop.sale(bill, null, DAY, List.of(line(oilLine, OIL, "1000.00", "0.00", "0.00")), "CASH");
        shop.publish(shop.issued("SALE", bill, bill, oilLine, OIL, "-700.00"));
        shop.publish(sale);
        UUID note = UUID.randomUUID();
        shop.publish(shop.returned(note, bill, oilLine, OIL, "400.00", "CASH"));
        shop.publish(shop.issued("SALE_RETURN", note, bill, oilLine, OIL, "280.00"));
        awaitLog(shop, 4);
        var built = shop.snapshot();

        int replayed = TenantContext.callWith(
                shop.tenant, () -> new TransactionTemplate(transactions).execute(s -> rebuild.replay()));

        assertThat(replayed).isEqualTo(4);
        assertThat(shop.snapshot()).isEqualTo(built);
    }

    @Test
    void aVoidWaitsForItsSale() {
        ReportingShop shop = shop("Reporting void order");
        SaleCompleted sale = shop.sale(
                UUID.randomUUID(), null, DAY, List.of(line(UUID.randomUUID(), EGGS, "10.00", "0.00", "0.00")), "CASH");

        assertThatThrownBy(() -> TenantContext.runWith(
                        shop.tenant,
                        () -> recorder.record(
                                UUID.randomUUID(),
                                SOLD,
                                new Fact.Voided(sale.billId(), LocalDate.parse("2026-11-26")))))
                .isInstanceOf(OutOfOrderEventException.class);
        assertThat(shop.count("SELECT count(*) FROM reporting.event_log")).isZero();
    }

    @Test
    void everyReportingTableIsTenantIsolatedAndTheLogIsInsertOnly() throws Exception {
        RlsCoverage.assertCovered(List.of("reporting"), List.of("reporting.event_log"));
        ReportingShop a = shop("Reporting isolation A");
        ReportingShop b = shop("Reporting isolation B");
        a.publish(a.sale(
                UUID.randomUUID(), null, DAY, List.of(line(UUID.randomUUID(), EGGS, "10.00", "0.00", "0.00")), "CASH"));
        awaitLog(a, 1);

        assertThat(b.count("SELECT count(*) FROM reporting.event_log")).isZero();
        assertThat(b.count("SELECT count(*) FROM reporting.fact_sale_line")).isZero();
        assertThat(b.count("SELECT count(*) FROM reporting.daily_summary")).isZero();
    }
}
