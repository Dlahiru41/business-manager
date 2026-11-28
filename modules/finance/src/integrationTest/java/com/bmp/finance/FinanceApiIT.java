package com.bmp.finance;

import static com.bmp.finance.FinanceShop.DAY;
import static com.bmp.finance.FinanceShop.line;
import static com.bmp.finance.FinanceShop.receiptLine;
import static com.bmp.finance.FinanceShop.tender;
import static com.bmp.test.web.ApiClient.etag;
import static com.bmp.test.web.ApiClient.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.finance.FinanceTestApplication.FinanceEventRecorder;
import com.bmp.finance.api.events.ExpenseRecorded;
import com.bmp.finance.api.events.ReceivableOverdue;
import com.bmp.finance.application.command.OverdueHandler;
import com.bmp.sales.api.spi.CreditExposureProvider;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.PartyId;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.time.MutableClock;
import com.bmp.test.web.OpenApiValidation;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The finance API (T04-04): every operation, its error codes and its contract. */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinanceApiIT {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("finance");
    static final UUID EGGS = UUID.randomUUID();

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
    FinanceEventRecorder recorded;

    @Autowired
    OverdueHandler overdue;

    @Autowired
    CreditExposureProvider exposure;

    @Autowired
    MutableClock clock;

    @Autowired
    JsonMapper json;

    private FinanceShop shop(String name) {
        return new FinanceShop(mvc, jdbc, transactions, publisher, name);
    }

    private static MvcTestResult ok(MvcTestResult result, int status) {
        assertThat(result).hasStatus(status);
        CONTRACT.assertConforms(result);
        return result;
    }

    private static String body(MvcTestResult result) {
        return new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void assertRejected(MvcTestResult result) {
        assertThat(result.getResponse().getStatus()).as("a malformed request").isIn(400, 422);
        assertThat(body(result)).contains("\"code\"");
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    @Test
    void fin01TheChartIsListedPagedAndSystemAccountsAreLocked() {
        FinanceShop shop = shop("Finance accounts");
        JsonNode first = json(ok(shop.api.get("/api/v1/accounts?limit=20", shop.owner), 200));
        assertThat(first.path("items")).hasSize(20);
        assertThat(first.path("page").path("hasMore").asBoolean()).isTrue();
        JsonNode rest = json(ok(
                shop.api.get(
                        "/api/v1/accounts?limit=20&cursor="
                                + first.path("page").path("nextCursor").asString(),
                        shop.owner),
                200));
        assertThat(rest.path("items")).hasSize(9);
        assertThat(json(ok(shop.api.get("/api/v1/accounts?type=INCOME&status=ACTIVE", shop.owner), 200))
                        .path("items"))
                .hasSize(3);

        UUID cash = shop.accountId("CASH_DRAWER");
        MvcTestResult account = ok(shop.api.get("/api/v1/accounts/" + cash, shop.owner), 200);
        assertThatProblem(shop.api.patch(
                        "/api/v1/accounts/" + cash, shop.owner, "{\"type\": \"EXPENSE\"}", etag(account)))
                .hasStatus(409)
                .hasCode("SYSTEM_ACCOUNT_LOCKED");
        assertThatProblem(shop.api.patch(
                        "/api/v1/accounts/" + cash, shop.owner, "{\"status\": \"INACTIVE\"}", etag(account)))
                .hasStatus(409)
                .hasCode("SYSTEM_ACCOUNT_LOCKED");
        assertThatProblem(shop.api.patch("/api/v1/accounts/" + cash, shop.owner, "{\"name\": \"Till\"}", null))
                .hasStatus(428);
        MvcTestResult renamed = ok(
                shop.api.patch(
                        "/api/v1/accounts/" + cash,
                        shop.owner,
                        "{\"name\": \"Till cash\", \"code\": \"1001\", \"subtype\": \"till\"}",
                        etag(account)),
                200);
        assertThat(json(renamed).path("code").asString()).isEqualTo("1001");
        assertThatProblem(
                        shop.api.patch("/api/v1/accounts/" + cash, shop.owner, "{\"name\": \"Again\"}", etag(account)))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
        assertThatProblem(shop.api.get("/api/v1/accounts/" + UUID.randomUUID(), shop.owner))
                .hasStatus(404)
                .hasCode("ACCOUNT_NOT_FOUND");
    }

    @Test
    void accountsAreAddedUnderAParentWithUniqueCodes() {
        FinanceShop shop = shop("Finance new account");
        UUID packing = shop.accountId("EXP_PACKING");
        UUID id = UUID.randomUUID();
        String body = "{\"id\": \"" + id + "\", \"code\": \"6410\", \"name\": \"Bottles\", \"type\":"
                + " \"EXPENSE\", \"parentId\": \"" + packing + "\"}";

        MvcTestResult created = ok(shop.api.post("/api/v1/accounts", shop.owner, body, key()), 201);
        assertThat(created).hasHeader("Location", "/api/v1/accounts/" + id);
        assertThatProblem(shop.api.post(
                        "/api/v1/accounts",
                        shop.owner,
                        body.replace(id.toString(), UUID.randomUUID().toString()),
                        key()))
                .hasStatus(409)
                .hasCode("ACCOUNT_CODE_TAKEN");
        assertThatProblem(shop.api.post(
                        "/api/v1/accounts",
                        shop.owner,
                        body.replace(id.toString(), UUID.randomUUID().toString())
                                .replace("6410", "6420")
                                .replace(packing.toString(), UUID.randomUUID().toString()),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_PARENT_ACCOUNT");
        MvcTestResult account = shop.api.get("/api/v1/accounts/" + id, shop.owner);
        ok(
                shop.api.patch(
                        "/api/v1/accounts/" + id,
                        shop.owner,
                        "{\"status\": \"INACTIVE\", \"type\": \"ASSET\"}",
                        etag(account)),
                200);

        FinanceShop other = shop("Finance new account elsewhere");
        assertThatProblem(other.api.get("/api/v1/accounts/" + id, other.owner)).hasStatus(404);
    }

    @Test
    void fin04ManualJournalsBalanceNeedAMemoAndAreReversedOnce() {
        FinanceShop shop = shop("Finance manual journals");
        UUID bank = shop.accountId("BANK");
        UUID equity = shop.accountId("EQUITY");
        UUID journal = UUID.randomUUID();
        String body = "{\"id\": \"" + journal + "\", \"entryDate\": \"2026-11-20\", \"memo\": \"Owner's capital\","
                + " \"lines\": [{\"accountId\": \"" + bank + "\", \"debit\": \"100000.00\"},"
                + " {\"accountId\": \"" + equity + "\", \"credit\": \"100000.00\"}]}";
        String idem = key();

        MvcTestResult created = ok(shop.api.post("/api/v1/journals", shop.owner, body, idem), 201);
        assertThat(json(created).path("sourceType").asString()).isEqualTo("MANUAL");
        assertThat(json(created).path("totalDebit").path("amount").asString()).isEqualTo("100000.00");
        assertThat(shop.api
                        .post("/api/v1/journals", shop.owner, body, idem)
                        .getResponse()
                        .getContentAsByteArray())
                .as("an idempotent replay answers the same")
                .isEqualTo(created.getResponse().getContentAsByteArray());
        assertThat(shop.journals("MANUAL")).isEqualTo(1L);

        assertThatProblem(shop.api.post(
                        "/api/v1/journals",
                        shop.owner,
                        body.replace(journal.toString(), UUID.randomUUID().toString())
                                .replace("\"credit\": \"100000.00\"", "\"credit\": \"99999.00\""),
                        key()))
                .hasStatus(422)
                .hasCode("JOURNAL_UNBALANCED");
        assertThatProblem(shop.api.post(
                        "/api/v1/journals",
                        shop.owner,
                        body.replace(journal.toString(), UUID.randomUUID().toString())
                                .replace(equity.toString(), UUID.randomUUID().toString()),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_ACCOUNT");
        assertThatProblem(shop.api.post(
                        "/api/v1/journals",
                        shop.owner,
                        body.replace(journal.toString(), UUID.randomUUID().toString())
                                .replace("\"debit\": \"100000.00\"", "\"debit\": \"100000.00\", \"credit\": \"1.00\""),
                        key()))
                .hasStatus(422)
                .hasFieldError("lines[0]", "ONE_SIDE");
        assertThatProblem(shop.api.post(
                        "/api/v1/journals",
                        shop.owner,
                        body.replace(journal.toString(), UUID.randomUUID().toString())
                                .replace("\"memo\": \"Owner's capital\",", ""),
                        key()))
                .hasStatus(422);

        UUID reversal = UUID.randomUUID();
        String reverse = "{\"id\": \"" + reversal + "\", \"memo\": \"Entered twice\"}";
        MvcTestResult reversed =
                ok(shop.api.post("/api/v1/journals/" + journal + "/reverse", shop.owner, reverse, key()), 201);
        assertThat(json(reversed).path("reversesId").asString()).isEqualTo(journal.toString());
        assertThat(json(ok(shop.api.get("/api/v1/journals/" + journal, shop.owner), 200))
                        .path("reversedById")
                        .asString())
                .isEqualTo(reversal.toString());
        assertThatProblem(shop.api.post(
                        "/api/v1/journals/" + journal + "/reverse",
                        shop.owner,
                        reverse.replace(reversal.toString(), UUID.randomUUID().toString()),
                        key()))
                .hasStatus(409)
                .hasCode("JOURNAL_ALREADY_REVERSED");
        assertThatProblem(shop.api.post(
                        "/api/v1/journals/" + UUID.randomUUID() + "/reverse",
                        shop.owner,
                        reverse.replace(reversal.toString(), UUID.randomUUID().toString()),
                        key()))
                .hasStatus(404)
                .hasCode("JOURNAL_NOT_FOUND");
        assertThat(shop.balance("BANK")).isEqualByComparingTo("0");
    }

    @Test
    void journalsAreListedNewestFirstByFilterAndTheLedgerBalances() {
        FinanceShop shop = shop("Finance ledger");
        UUID bill = UUID.randomUUID();
        shop.publish(shop.sale(
                bill,
                null,
                DAY,
                List.of(line(UUID.randomUUID(), EGGS, "550.00", "0.00", "0.00")),
                List.of(tender("CASH", "550.00"))));
        shop.publish(shop.goodsReceived(
                UUID.randomUUID(), UUID.randomUUID(), List.of(receiptLine(UUID.randomUUID(), EGGS, "1000.00"))));
        shop.awaitJournals("SALE", 1);
        shop.awaitJournals("GOODS_RECEIPT", 1);

        JsonNode page = json(ok(shop.api.get("/api/v1/journals?limit=1", shop.owner), 200));
        assertThat(page.path("items")).hasSize(1);
        JsonNode next = json(ok(
                shop.api.get(
                        "/api/v1/journals?limit=1&cursor="
                                + page.path("page").path("nextCursor").asString(),
                        shop.owner),
                200));
        assertThat(next.path("items")).hasSize(1);
        assertThat(json(ok(
                                shop.api.get(
                                        "/api/v1/journals?sourceType=SALE&sourceId=" + bill + "&period=2026-11"
                                                + "&from=2026-11-01&to=2026-11-30&accountId=" + shop.accountId("SALES"),
                                        shop.owner),
                                200))
                        .path("items"))
                .hasSize(1);
        assertRejected(shop.api.get("/api/v1/journals?period=2026-13", shop.owner));

        JsonNode trial = json(ok(shop.api.get("/api/v1/ledger/trial-balance", shop.owner), 200));
        assertThat(trial.path("totalDebit").path("amount").asString())
                .isEqualTo(trial.path("totalCredit").path("amount").asString());
        assertThat(json(ok(shop.api.get("/api/v1/ledger/trial-balance?period=2026-11", shop.owner), 200))
                        .path("rows"))
                .hasSize(4);
        JsonNode pl = json(ok(shop.api.get("/api/v1/ledger/profit-and-loss?from=2026-11&to=2026-11", shop.owner), 200));
        assertThat(pl.path("revenue").path("amount").asString()).isEqualTo("550.00");
        assertThat(pl.path("profit").path("amount").asString()).isEqualTo("550.00");
        assertRejected(shop.api.get("/api/v1/ledger/profit-and-loss?from=2026-11&to=nope", shop.owner));
    }

    @Test
    void fin09ExpensesPostFromTheDrawerTheBankOrOnAccount() {
        FinanceShop shop = shop("Finance expenses");
        UUID packing = shop.categoryId("PACKING");
        UUID session = drawers.open(shop.register, Instant.parse("2026-11-25T02:00:00Z"));
        UUID fromDrawer = UUID.randomUUID();

        MvcTestResult bottles = ok(
                shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(
                                fromDrawer,
                                packing,
                                "\"paidFrom\": \"CASH_DRAWER\", \"drawerSessionId\": \"" + session + "\""),
                        key()),
                201);
        assertThat(json(bottles).path("journalId").asString()).isNotEmpty();
        assertThat(drawers.movements(session)).singleElement().satisfies(m -> {
            assertThat(m.type()).isEqualTo("PAID_OUT");
            assertThat(m.amount()).isEqualByComparingTo("2000.00");
            assertThat(m.movementId()).isEqualTo(fromDrawer);
        });
        assertThat(shop.balance("EXP_PACKING")).isEqualByComparingTo("2000.00");
        assertThat(shop.balance("CASH_DRAWER")).isEqualByComparingTo("-2000.00");
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(recorded.of(ExpenseRecorded.class, shop.tenant))
                        .hasSize(1));
        EventContract.assertConforms(
                json.writeValueAsString(
                        recorded.of(ExpenseRecorded.class, shop.tenant).getFirst()),
                "ExpenseRecorded");

        ok(
                shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(UUID.randomUUID(), shop.categoryId("RENT"), "\"paidFrom\": \"BANK\""),
                        key()),
                201);
        UUID supplier = UUID.randomUUID();
        shop.supplierKnown(supplier, 30);
        UUID electricity = UUID.randomUUID();
        MvcTestResult payable = ok(
                shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(
                                electricity,
                                shop.categoryId("UTILITIES"),
                                "\"paidFrom\": \"PAYABLE\", \"supplierId\": \"" + supplier + "\""),
                        key()),
                201);
        assertThat(json(payable).path("dueDate").asString()).isEqualTo("2026-12-25");
        assertThat(shop.balance("AP")).isEqualByComparingTo("-2000.00");
        assertThat(json(ok(shop.api.get("/api/v1/expenses/" + electricity, shop.owner), 200))
                        .path("supplierId")
                        .asString())
                .isEqualTo(supplier.toString());
        JsonNode list =
                json(ok(shop.api.get("/api/v1/expenses?limit=2&from=2026-11-01&to=2026-11-30", shop.owner), 200));
        assertThat(list.path("items")).hasSize(2);
        assertThat(json(ok(
                                shop.api.get(
                                        "/api/v1/expenses?limit=2&cursor="
                                                + list.path("page")
                                                        .path("nextCursor")
                                                        .asString(),
                                        shop.owner),
                                200))
                        .path("items"))
                .hasSize(1);
        assertThat(json(ok(
                                shop.api.get(
                                        "/api/v1/expenses?paidFrom=BANK&categoryId=" + shop.categoryId("RENT"),
                                        shop.owner),
                                200))
                        .path("items"))
                .hasSize(1);

        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(UUID.randomUUID(), packing, "\"paidFrom\": \"CASH_DRAWER\""),
                        key()))
                .hasStatus(422)
                .hasCode("DRAWER_SESSION_REQUIRED");
        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(
                                UUID.randomUUID(),
                                packing,
                                "\"paidFrom\": \"CASH_DRAWER\", \"drawerSessionId\": \"" + UUID.randomUUID() + "\""),
                        key()))
                .hasStatus(422)
                .hasCode("DRAWER_SESSION_NOT_FOUND");
        drawers.reconcile(session);
        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(
                                UUID.randomUUID(),
                                packing,
                                "\"paidFrom\": \"CASH_DRAWER\", \"drawerSessionId\": \"" + session + "\""),
                        key()))
                .hasStatus(409)
                .hasCode("DRAWER_NOT_OPEN");
        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(UUID.randomUUID(), packing, "\"paidFrom\": \"PAYABLE\""),
                        key()))
                .hasStatus(422)
                .hasCode("SUPPLIER_REQUIRED");
        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(
                                UUID.randomUUID(),
                                packing,
                                "\"paidFrom\": \"PAYABLE\", \"supplierId\": \"" + UUID.randomUUID() + "\""),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");
        assertThatProblem(shop.api.post(
                        "/api/v1/expenses",
                        shop.owner,
                        expense(UUID.randomUUID(), UUID.randomUUID(), "\"paidFrom\": \"BANK\""),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_EXPENSE_CATEGORY");
        assertThatProblem(shop.api.get("/api/v1/expenses/" + UUID.randomUUID(), shop.owner))
                .hasStatus(404)
                .hasCode("EXPENSE_NOT_FOUND");
        assertThat(shop.unbalanced()).isZero();
    }

    private static String expense(UUID id, UUID category, String paid) {
        return "{\"id\": \"" + id + "\", \"categoryId\": \"" + category + "\", \"expenseDate\": \"2026-11-25\","
                + " \"amount\": \"2000.00\", \"description\": \"200 empty bottles\", " + paid + "}";
    }

    @Test
    void expenseCategoriesPostToExpenseAccountsOnly() {
        FinanceShop shop = shop("Finance categories");
        UUID id = UUID.randomUUID();
        String body = "{\"id\": \"" + id + "\", \"code\": \"BOTTLES\", \"name\": \"Empty bottles\","
                + " \"accountId\": \"" + shop.accountId("EXP_PACKING") + "\"}";

        MvcTestResult created = ok(shop.api.post("/api/v1/expense-categories", shop.owner, body, key()), 201);
        assertThatProblem(shop.api.post(
                        "/api/v1/expense-categories",
                        shop.owner,
                        body.replace(id.toString(), UUID.randomUUID().toString()),
                        key()))
                .hasStatus(409)
                .hasCode("EXPENSE_CATEGORY_CODE_TAKEN");
        assertThatProblem(shop.api.post(
                        "/api/v1/expense-categories",
                        shop.owner,
                        body.replace(id.toString(), UUID.randomUUID().toString())
                                .replace("BOTTLES", "CASH")
                                .replace(
                                        shop.accountId("EXP_PACKING").toString(),
                                        shop.accountId("CASH_DRAWER").toString()),
                        key()))
                .hasStatus(422)
                .hasCode("ACCOUNT_NOT_EXPENSE");
        assertThatProblem(shop.api.post(
                        "/api/v1/expense-categories",
                        shop.owner,
                        body.replace(id.toString(), UUID.randomUUID().toString())
                                .replace("BOTTLES", "GHOST")
                                .replace(
                                        shop.accountId("EXP_PACKING").toString(),
                                        UUID.randomUUID().toString()),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_ACCOUNT");
        MvcTestResult updated = ok(
                shop.api.patch(
                        "/api/v1/expense-categories/" + id,
                        shop.owner,
                        "{\"name\": \"Bottles and caps\", \"accountId\": \"" + shop.accountId("EXP_OTHER")
                                + "\", \"status\": \"INACTIVE\"}",
                        etag(created)),
                200);
        assertThat(json(updated).path("status").asString()).isEqualTo("INACTIVE");
        assertThatProblem(shop.api.patch(
                        "/api/v1/expense-categories/" + id, shop.owner, "{\"name\": \"x\"}", etag(created)))
                .hasStatus(412);
        assertThatProblem(shop.api.patch(
                        "/api/v1/expense-categories/" + UUID.randomUUID(), shop.owner, "{\"name\": \"x\"}", "W/\"0\""))
                .hasStatus(404)
                .hasCode("EXPENSE_CATEGORY_NOT_FOUND");
        JsonNode page = json(ok(shop.api.get("/api/v1/expense-categories?limit=5", shop.owner), 200));
        assertThat(page.path("items")).hasSize(5);
        assertThat(json(ok(
                                shop.api.get(
                                        "/api/v1/expense-categories?limit=5&cursor="
                                                + page.path("page")
                                                        .path("nextCursor")
                                                        .asString(),
                                        shop.owner),
                                200))
                        .path("items"))
                .hasSize(3);
    }

    @Test
    void fin11SupplierPaymentsSettleTheAllocatedInvoiceAndThenTheOldest() {
        FinanceShop shop = shop("Finance supplier payments");
        UUID supplier = UUID.randomUUID();
        shop.supplierKnown(supplier, 30);
        UUID receipt = UUID.randomUUID();
        shop.publish(shop.goodsReceived(receipt, supplier, List.of(receiptLine(UUID.randomUUID(), EGGS, "48000.00"))));
        UUID invoice = UUID.randomUUID();
        shop.publish(shop.invoice(invoice, supplier, receipt, "48000.00", "48000.00"));
        shop.awaitJournals("SUPPLIER_INVOICE", 1);
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(
                                shop.count("SELECT count(*) FROM finance.open_item WHERE party_type = 'SUPPLIER'"))
                        .isEqualTo(1L));

        assertThatProblem(shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        payment(UUID.randomUUID(), supplier, "1000.00", "BANK", invoice, "48000.01"),
                        key()))
                .hasStatus(422)
                .hasCode("ALLOCATION_EXCEEDS_OPEN");
        assertThatProblem(shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        payment(UUID.randomUUID(), supplier, "1000.00", "BANK", invoice, "2000.00"),
                        key()))
                .hasStatus(422)
                .hasCode("ALLOCATION_EXCEEDS_PAYMENT");
        assertThatProblem(shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        payment(UUID.randomUUID(), supplier, "1000.00", "BANK", UUID.randomUUID(), "10.00"),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_OPEN_ITEM");
        assertThatProblem(shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        payment(UUID.randomUUID(), UUID.randomUUID(), "1000.00", "BANK", null, null),
                        key()))
                .hasStatus(422)
                .hasCode("UNKNOWN_SUPPLIER");

        UUID paid = UUID.randomUUID();
        ok(
                shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        payment(paid, supplier, "30000.00", "BANK", invoice, "30000.00"),
                        key()),
                201);
        UUID session = drawers.open(shop.register, Instant.parse("2026-11-25T02:00:00Z"));
        ok(
                shop.api.post(
                        "/api/v1/supplier-payments",
                        shop.owner,
                        payment(
                                UUID.randomUUID(),
                                supplier,
                                "18000.00",
                                "CASH_DRAWER\", \"drawerSessionId\": \"" + session,
                                null,
                                null),
                        key()),
                201);

        assertThat(shop.balance("AP")).isEqualByComparingTo("0");
        assertThat(shop.balance("BANK")).isEqualByComparingTo("-30000.00");
        assertThat(shop.balance("CASH_DRAWER")).isEqualByComparingTo("-18000.00");
        assertThat(drawers.movements(session)).hasSize(1);
        assertThat(json(ok(shop.api.get("/api/v1/supplier-payments/" + paid, shop.owner), 200))
                        .path("allocations"))
                .hasSize(1);
        assertThat(json(ok(
                                shop.api.get(
                                        "/api/v1/supplier-payments?supplierId=" + supplier + "&limit=1", shop.owner),
                                200))
                        .path("page")
                        .path("hasMore")
                        .asBoolean())
                .isTrue();
        assertThatProblem(shop.api.get("/api/v1/supplier-payments/" + UUID.randomUUID(), shop.owner))
                .hasStatus(404)
                .hasCode("SUPPLIER_PAYMENT_NOT_FOUND");
        JsonNode payables = json(ok(shop.api.get("/api/v1/payables", shop.owner), 200));
        assertThat(payables.path("totals").path("balance").path("amount").asString())
                .isEqualTo("0.00");
        JsonNode statement =
                json(ok(shop.api.get("/api/v1/payables/" + supplier + "/statement?from=2026-11-01", shop.owner), 200));
        assertThat(statement.path("entries")).hasSize(3);
        assertThat(statement.path("entries").findValuesAsString("documentType")).contains("SUPPLIER_INVOICE");
        assertThat(statement.path("entries").findValues("credit").stream()
                        .map(c -> c.path("amount").asString()))
                .contains("48000.00");
        assertThat(statement.path("closingBalance").path("amount").asString()).isEqualTo("0.00");
    }

    private static String payment(UUID id, UUID supplier, String amount, String from, UUID document, String allocated) {
        String allocation = document == null
                ? ""
                : ", \"allocations\": [{\"documentId\": \"" + document + "\", \"amount\": \"" + allocated + "\"}]";
        return "{\"id\": \"" + id + "\", \"supplierId\": \"" + supplier + "\", \"amount\": \"" + amount
                + "\", \"paidFrom\": \"" + from + "\"" + allocation + "}";
    }

    @Test
    void cashDropsLeaveTheDrawerForTheBank() {
        FinanceShop shop = shop("Finance cash drops");
        UUID session = drawers.open(shop.register, Instant.parse("2026-11-25T02:00:00Z"));
        UUID drop = UUID.randomUUID();

        ok(
                shop.api.post(
                        "/api/v1/cash-drops",
                        shop.owner,
                        "{\"id\": \"" + drop + "\", \"drawerSessionId\": \"" + session
                                + "\", \"amount\": \"40000.00\", \"reference\": \"Slip 0912\"}",
                        key()),
                201);

        assertThat(drawers.movements(session))
                .singleElement()
                .satisfies(m -> assertThat(m.type()).isEqualTo("DROP"));
        assertThat(shop.balance("BANK")).isEqualByComparingTo("40000.00");
        assertThat(json(ok(shop.api.get("/api/v1/cash-drops?drawerSessionId=" + session, shop.owner), 200))
                        .path("items"))
                .hasSize(1);
        assertThatProblem(shop.api.post(
                        "/api/v1/cash-drops",
                        shop.owner,
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"drawerSessionId\": \"" + UUID.randomUUID()
                                + "\", \"amount\": \"1.00\"}",
                        key()))
                .hasStatus(422)
                .hasCode("DRAWER_SESSION_NOT_FOUND");
    }

    @Test
    void fin07ReceivablesAreAgedAndAStatementShowsTheAccount() {
        FinanceShop shop = shop("Finance receivables API");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 0);
        shop.publish(shop.sale(
                UUID.randomUUID(),
                customer,
                java.time.LocalDate.parse("2026-09-01"),
                List.of(line(UUID.randomUUID(), EGGS, "500.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "500.00"))));
        shop.publish(shop.sale(
                UUID.randomUUID(),
                customer,
                DAY,
                List.of(line(UUID.randomUUID(), EGGS, "800.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "800.00"))));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(shop.count("SELECT count(*) FROM finance.open_item"))
                        .isEqualTo(2L));

        JsonNode aging = json(ok(shop.api.get("/api/v1/receivables?asOf=2026-11-30", shop.owner), 200));
        assertThat(aging.path("rows")).hasSize(1);
        assertThat(aging.path("rows")
                        .get(0)
                        .path("buckets")
                        .path("days61To90")
                        .path("amount")
                        .asString())
                .isEqualTo("500.00");
        assertThat(aging.path("totals").path("balance").path("amount").asString())
                .isEqualTo("1300.00");
        assertThat(json(ok(shop.api.get("/api/v1/receivables?overdueOnly=true&asOf=2026-09-01", shop.owner), 200))
                        .path("rows"))
                .isEmpty();
        JsonNode statement = json(ok(
                shop.api.get(
                        "/api/v1/receivables/" + customer + "/statement?from=2026-10-01&to=" + "2026-11-30",
                        shop.owner),
                200));
        assertThat(statement.path("openingBalance").path("amount").asString()).isEqualTo("500.00");
        assertThat(statement.path("entries")).hasSize(1);
        assertThat(statement.path("closingBalance").path("amount").asString()).isEqualTo("1300.00");
        assertThat(statement.path("openItems")).hasSize(2);
        assertThatProblem(shop.api.get("/api/v1/receivables/" + UUID.randomUUID() + "/statement", shop.owner))
                .hasStatus(404)
                .hasCode("PARTY_NOT_KNOWN");
        BigDecimal owed = TenantContext.callWith(
                shop.tenant,
                () -> new TransactionTemplate(transactions).execute(s -> exposure.exposureOf(PartyId.of(customer))));
        assertThat(owed).as("FIN-10: the exposure is what the customer owes").isEqualByComparingTo("1300.00");
    }

    @Test
    void fin08OverdueCustomersAreReportedOnceAWeek() {
        FinanceShop shop = shop("Finance overdue");
        UUID customer = UUID.randomUUID();
        shop.customerKnown(customer, 0);
        shop.publish(shop.sale(
                UUID.randomUUID(),
                customer,
                java.time.LocalDate.parse("2026-11-10"),
                List.of(line(UUID.randomUUID(), EGGS, "500.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "500.00"))));
        shop.publish(shop.sale(
                UUID.randomUUID(),
                customer,
                java.time.LocalDate.parse("2026-11-24"),
                List.of(line(UUID.randomUUID(), EGGS, "200.00", "0.00", "0.00")),
                List.of(tender("CREDIT", "200.00"))));
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(shop.count("SELECT count(*) FROM finance.open_item"))
                        .isEqualTo(2L));

        assertThat(report(shop)).isEqualTo(1);
        assertThat(report(shop)).as("not again within a week").isZero();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(recorded.of(ReceivableOverdue.class, shop.tenant))
                        .hasSize(1));
        ReceivableOverdue event =
                recorded.of(ReceivableOverdue.class, shop.tenant).getFirst();
        assertThat(event.overdueAmount()).isEqualByComparingTo("500.00");
        assertThat(event.balance()).isEqualByComparingTo("700.00");
        assertThat(event.daysOverdue()).isEqualTo(15);
        EventContract.assertConforms(json.writeValueAsString(event), "ReceivableOverdue");

        Instant now = clock.instant();
        try {
            clock.set(now.plus(Duration.ofDays(7)));
            assertThat(report(shop)).as("a week later").isEqualTo(1);
        } finally {
            clock.set(now);
        }
    }

    private int report(FinanceShop shop) {
        return TenantContext.callWith(shop.tenant, () -> overdue.report());
    }
}
