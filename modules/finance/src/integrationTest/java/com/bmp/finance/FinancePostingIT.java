package com.bmp.finance;

import static com.bmp.finance.FinanceShop.DAY;
import static com.bmp.finance.FinanceShop.cost;
import static com.bmp.finance.FinanceShop.line;
import static com.bmp.finance.FinanceShop.receiptLine;
import static com.bmp.finance.FinanceShop.returnedLine;
import static com.bmp.finance.FinanceShop.tender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bmp.finance.FinanceTestApplication.FinanceEventRecorder;
import com.bmp.finance.api.events.JournalPosted;
import com.bmp.finance.application.command.SalesPostingHandler;
import com.bmp.finance.application.command.StockPostingHandler;
import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.sales.api.events.RefundTender;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.tenant.api.events.TenantCreated;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
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
import tools.jackson.databind.json.JsonMapper;

/** Rules FIN-01..FIN-05 and FIN-07 through the listeners, with events published as their modules publish them. */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinancePostingIT {

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
    FinanceEventRecorder recorded;

    @Autowired
    SalesPostingHandler sales;

    @Autowired
    StockPostingHandler stock;

    @Autowired
    MeterRegistry meters;

    @Autowired
    JsonMapper json;

    private FinanceShop shop(String name) {
        return new FinanceShop(mvc, jdbc, transactions, publisher, name);
    }

    private SaleCompleted cashSale(FinanceShop shop, UUID bill, UUID eggsLine, UUID oilLine) {
        return shop.sale(
                bill,
                null,
                DAY,
                List.of(
                        line(eggsLine, EGGS, "550.00", "0.00", "0.00"),
                        line(oilLine, OIL, "1000.00", "152.54", "50.00")),
                List.of(tender("CASH", "1550.00")));
    }

    @Test
    void fin01TheChartIsSeededOnceWithItsExpenseCategories() {
        FinanceShop shop = shop("Finance chart");

        assertThat(shop.count("SELECT count(*) FROM finance.account WHERE system_key IS NOT NULL"))
                .isEqualTo(29L);
        assertThat(shop.count("SELECT count(*) FROM finance.expense_category")).isEqualTo(7L);
        assertThat(shop.<String>sql(j -> j.sql("SELECT code FROM finance.account WHERE system_key = 'POS_CLEARING'")
                        .query(String.class)
                        .single()))
                .isEqualTo("1110");

        shop.publish(new TenantCreated(
                UUID.randomUUID(),
                shop.tenant,
                FinanceShop.SOLD,
                shop.tenant.value(),
                1,
                EventMetadata.empty(),
                "RETAIL_GROCERY",
                "LKR",
                "Asia/Colombo",
                0,
                null));
        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(shop.count("SELECT count(*) FROM finance.account"))
                        .isEqualTo(29L));
    }

    @Test
    void fin02ASaleItsPaymentAndItsStockPostOneBalancedJournalEach() {
        FinanceShop shop = shop("Finance sale");
        UUID bill = UUID.randomUUID();
        UUID eggsLine = UUID.randomUUID();
        UUID oilLine = UUID.randomUUID();

        shop.publish(cashSale(shop, bill, eggsLine, oilLine));
        shop.awaitJournals("SALE", 1);
        shop.publish(shop.payment(UUID.randomUUID(), bill, null, "CASH", "1550.00"));
        shop.publish(shop.issued(
                "SALE", bill, bill, List.of(cost(eggsLine, EGGS, "-300.00"), cost(oilLine, OIL, "-529.5012"))));

        shop.awaitJournals("BILL_PAYMENT", 1);
        shop.awaitJournals("STOCK_ISSUE", 1);
        assertThat(shop.unbalanced()).isZero();
        assertThat(shop.balance("POS_CLEARING")).isEqualByComparingTo("0");
        assertThat(shop.balance("CASH_DRAWER")).isEqualByComparingTo("1550.00");
        assertThat(shop.balance("SALES")).isEqualByComparingTo("-1447.46");
        assertThat(shop.balance("SALES_DISCOUNTS")).isEqualByComparingTo("50.00");
        assertThat(shop.balance("TAX_PAYABLE")).isEqualByComparingTo("-152.54");
        assertThat(shop.balance("COGS")).isEqualByComparingTo("829.5012");
        assertThat(shop.balance("INVENTORY")).isEqualByComparingTo("-829.5012");
        assertThat(shop.<String>sql(j -> j.sql("SELECT number FROM finance.journal_entry WHERE source_type = 'SALE'")
                        .query(String.class)
                        .single()))
                .matches("JE-[0-9]{6}");
        assertThat(shop.<LocalDate>sql(
                        j -> j.sql("SELECT entry_date FROM finance.journal_entry WHERE source_type = 'STOCK_ISSUE'")
                                .query(LocalDate.class)
                                .single()))
                .as("the cost is dated like the sale")
                .isEqualTo(DAY);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(recorded.of(JournalPosted.class, shop.tenant))
                        .hasSize(3));
        JournalPosted posted = recorded.of(JournalPosted.class, shop.tenant).stream()
                .filter(e -> e.sourceType().equals("SALE"))
                .findFirst()
                .orElseThrow();
        assertThat(posted.lines()).hasSize(5);
        assertThat(posted.lines().getFirst().systemKey()).isEqualTo("POS_CLEARING");
        EventContract.assertConforms(json.writeValueAsString(posted), "JournalPosted");
    }

    @Test
    void fin02ARedeliveredEventPostsNothingMore() {
        FinanceShop shop = shop("Finance redelivery");
        SaleCompleted sale = cashSale(shop, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        shop.publish(sale);
        shop.publish(sale);
        shop.awaitJournals("SALE", 1);

        await().during(Duration.ofSeconds(2))
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(shop.journals("SALE")).isEqualTo(1L));
    }

    @Test
    void fin03AJournalThatDoesNotBalanceIsParkedCountedAndNotPosted() {
        FinanceShop shop = shop("Finance imbalance");
        UUID bill = UUID.randomUUID();
        UUID oilLine = UUID.randomUUID();
        shop.publish(cashSale(shop, bill, UUID.randomUUID(), oilLine));
        shop.awaitJournals("SALE", 1);
        double before = meters.counter("bmp.ledger.imbalance").count();

        // The credit note says 1000.00 but its line 999.00: a bug upstream, never a journal.
        shop.publish(shop.returned(
                UUID.randomUUID(),
                bill,
                null,
                "1000.00",
                List.of(returnedLine(oilLine, OIL, "999.00", "152.54", true)),
                List.of()));

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(shop.count("""
                        SELECT count(*) FROM platform.failed_event
                         WHERE consumer LIKE '%SalesPostingListener%' AND status = 'PARKED'
                           AND problem ->> 'code' = 'LEDGER_IMBALANCE'
                        """)).isEqualTo(1L));
        assertThat(meters.counter("bmp.ledger.imbalance").count()).isEqualTo(before + 1);
        assertThat(shop.journals("SALE_RETURN")).isZero();
        assertThat(shop.unbalanced()).isZero();
    }

    @Test
    void fin04AVoidReversesTheSaleExactlyAndWaitsForIt() {
        FinanceShop shop = shop("Finance void");
        UUID bill = UUID.randomUUID();
        SaleCompleted sale = cashSale(shop, bill, UUID.randomUUID(), UUID.randomUUID());

        assertThatThrownBy(() -> TenantContext.runWith(
                        shop.tenant,
                        () -> new TransactionTemplate(transactions)
                                .executeWithoutResult(s -> sales.voided(shop.voided(sale)))))
                .isInstanceOf(OutOfOrderEventException.class);

        shop.publish(sale);
        shop.awaitJournals("SALE", 1);
        shop.publish(shop.voided(sale));
        shop.awaitJournals("SALE_VOID", 1);

        assertThat(shop.<Boolean>sql(j -> j.sql("""
                                SELECT v.reverses_id = s.id FROM finance.journal_entry v, finance.journal_entry s
                                 WHERE v.source_type = 'SALE_VOID' AND s.source_type = 'SALE'
                                """).query(Boolean.class).single()))
                .as("the void points back at the sale")
                .isTrue();
        for (String key : List.of("POS_CLEARING", "SALES", "SALES_DISCOUNTS", "TAX_PAYABLE")) {
            assertThat(shop.balance(key)).as(key).isEqualByComparingTo("0");
        }
        assertThat(shop.unbalanced()).isZero();
    }

    @Test
    void fin05APostingDatedInAClosedPeriodLandsOnTheFirstOpenDay() {
        FinanceShop shop = shop("Finance late posting");
        shop.sql(j -> j.sql("""
                        INSERT INTO finance.fiscal_period (tenant_id, period, status, closed_at)
                        VALUES (platform.current_tenant_id(), '2026-10', 'CLOSED', now())
                        """).update());
        UUID bill = UUID.randomUUID();

        shop.publish(shop.sale(
                bill,
                null,
                LocalDate.parse("2026-10-31"),
                List.of(line(UUID.randomUUID(), EGGS, "275.00", "0.00", "0.00")),
                List.of(tender("CASH", "275.00"))));
        shop.awaitJournals("SALE", 1);

        assertThat(shop.<String>sql(
                        j -> j.sql("SELECT period || ' ' || entry_date || ' ' || memo FROM finance.journal_entry")
                                .query(String.class)
                                .single()))
                .isEqualTo("2026-11 2026-11-01 Bill R1-" + bill.toString().substring(30)
                        + "; late posting for 2026-10-31");
    }

    @Test
    void fin07CreditBillsOpenReceivablesThatCollectionsAndReturnsSettleOldestFirst() {
        FinanceShop shop = shop("Finance receivables");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 30);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID secondLine = UUID.randomUUID();
        shop.publish(shop.sale(
                first,
                customer,
                LocalDate.parse("2026-11-01"),
                List.of(line(UUID.randomUUID(), EGGS, "500.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "500.00"))));
        shop.publish(shop.sale(
                second,
                customer,
                LocalDate.parse("2026-11-10"),
                List.of(line(secondLine, OIL, "800.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "800.00"))));
        shop.awaitJournals("SALE", 2);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(shop.count("SELECT count(*) FROM finance.open_item"))
                        .isEqualTo(2L));
        assertThat(shop.<LocalDate>sql(j -> j.sql("SELECT due_date FROM finance.open_item WHERE document_id = :bill")
                        .param("bill", first)
                        .query(LocalDate.class)
                        .single()))
                .isEqualTo(LocalDate.parse("2026-12-01"));

        shop.publish(shop.payment(UUID.randomUUID(), null, customer, "CASH", "600.00"));
        shop.awaitJournals("COLLECTION", 1);
        shop.publish(shop.returned(
                UUID.randomUUID(),
                second,
                customer,
                "200.00",
                List.of(returnedLine(secondLine, OIL, "200.00", "0.00", true)),
                List.of(new RefundTender(UUID.randomUUID(), "CREDIT", new BigDecimal("200.00"), null, null))));
        shop.awaitJournals("SALE_RETURN", 1);

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(open(shop, first)).isEqualByComparingTo("0"));
        assertThat(open(shop, second)).isEqualByComparingTo("500.00");
        assertThat(shop.<BigDecimal>sql(j -> j.sql("SELECT balance FROM finance.party_balance WHERE party_id = :c")
                        .param("c", customer)
                        .query(BigDecimal.class)
                        .single()))
                .isEqualByComparingTo("500.00");
        assertThat(shop.balance("AR")).as("AR = Σ open customer items").isEqualByComparingTo("500.00");
        assertThat(shop.count("SELECT count(*) FROM finance.open_item_settlement"))
                .isEqualTo(3L);
    }

    @Test
    void fin07ASupplierInvoiceOpensAPayableAndClearsGoodsReceivedNotInvoiced() {
        FinanceShop shop = shop("Finance payables");
        UUID supplier = UUID.randomUUID();
        UUID receipt = UUID.randomUUID();
        UUID eggsLine = UUID.randomUUID();
        shop.publish(shop.goodsReceived(receipt, supplier, List.of(receiptLine(eggsLine, EGGS, "48000.00"))));
        shop.awaitJournals("GOODS_RECEIPT", 1);
        shop.publish(shop.invoice(UUID.randomUUID(), supplier, receipt, "48150.00", "48000.00"));
        shop.awaitJournals("SUPPLIER_INVOICE", 1);

        assertThat(shop.balance("INVENTORY")).isEqualByComparingTo("48000.00");
        assertThat(shop.balance("GRNI")).isEqualByComparingTo("0");
        assertThat(shop.balance("PPV")).isEqualByComparingTo("150.00");
        assertThat(shop.balance("AP")).isEqualByComparingTo("-48150.00");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(shop.<BigDecimal>sql(
                                j -> j.sql("""
                                SELECT sum(amount - settled) FROM finance.open_item WHERE party_type = 'SUPPLIER'
                                """).query(BigDecimal.class).single()))
                        .isEqualByComparingTo("48150.00"));
    }

    @Test
    void aReturnToTheSupplierAtCurrentCostPostsTheDifferenceToPpvOnceTheReceiptIsKnown() {
        FinanceShop shop = shop("Finance supplier return");
        UUID receipt = UUID.randomUUID();
        UUID returnLine = UUID.randomUUID();
        StockReceived stockOut = new StockReceived(
                UUID.randomUUID(),
                shop.tenant,
                FinanceShop.SOLD,
                receipt,
                0,
                EventMetadata.empty(),
                "GOODS_RECEIPT",
                receipt,
                LocationId.of(shop.location),
                "LKR",
                List.of(cost(returnLine, OIL, "-529.50")),
                List.<StockMovementLine>of());

        assertThatThrownBy(() -> TenantContext.runWith(
                        shop.tenant,
                        () -> new TransactionTemplate(transactions)
                                .executeWithoutResult(s -> stock.received(stockOut))))
                .isInstanceOf(OutOfOrderEventException.class);

        shop.publish(shop.goodsReceived(receipt, UUID.randomUUID(), List.of(receiptLine(returnLine, OIL, "-500.00"))));
        shop.awaitJournals("GOODS_RECEIPT", 1);
        shop.publish(stockOut);
        shop.awaitJournals("STOCK_RECEIPT", 1);

        assertThat(shop.balance("INVENTORY")).as("the stock left at its cost").isEqualByComparingTo("-529.50");
        assertThat(shop.balance("PPV")).isEqualByComparingTo("29.50");
        assertThat(shop.balance("GRNI")).isEqualByComparingTo("500.00");
    }

    @Test
    void theRebuildFindsTheProjectionAsTheOpenItemsLeftItAndRepairsADriftedRow() {
        FinanceShop shop = shop("Finance rebuild");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 7);
        shop.publish(shop.sale(
                UUID.randomUUID(),
                customer,
                DAY,
                List.of(line(UUID.randomUUID(), EGGS, "300.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "300.00"))));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(
                        () -> assertThat(shop.count("SELECT count(*) FROM finance.party_balance WHERE balance = 300"))
                                .isEqualTo(1L));

        assertThat(rebuild(shop)).isEqualTo("1/0");
        shop.sql(j -> j.sql("UPDATE finance.party_balance SET balance = 1").update());
        assertThat(rebuild(shop)).isEqualTo("1/1");
        assertThat(shop.count("SELECT count(*) FROM finance.party_balance WHERE balance = 300"))
                .isEqualTo(1L);
    }

    private String rebuild(FinanceShop shop) {
        var result = shop.api.post(
                "/api/v1/admin/finance/party-balances/rebuild",
                shop.owner,
                "",
                UUID.randomUUID().toString());
        assertThat(result).hasStatusOk();
        var body = com.bmp.test.web.ApiClient.json(result);
        return body.path("parties").asInt() + "/" + body.path("changed").asInt();
    }

    private static BigDecimal open(FinanceShop shop, UUID bill) {
        return shop.sql(j -> j.sql("SELECT amount - settled FROM finance.open_item WHERE document_id = :bill")
                .param("bill", bill)
                .query(BigDecimal.class)
                .single());
    }
}
