package com.bmp.finance;

import static com.bmp.finance.FinanceShop.line;
import static com.bmp.finance.FinanceShop.tender;
import static com.bmp.test.web.ApiClient.etag;
import static com.bmp.test.web.ApiClient.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.finance.api.LedgerQueries;
import com.bmp.finance.api.dto.AgingView;
import com.bmp.finance.application.PeriodCloseRequested;
import com.bmp.finance.application.command.PeriodCloseHandlers;
import com.bmp.persistence.operation.Operations;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.test.persistence.WithPostgres;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Replays by document id, parties of the wrong kind, the rebuild of a stale row and the ledger API for reporting. */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinanceReplaysAndQueriesIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    FakeDrawers drawers;

    @Autowired
    LedgerQueries ledger;

    @Autowired
    PeriodCloseHandlers close;

    @Autowired
    Operations operations;

    private FinanceShop shop(String name) {
        return new FinanceShop(mvc, jdbc, transactions, publisher, name);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private <T> T inTenant(FinanceShop shop, Supplier<T> action) {
        return TenantContext.callWith(
                shop.tenant, () -> new TransactionTemplate(transactions).execute(s -> action.get()));
    }

    @Test
    void aDocumentSentAgainWithItsIdUnderAnotherKeyAnswersTheFirstOne() {
        FinanceShop shop = shop("Finance replays");
        UUID session = drawers.open(shop.register, Instant.parse("2026-11-25T02:00:00Z"));
        UUID expense = UUID.randomUUID();
        String expenseBody = "{\"id\": \"" + expense + "\", \"categoryId\": \"" + shop.categoryId("OTHER")
                + "\", \"expenseDate\": \"2026-11-25\", \"amount\": \"75.00\", \"description\": \"Tea\","
                + " \"paidFrom\": \"BANK\", \"attachmentKeys\": [\"receipts/2026/11/tea.jpg\"]}";
        MvcTestResult first = shop.api.post("/api/v1/expenses", shop.owner, expenseBody, key());
        assertThat(body(shop.api.post("/api/v1/expenses", shop.owner, expenseBody, key())))
                .isEqualTo(body(first));
        assertThat(json(first).path("attachmentKeys").get(0).asString()).isEqualTo("receipts/2026/11/tea.jpg");

        UUID drop = UUID.randomUUID();
        String dropBody = "{\"id\": \"" + drop + "\", \"drawerSessionId\": \"" + session
                + "\", \"amount\": \"100.00\", \"droppedAt\": \"2026-11-25T10:00:00Z\"}";
        MvcTestResult dropped = shop.api.post("/api/v1/cash-drops", shop.owner, dropBody, key());
        assertThat(body(shop.api.post("/api/v1/cash-drops", shop.owner, dropBody, key())))
                .isEqualTo(body(dropped));
        assertThat(drawers.movements(session)).hasSize(1);

        UUID supplier = UUID.randomUUID();
        shop.supplierKnown(supplier, 0);
        UUID payment = UUID.randomUUID();
        String paymentBody = "{\"id\": \"" + payment + "\", \"supplierId\": \"" + supplier
                + "\", \"amount\": \"10.00\", \"paidFrom\": \"BANK\", \"paidAt\": \"2026-11-25T10:00:00Z\"}";
        MvcTestResult paid = shop.api.post("/api/v1/supplier-payments", shop.owner, paymentBody, key());
        assertThat(paid).hasStatus(201);
        assertThat(body(shop.api.post("/api/v1/supplier-payments", shop.owner, paymentBody, key())))
                .isEqualTo(body(paid));

        UUID journal = UUID.randomUUID();
        String journalBody = "{\"id\": \"" + journal + "\", \"entryDate\": \"2026-11-25\", \"memo\": \"Fix\","
                + " \"lines\": [{\"accountId\": \"" + shop.accountId("BANK") + "\", \"debit\": \"5.00\","
                + " \"partyType\": \"SUPPLIER\", \"partyId\": \"" + supplier + "\", \"dimensions\": {\"channel\":"
                + " \"RETAIL\"}}, {\"accountId\": \"" + shop.accountId("EQUITY") + "\", \"credit\": \"5.00\"}]}";
        MvcTestResult posted = shop.api.post("/api/v1/journals", shop.owner, journalBody, key());
        assertThat(json(posted)
                        .path("lines")
                        .get(0)
                        .path("dimensions")
                        .path("channel")
                        .asString())
                .isEqualTo("RETAIL");
        assertThat(body(shop.api.post("/api/v1/journals", shop.owner, journalBody, key())))
                .isEqualTo(body(posted));
        UUID reversal = UUID.randomUUID();
        String reverseBody = "{\"id\": \"" + reversal + "\", \"memo\": \"Undo\", \"entryDate\": \"2026-11-24\"}";
        MvcTestResult reversed =
                shop.api.post("/api/v1/journals/" + journal + "/reverse", shop.owner, reverseBody, key());
        assertThat(json(reversed).path("entryDate").asString()).isEqualTo("2026-11-24");
        assertThat(body(shop.api.post("/api/v1/journals/" + journal + "/reverse", shop.owner, reverseBody, key())))
                .isEqualTo(body(reversed));
        assertThat(shop.unbalanced()).isZero();
    }

    @Test
    void aCustomerIsNoSupplierAndInactiveAccountsTakeNoManualLines() {
        FinanceShop shop = shop("Finance wrong kinds");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 0);

        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"categoryId\": \"" + shop.categoryId("OTHER")
                                + "\", \"expenseDate\": \"2026-11-25\","
                                + " \"amount\": \"1.00\", \"description\": \"x\", \"paidFrom\": \"PAYABLE\", \"supplierId\":"
                                + " \"" + customer + "\"}",
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");
        assertThatProblem(shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"supplierId\": \"" + customer
                                + "\", \"amount\": \"1.00\", \"paidFrom\": \"BANK\"}",
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");

        UUID other = shop.accountId("EXP_OTHER");
        UUID inactive = UUID.randomUUID();
        assertThat(shop.api.post(
                        "/api/v1/accounts",
                        shop.owner,
                        "{\"id\": \"" + inactive + "\", \"code\": \"6950\","
                                + " \"name\": \"Old\", \"type\": \"EXPENSE\"}",
                        key()))
                .hasStatus(201);
        MvcTestResult account = shop.api.get("/api/v1/accounts/" + inactive, shop.owner);
        assertThat(shop.api.patch(
                        "/api/v1/accounts/" + inactive, shop.owner, "{\"status\": \"INACTIVE\"}", etag(account)))
                .hasStatusOk();
        assertThatProblem(shop.api.post(
                        "/api/v1/journals",
                        shop.owner,
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"entryDate\": \"2026-11-25\", \"memo\": \"x\", \"lines\": [{\"accountId\": \""
                                + inactive + "\", \"debit\": \"1.00\"}, {\"accountId\": \"" + other
                                + "\", \"credit\": \"1.00\"}]}",
                        key()))
                .hasStatus(422)
                .hasCode("ACCOUNT_INACTIVE");
        assertThatProblem(shop.api.post(
                        "/api/v1/journals",
                        shop.owner,
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"entryDate\": \"2026-11-25\", \"memo\": \"x\", \"lines\": [{\"accountId\": \""
                                + other + "\"}, {\"accountId\": \"" + other + "\", \"credit\": \"1.00\"}]}",
                        key()))
                .hasStatus(422)
                .hasFieldError("lines[0]", "ONE_SIDE");

        MvcTestResult category = shop.api.get("/api/v1/expense-categories?limit=1", shop.owner);
        String id = json(category).path("items").get(0).path("id").asString();
        assertThat(shop.api.patch(
                        "/api/v1/expense-categories/" + id, shop.owner, "{\"status\": \"INACTIVE\"}", "W/\"0\""))
                .hasStatusOk();
    }

    @Test
    void theRebuildZeroesABalanceRowWithoutOpenItems() {
        FinanceShop shop = shop("Finance stale balance");
        shop.sql(j -> j.sql("""
                        INSERT INTO finance.party_balance (tenant_id, party_type, party_id, balance, oldest_open_date)
                        VALUES (platform.current_tenant_id(), 'CUSTOMER', :p, 5, DATE '2026-11-01')
                        """).param("p", UUID.randomUUID()).update());

        MvcTestResult result = shop.api.post("/api/v1/admin/finance/party-balances/rebuild", shop.owner, "", key());

        assertThat(json(result).path("changed").asInt()).isEqualTo(1);
        assertThat(shop.count(
                        "SELECT count(*) FROM finance.party_balance WHERE balance = 0 AND oldest_open_date IS NULL"))
                .isEqualTo(1L);
    }

    @Test
    void reportingReadsProfitAgingOverdueAndClearingFromTheLedger() {
        FinanceShop shop = shop("Finance ledger API");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 0);
        shop.publish(shop.sale(
                UUID.randomUUID(),
                customer,
                LocalDate.parse("2026-11-01"),
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "400.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "400.00"))));
        UUID walkIn = UUID.randomUUID();
        shop.publish(shop.sale(
                walkIn,
                null,
                LocalDate.parse("2026-11-02"),
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "60.00", "0.00", "0.00")),
                List.of()));
        shop.awaitJournals("SALE", 2);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(shop.count("SELECT count(*) FROM finance.open_item"))
                        .isEqualTo(1L));

        var pl = inTenant(shop, () -> ledger.profitAndLoss(YearMonth.of(2026, 11), YearMonth.of(2026, 11)));
        assertThat(pl.revenue()).isEqualByComparingTo("460.00");
        assertThat(pl.profit()).isEqualByComparingTo("460.00");
        assertThat(pl.currency()).isEqualTo("LKR");
        AgingView aging = inTenant(shop, () -> ledger.aging("CUSTOMER", LocalDate.parse("2026-12-15")));
        assertThat(aging.rows()).singleElement().satisfies(r -> {
            assertThat(r.partyId()).isEqualTo(customer);
            assertThat(r.buckets().days31To60()).isEqualByComparingTo("400.00");
        });
        assertThat(aging.totals().total()).isEqualByComparingTo("400.00");
        assertThat(inTenant(shop, () -> ledger.overdueReceivables(LocalDate.parse("2026-11-20"))))
                .isEqualByComparingTo("400.00");
        assertThat(inTenant(shop, () -> ledger.posClearingNotZero(LocalDate.parse("2026-11-25"))))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.billId()).isEqualTo(walkIn);
                    assertThat(d.amount()).isEqualByComparingTo("60.00");
                    assertThat(d.since()).isEqualTo(LocalDate.parse("2026-11-02"));
                });
        assertThat(inTenant(shop, () -> ledger.lastPostedAt())).isPresent();
    }

    @Test
    void aCloseRunsOnceAndOnlyForAPeriodThatIsClosing() {
        FinanceShop shop = shop("Finance close once");
        UUID operation = UUID.randomUUID();
        inTenant(shop, () -> {
            operations.create(operation, "PERIOD_CLOSE");
            return null;
        });
        PeriodCloseRequested open = new PeriodCloseRequested(
                UUID.randomUUID(),
                shop.tenant,
                Instant.now(),
                operation,
                0,
                EventMetadata.empty(),
                operation,
                "2026-09",
                List.of());

        inTenant(shop, () -> {
            close.run(open);
            return null;
        });
        var failed = inTenant(shop, () -> operations.find(operation)).orElseThrow();
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.problem()).contains("PERIOD_ALREADY_CLOSED");

        inTenant(shop, () -> {
            close.run(open);
            return null;
        });
        assertThat(inTenant(shop, () -> operations.find(operation))
                        .orElseThrow()
                        .updatedAt())
                .as("a finished operation is left alone")
                .isEqualTo(failed.updatedAt());
        inTenant(shop, () -> {
            close.run(new PeriodCloseRequested(
                    UUID.randomUUID(),
                    shop.tenant,
                    Instant.now(),
                    UUID.randomUUID(),
                    0,
                    EventMetadata.empty(),
                    UUID.randomUUID(),
                    "2026-09",
                    List.of()));
            return null;
        });
    }
}
