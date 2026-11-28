package com.bmp.finance;

import static com.bmp.finance.FinanceShop.line;
import static com.bmp.finance.FinanceShop.tender;
import static com.bmp.test.web.ApiClient.etag;
import static com.bmp.test.web.ApiClient.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.finance.FinanceTestApplication.FinanceEventRecorder;
import com.bmp.finance.api.events.PeriodClosed;
import com.bmp.persistence.event.FailedEventStore;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.OpenApiValidation;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Rule FIN-06: periods, their checks and their close as a background operation, the year end included. */
@SpringBootTest(classes = FinanceTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class FinancePeriodsIT {

    static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("finance");

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
    FailedEventStore failedEvents;

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

    private JsonNode close(FinanceShop shop, String period, String body) {
        MvcTestResult accepted = ok(
                shop.api.post(
                        "/api/v1/periods/" + period + "/close",
                        shop.owner,
                        body,
                        UUID.randomUUID().toString()),
                202);
        String operation = json(accepted).path("id").asString();
        assertThat(accepted).hasHeader("Location", "/api/v1/operations/" + operation);
        await().atMost(Duration.ofSeconds(30)).until(() -> finished(shop, operation));
        return shop.sql(j -> json.readTree(j.sql("""
                        SELECT json_build_object('status', status, 'problem', problem, 'result', result_ref)::text
                          FROM platform.operation WHERE id = :id
                        """)
                .param("id", UUID.fromString(operation))
                .query(String.class)
                .single()));
    }

    private static boolean finished(FinanceShop shop, String operation) {
        String status = shop.sql(j -> j.sql("SELECT status FROM platform.operation WHERE id = :id")
                .param("id", UUID.fromString(operation))
                .query(String.class)
                .single());
        return status.equals("SUCCEEDED") || status.equals("FAILED");
    }

    @Test
    void fin06AClosedPeriodNeedsZeroPosClearingOrAcknowledgedBillsAndThenRefusesPostings() {
        FinanceShop shop = shop("Finance close");
        UUID bill = UUID.randomUUID();
        shop.publish(shop.sale(
                bill,
                null,
                LocalDate.parse("2026-10-15"),
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "300.00", "0.00", "0.00")),
                List.of(tender("CASH", "300.00"))));
        shop.awaitJournals("SALE", 1);
        JsonNode checks = json(ok(shop.api.get("/api/v1/periods/2026-10/close-checks", shop.owner), 200));
        assertThat(checks.path("ready").asBoolean()).isFalse();
        assertThat(checks.path("posClearingDifferences").get(0).path("billId").asString())
                .isEqualTo(bill.toString());

        JsonNode refused = close(shop, "2026-10", "{}");
        assertThat(refused.path("status").asString()).isEqualTo("FAILED");
        assertThat(refused.path("problem").path("code").asString()).isEqualTo("POS_CLEARING_NOT_ZERO");
        assertThat(json(ok(shop.api.get("/api/v1/periods/2026-10", shop.owner), 200))
                        .path("status")
                        .asString())
                .isEqualTo("OPEN");

        JsonNode closed = close(shop, "2026-10", "{\"acknowledgedBillIds\": [\"" + bill + "\"]}");
        assertThat(closed.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(closed.path("result").asString()).isEqualTo("/api/v1/periods/2026-10");
        JsonNode period = json(ok(shop.api.get("/api/v1/periods/2026-10", shop.owner), 200));
        assertThat(period.path("status").asString()).isEqualTo("CLOSED");
        assertThat(period.path("yearEnd").asBoolean()).isFalse();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(recorded.of(PeriodClosed.class, shop.tenant)).hasSize(1));
        EventContract.assertConforms(
                json.writeValueAsString(
                        recorded.of(PeriodClosed.class, shop.tenant).getFirst()),
                "PeriodClosed");

        assertThatProblem(shop.api.post(
                        "/api/v1/periods/2026-10/close",
                        shop.owner,
                        "{}",
                        UUID.randomUUID().toString()))
                .hasStatus(409)
                .hasCode("PERIOD_ALREADY_CLOSED");
        assertThatProblem(shop.api.post(
                        "/api/v1/periods/2026-11/close",
                        shop.owner,
                        "{}",
                        UUID.randomUUID().toString()))
                .hasStatus(409)
                .hasCode("PERIOD_NOT_ENDED");
        assertRejected(shop.api.post(
                "/api/v1/periods/2026-1/close",
                shop.owner,
                "{}",
                UUID.randomUUID().toString()));
        assertRejected(shop.api.get("/api/v1/periods/2026-13", shop.owner));

        shop.publish(shop.sale(
                UUID.randomUUID(),
                null,
                LocalDate.parse("2026-10-20"),
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "50.00", "0.00", "0.00")),
                List.of(tender("CASH", "50.00"))));
        shop.awaitJournals("SALE", 2);
        assertThat(shop.count("SELECT count(*) FROM finance.journal_entry WHERE period = '2026-11'"))
                .as("FIN-05: a posting into the closed October lands in November")
                .isEqualTo(1L);
        JsonNode periods = json(ok(shop.api.get("/api/v1/periods?limit=1", shop.owner), 200));
        assertThat(periods.path("items").get(0).path("period").asString()).isEqualTo("2026-11");
        assertThat(json(ok(
                                shop.api.get(
                                        "/api/v1/periods?limit=1&cursor="
                                                + periods.path("page")
                                                        .path("nextCursor")
                                                        .asString(),
                                        shop.owner),
                                200))
                        .path("items")
                        .get(0)
                        .path("status")
                        .asString())
                .isEqualTo("CLOSED");
    }

    @Test
    void fin06EarlierPeriodsCloseFirstAndDrawersAndFailedEventsBlockAClose() {
        FinanceShop shop = shop("Finance close blockers");
        shop.publish(shop.sale(
                UUID.randomUUID(),
                null,
                LocalDate.parse("2026-09-10"),
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "10.00", "0.00", "0.00")),
                List.of()));
        shop.awaitJournals("SALE", 1);
        assertThatProblem(shop.api.post(
                        "/api/v1/periods/2026-10/close",
                        shop.owner,
                        "{}",
                        UUID.randomUUID().toString()))
                .hasStatus(409)
                .hasCode("EARLIER_PERIOD_OPEN");

        UUID session = drawers.open(shop.register, Instant.parse("2026-09-12T03:00:00Z"));
        JsonNode drawerRefusal = close(shop, "2026-09", "{\"acknowledgedBillIds\": []}");
        assertThat(drawerRefusal.path("problem").path("code").asString())
                .isIn("POS_CLEARING_NOT_ZERO", "DRAWERS_NOT_RECONCILED");
        String bill = shop.<String>sql(
                j -> j.sql("SELECT bill_id::text FROM finance.journal_line WHERE bill_id IS NOT NULL" + " LIMIT 1")
                        .query(String.class)
                        .single());
        JsonNode stillOpen = close(shop, "2026-09", "{\"acknowledgedBillIds\": [\"" + bill + "\"]}");
        assertThat(stillOpen.path("problem").path("code").asString()).isEqualTo("DRAWERS_NOT_RECONCILED");
        drawers.reconcile(session);

        TenantContext.runWith(
                shop.tenant,
                () -> failedEvents.park(
                        "test.consumer",
                        shop.sale(UUID.randomUUID(), null, LocalDate.parse("2026-09-10"), List.of(), List.of()),
                        new BusinessRuleViolation(PlatformErrorCode.VALIDATION_FAILED, Map.of())));
        JsonNode failed = close(shop, "2026-09", "{\"acknowledgedBillIds\": [\"" + bill + "\"]}");
        assertThat(failed.path("problem").path("code").asString()).isEqualTo("FAILED_EVENTS_PENDING");
        assertThat(json(ok(shop.api.get("/api/v1/periods/2026-09/close-checks", shop.owner), 200))
                        .path("failedEvents")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void fin06TheLastPeriodOfTheFiscalYearMovesProfitToRetainedEarnings() {
        FinanceShop shop = shop("Finance year end");
        MvcTestResult finance = shop.api.get("/api/v1/settings/finance", shop.owner);
        assertThat(shop.api.patch(
                        "/api/v1/settings/finance",
                        shop.owner,
                        "{\"values\": {\"fiscalYearStartMonth\": 11}}",
                        etag(finance)))
                .hasStatusOk();
        shop.publish(shop.sale(
                UUID.randomUUID(),
                null,
                LocalDate.parse("2026-10-05"),
                List.of(line(UUID.randomUUID(), UUID.randomUUID(), "900.00", "0.00", "0.00")),
                List.of()));
        shop.awaitJournals("SALE", 1);
        String bill = shop.<String>sql(
                j -> j.sql("SELECT bill_id::text FROM finance.journal_line WHERE bill_id IS NOT NULL" + " LIMIT 1")
                        .query(String.class)
                        .single());

        JsonNode closed = close(shop, "2026-10", "{\"acknowledgedBillIds\": [\"" + bill + "\"]}");

        assertThat(closed.path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(shop.journals("YEAR_END_CLOSE")).isEqualTo(1L);
        assertThat(shop.balance("SALES")).isEqualByComparingTo("0");
        assertThat(shop.balance("RETAINED_EARNINGS")).isEqualByComparingTo("-900.00");
        assertThat(json(ok(shop.api.get("/api/v1/ledger/profit-and-loss?from=2026-10&to=2026-10", shop.owner), 200))
                        .path("profit")
                        .path("amount")
                        .asString())
                .as("the closing entry is left out of profit and loss")
                .isEqualTo("900.00");
        assertThat(json(shop.api.get("/api/v1/periods/2026-10", shop.owner))
                        .path("yearEnd")
                        .asBoolean())
                .isTrue();
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(recorded.of(PeriodClosed.class, shop.tenant))
                        .singleElement()
                        .satisfies(e -> {
                            assertThat(e.yearEnd()).isTrue();
                            assertThat(e.closingJournalId()).isNotNull();
                        }));
    }
}
