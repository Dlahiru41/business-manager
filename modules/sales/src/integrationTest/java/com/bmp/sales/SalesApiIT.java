package com.bmp.sales;

import static com.bmp.sales.SalesApi.cash;
import static com.bmp.sales.SalesApi.json;
import static com.bmp.sales.SalesApi.tender;
import static com.bmp.sales.SalesApi.totals;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.sales.SalesTestApplication.Approvers;
import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.api.events.SaleReturned;
import com.bmp.sales.api.events.SaleVoided;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** Online bills through the sales API with the pilot shop's catalog and prices (SAL-01..SAL-15). */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class SalesApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    @Autowired
    Approvers approvers;

    SalesApi api(String name) {
        return new SalesApi(mvc, jdbc, transactions, name);
    }

    static String eggs(SalesApi api, String qty, String extra) {
        return api.line("EGG-RED-L", "piece", qty, "55.00", extra);
    }

    @Test
    void sal02Sal08ADraftIsEditedThenCompletedAndPublishedWithBaseQuantities() {
        SalesApi api = api("Draft");
        UUID id = UUID.randomUUID();
        MvcTestResult draft = api.post(
                "/api/v1/bills",
                "{\"id\": \"" + id + "\", \"number\": \"R1-000001\", "
                        + "\"registerId\": \"" + api.register + "\", \"channel\": \"RETAIL\", \"lines\": ["
                        + eggs(api, "10", "") + "]}");
        assertThat(draft).hasStatus(201);
        assertThat(json(draft).path("status").asString()).isEqualTo("DRAFT");
        assertThat(json(draft).path("totals").path("grandTotal").path("amount").asString())
                .isEqualTo("550.00");

        MvcTestResult edited = api.patch(
                "/api/v1/bills/" + id,
                "{\"lines\": [" + eggs(api, "10", "") + ", " + api.line("OIL", "bottle-750", "1", "550.00", "") + "]}",
                ApiClient.etag(draft));
        JsonNode bill = json(edited);
        assertThat(bill.path("totals").path("grandTotal").path("amount").asString())
                .isEqualTo("1100.00");
        assertThat(bill.path("totals").path("taxTotal").path("amount").asString())
                .as("18 % VAT inside the oil's 550.00")
                .isEqualTo("83.90");
        MvcTestResult completed = api.post(
                "/api/v1/bills/" + id + "/complete",
                "{\"tenders\": [" + cash("1100.00", "2000.00") + "], \"totals\": "
                        + totals("1100.00", "0.00", "83.90", "1100.00") + "}");

        assertThat(completed).hasStatusOk();
        assertThat(json(completed).path("status").asString()).isEqualTo("COMPLETED");
        assertThat(json(completed)
                        .path("tenders")
                        .get(0)
                        .path("change")
                        .path("amount")
                        .asString())
                .isEqualTo("900.00");
        assertThat(json(completed).path("businessDate").asString()).isEqualTo("2026-11-25");
        assertThat(json(completed).path("shiftId").asString()).isEqualTo(api.shift.toString());
        assertThat(events.stream(SaleCompleted.class).filter(e -> e.billId().equals(id)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.lines()).extracting(l -> l.stockState()).containsExactly("DEFAULT", "OPEN");
                    assertThat(e.lines().get(1).qtyBase()).isEqualByComparingTo("750");
                    assertThat(e.totals().grandTotal()).isEqualByComparingTo("1100.00");
                    assertThat(e.tendered("CASH")).isEqualByComparingTo("1100.00");
                    assertThat(e.offline()).isFalse();
                });
        assertThat(json(api.get("/api/v1/registers/" + api.register))
                        .path("lastNumber")
                        .asLong())
                .isEqualTo(1);
        assertThatProblem(api.post(
                        "/api/v1/bills/" + id + "/complete",
                        "{\"tenders\": [" + cash("1100.00", "2000.00") + "], \"totals\": "
                                + totals("1100.00", "0.00", "83.90", "1100.00") + "}"))
                .hasStatus(409)
                .hasCode("BILL_STATE_INVALID");
    }

    @Test
    void sal01TheSameBillAgainReplaysAndANumberBelongsToOneBill() {
        SalesApi api = api("Replay");
        UUID id = UUID.randomUUID();
        String body = api.completed(
                id,
                "R1-000007",
                "",
                List.of(eggs(api, "2", "")),
                List.of(cash("110.00", "200.00")),
                totals("110.00", "0.00", "0.00", "110.00"));

        assertThat(api.submit(body)).hasStatus(201);
        MvcTestResult again = api.submit(body);
        assertThat(again).as("a till resending its bill").hasStatus(201);
        assertThat(json(again).path("id").asString()).isEqualTo(id.toString());
        assertThat(events.stream(SaleCompleted.class).filter(e -> e.billId().equals(id)))
                .hasSize(1);
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        "R1-000007",
                        "",
                        List.of(eggs(api, "2", "")),
                        List.of(cash("110.00", "200.00")),
                        totals("110.00", "0.00", "0.00", "110.00"))))
                .hasStatus(409)
                .hasCode("BILL_NUMBER_CONFLICT");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        "R9-000008",
                        "",
                        List.of(eggs(api, "2", "")),
                        List.of(cash("110.00", "200.00")),
                        totals("110.00", "0.00", "0.00", "110.00"))))
                .as("another register's prefix")
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
    }

    @Test
    void sal03Sal04OnlinePricesAndTotalsMustMatchUnlessAManagerOverrides() {
        SalesApi api = api("Prices");
        UUID manager = approvers.add("sales:*");
        UUID clerk = approvers.add("sales:read");

        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(api.line("EGG-RED-L", "piece", "2", "54.00", "")),
                        List.of(cash("108.00", "108.00")),
                        totals("108.00", "0.00", "0.00", "108.00"))))
                .hasStatus(422)
                .hasCode("PRICE_MISMATCH");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(eggs(api, "2", "")),
                        List.of(cash("110.01", "200.00")),
                        totals("110.00", "0.00", "0.00", "110.01"))))
                .hasStatus(422)
                .hasCode("TOTALS_MISMATCH");
        String override =
                ", \"priceOverride\": {\"unitPrice\": \"50.00\", \"approvedBy\": \"%s\", \"reason\": \"Cracked\"}";
        MvcTestResult overridden = api.submit(api.completed(
                UUID.randomUUID(),
                api.nextNumber(),
                "",
                List.of(api.line("EGG-RED-L", "piece", "2", null, override.formatted(manager))),
                List.of(cash("100.00", "100.00")),
                totals("100.00", "0.00", "0.00", "100.00")));
        assertThat(overridden).hasStatus(201);
        assertThat(json(overridden).path("lines").get(0).path("priceSource").asString())
                .isEqualTo("OVERRIDE");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(api.line("EGG-RED-L", "piece", "2", null, override.formatted(clerk))),
                        List.of(cash("100.00", "100.00")),
                        totals("100.00", "0.00", "0.00", "100.00"))))
                .hasStatus(403)
                .hasCode("APPROVER_NOT_ALLOWED");
    }

    @Test
    void sal05DiscountsAboveTheCashierLimitNeedAManager() {
        SalesApi api = api("Discounts");
        UUID manager = approvers.add("sales:discount-unlimited");
        String tenPercent = ", \"lineDiscount\": \"55.00\"";

        assertThatProblem(api.post(
                        "/api/v1/bills",
                        api.cashier,
                        api.completed(
                                UUID.randomUUID(),
                                api.nextNumber(),
                                "",
                                List.of(eggs(api, "10", tenPercent)),
                                List.of(cash("495.00", "500.00")),
                                totals("550.00", "55.00", "0.00", "495.00"))))
                .hasStatus(403)
                .hasCode("DISCOUNT_LIMIT_EXCEEDED");
        assertThat(api.post(
                        "/api/v1/bills",
                        api.cashier,
                        api.completed(
                                UUID.randomUUID(),
                                api.nextNumber(),
                                "",
                                List.of(eggs(api, "10", tenPercent + ", \"discountApprovedBy\": \"" + manager + "\"")),
                                List.of(cash("495.00", "500.00")),
                                totals("550.00", "55.00", "0.00", "495.00"))))
                .hasStatus(201);
        MvcTestResult billDiscount = api.post(
                "/api/v1/bills",
                api.cashier,
                api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        ", \"billDiscount\": {\"type\": \"PERCENT\", \"value\": \"5\", \"reason\": \"Regular\"}",
                        List.of(eggs(api, "10", "")),
                        List.of(cash("522.50", "600.00")),
                        totals("550.00", "27.50", "0.00", "522.50")));
        assertThat(billDiscount).as("exactly the 5 % limit").hasStatus(201);
        assertThat(json(billDiscount)
                        .path("lines")
                        .get(0)
                        .path("allocatedBillDiscount")
                        .path("amount")
                        .asString())
                .isEqualTo("27.50");
    }

    @Test
    void sal06Sal07TendersAddUpAndCreditStaysWithinTheLimit() {
        SalesApi api = api("Tenders");
        UUID customer = api.creditCustomer("Hotel Lake View", "1000.00");
        String withCustomer = ", \"customerId\": \"" + customer + "\"";
        String two = eggs(api, "10", "");
        String total = totals("550.00", "0.00", "0.00", "550.00");

        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        withCustomer,
                        List.of(two),
                        List.of(tender("CREDIT", "550.00", null)),
                        total)))
                .as("the tenant does not allow credit sales yet")
                .hasStatus(422)
                .hasCode("CREDIT_NOT_ALLOWED");
        api.settings("sales", "{\"allowCreditSales\": true}");
        assertThat(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        withCustomer,
                        List.of(two),
                        List.of(tender("CREDIT", "550.00", null)),
                        total)))
                .hasStatus(201);
        assertThatProblem(api.post(
                        "/api/v1/bills",
                        api.cashier,
                        api.completed(
                                UUID.randomUUID(),
                                api.nextNumber(),
                                withCustomer,
                                List.of(two),
                                List.of(tender("CREDIT", "550.00", null)),
                                total)))
                .as("550 owed, 550 more is above 1,000; a cashier may not override")
                .hasStatus(409)
                .hasCode("CREDIT_LIMIT_EXCEEDED");
        UUID manager = approvers.add("sales:credit-override");
        assertThat(api.post(
                        "/api/v1/bills",
                        api.cashier,
                        api.completed(
                                UUID.randomUUID(),
                                api.nextNumber(),
                                withCustomer + ", \"creditApprovedBy\": \"" + manager + "\"",
                                List.of(two),
                                List.of(tender("CREDIT", "550.00", null)),
                                total)))
                .hasStatus(201);
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(two),
                        List.of(tender("CREDIT", "550.00", null)),
                        total)))
                .hasStatus(422)
                .hasCode("CREDIT_NOT_ALLOWED");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(two),
                        List.of(tender("CARD", "550.00", null)),
                        total)))
                .hasStatus(422)
                .hasCode("TENDER_INVALID");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(two),
                        List.of(tender("CARD", "550.00", "4111111111111111")),
                        total)))
                .hasStatus(422)
                .hasCode("CARD_DATA_NOT_ALLOWED");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(two),
                        List.of(tender("CARD", "500.00", "APPR 1"), cash("40.00", "40.00")),
                        total)))
                .hasStatus(422)
                .hasCode("TENDER_MISMATCH");
        assertThat(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(two),
                        List.of(tender("CARD", "500.00", "APPR 1"), cash("50.00", "100.00")),
                        total)))
                .as("split card and cash")
                .hasStatus(201);
    }

    @Test
    void sal09Sal10AVoidNeedsAManagerAndAReturnIssuesACreditNote() {
        SalesApi api = api("Voids");
        UUID manager = approvers.add("sales:void");
        UUID voided = UUID.randomUUID();
        api.submit(api.completed(
                voided,
                api.nextNumber(),
                "",
                List.of(eggs(api, "2", "")),
                List.of(cash("110.00", "110.00")),
                totals("110.00", "0.00", "0.00", "110.00")));

        assertThatProblem(api.post("/api/v1/bills/" + voided + "/void", "{\"reason\": \"Wrong customer\"}"))
                .hasStatus(422)
                .hasCode("APPROVAL_REQUIRED");
        MvcTestResult done = api.post(
                "/api/v1/bills/" + voided + "/void",
                "{\"reason\": \"Wrong customer\", \"approvedBy\": \"" + manager + "\"}");
        assertThat(json(done).path("status").asString()).isEqualTo("VOIDED");
        assertThat(events.stream(SaleVoided.class).filter(e -> e.billId().equals(voided)))
                .singleElement()
                .satisfies(e -> assertThat(e.approvedBy()).isEqualTo(manager));

        UUID sold = UUID.randomUUID();
        JsonNode bill = json(api.submit(api.completed(
                sold,
                api.nextNumber(),
                "",
                List.of(eggs(api, "10", "")),
                List.of(cash("550.00", "1000.00")),
                totals("550.00", "0.00", "0.00", "550.00"))));
        String line = bill.path("lines").get(0).path("id").asString();
        UUID note = UUID.randomUUID();
        MvcTestResult returned = api.post(
                "/api/v1/bills/" + sold + "/returns",
                "{\"id\": \"" + note
                        + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \"" + line
                        + "\", \"qty\": \"2\", \"restock\": true}]}");

        assertThat(returned).hasStatus(201);
        assertThat(json(returned).path("number").asString()).isEqualTo("CN-000001");
        assertThat(json(returned).path("total").path("amount").asString()).isEqualTo("110.00");
        assertThat(json(returned).path("refundTenders").get(0).path("method").asString())
                .isEqualTo("CASH");
        assertThat(events.stream(SaleReturned.class).filter(e -> e.billId().equals(sold)))
                .singleElement()
                .satisfies(e -> assertThat(e.lines().getFirst().restock()).isTrue());
        assertThat(json(api.get("/api/v1/bills/" + sold)).path("status").asString())
                .isEqualTo("PARTIALLY_RETURNED");
        assertThatProblem(api.post(
                        "/api/v1/bills/" + sold + "/returns",
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"reason\": \"More\", \"lines\": [{\"billLineId\": \"" + line
                                + "\", \"qty\": \"9\", \"restock\": false}]}"))
                .hasStatus(409)
                .hasCode("RETURN_QTY_EXCEEDED");
        assertThatProblem(api.post(
                        "/api/v1/bills/" + sold + "/void",
                        "{\"reason\": \"Late\", \"approvedBy\": \"" + manager + "\"}"))
                .hasStatus(409)
                .hasCode("BILL_STATE_INVALID");
        assertThat(json(api.get("/api/v1/credit-notes?billId=" + sold)).path("items"))
                .hasSize(1);
        assertThat(json(api.get("/api/v1/credit-notes/" + note)).path("lines")).hasSize(1);
        assertThatProblem(api.get("/api/v1/credit-notes/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("CREDIT_NOTE_NOT_FOUND");
    }

    @Test
    void sal12Sal14Sal15ShiftsChannelsAndWeighedLines() {
        SalesApi api = api("Shifts");
        assertThatProblem(api.post("/api/v1/shifts", "{\"registerId\": \"" + api.register + "\"}"))
                .hasStatus(409)
                .hasCode("SHIFT_ALREADY_OPEN");
        MvcTestResult weighed = api.submit(api.completed(
                UUID.randomUUID(),
                api.nextNumber(),
                "",
                List.of(api.line("KAROLA", "kg", "0.350", "2400.00", ", \"weighed\": true")),
                List.of(cash("840.00", "1000.00")),
                totals("840.00", "0.00", "0.00", "840.00")));
        assertThat(weighed).hasStatus(201);
        assertThat(json(weighed).path("lines").get(0).path("qtyBase").asString())
                .isEqualTo("350");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(api.line("KAROLA", "kg", "0.3505", "2400.00", ", \"weighed\": true")),
                        List.of(cash("841.20", "1000.00")),
                        totals("841.20", "0.00", "0.00", "841.20"))))
                .hasStatus(422)
                .hasCode("WEIGHED_QTY_INVALID");

        MvcTestResult tray = api.submit(api.completed(
                "WHOLESALE",
                UUID.randomUUID(),
                api.nextNumber(),
                "",
                List.of(api.line("EGG-RED-L", "piece", "300", "50.00", "")),
                List.of(cash("14250.00", "15000.00")),
                totals("15000.00", "750.00", "0.00", "14250.00")));
        assertThat(tray).as("300 wholesale eggs get the 5 % break").hasStatus(201);
        assertThat(json(tray)
                        .path("lines")
                        .get(0)
                        .path("ruleDiscount")
                        .path("amount")
                        .asString())
                .isEqualTo("750.00");
        api.settings("sales", "{\"wholesaleRequiresCustomer\": true}");
        assertThatProblem(api.submit(api.completed(
                        "WHOLESALE",
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(api.line("EGG-RED-L", "piece", "1", "50.00", "")),
                        List.of(cash("50.00", "50.00")),
                        totals("50.00", "0.00", "0.00", "50.00"))))
                .hasStatus(422)
                .hasCode("CUSTOMER_REQUIRED");

        assertThat(api.post("/api/v1/shifts/" + api.shift + "/close", "{}")).hasStatusOk();
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(eggs(api, "1", "")),
                        List.of(cash("55.00", "55.00")),
                        totals("55.00", "0.00", "0.00", "55.00"))))
                .hasStatus(409)
                .hasCode("SHIFT_NOT_OPEN");
        assertThatProblem(api.post("/api/v1/shifts/" + api.shift + "/close", "{}"))
                .hasStatus(409)
                .hasCode("SHIFT_STATE_INVALID");
    }

    @Test
    void registersHaveUniqueCodesAndPrefixesAndChangeUnderTheirVersion() {
        SalesApi api = api("Registers");
        assertThatProblem(api.post(
                        "/api/v1/registers",
                        "{\"code\": \"R1\", \"locationId\": \"" + api.location + "\", \"billPrefix\": \"R7\"}"))
                .hasStatus(409)
                .hasCode("REGISTER_CODE_TAKEN");
        assertThatProblem(api.post(
                        "/api/v1/registers",
                        "{\"code\": \"R2\", \"locationId\": \"" + api.location + "\", \"billPrefix\": \"R1\"}"))
                .hasStatus(409)
                .hasCode("BILL_PREFIX_TAKEN");
        assertThatProblem(api.post(
                        "/api/v1/registers",
                        "{\"code\": \"R3\", \"locationId\": \"" + UUID.randomUUID() + "\", \"billPrefix\": \"R3\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
        String path = "/api/v1/registers/" + api.register;
        MvcTestResult renamed = api.patch(path, "{\"name\": \"Counter 1\"}", ApiClient.etag(api.get(path)));
        assertThat(json(renamed).path("name").asString()).isEqualTo("Counter 1");
        assertThat(json(api.get("/api/v1/registers?locationId=" + api.location)).path("items"))
                .hasSize(1);
        assertThatProblem(api.get("/api/v1/registers/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("REGISTER_NOT_FOUND");
        assertThat(json(api.get("/api/v1/bills?status=COMPLETED")).path("items"))
                .isEmpty();
        assertThatProblem(api.get("/api/v1/bills/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");
    }
}
