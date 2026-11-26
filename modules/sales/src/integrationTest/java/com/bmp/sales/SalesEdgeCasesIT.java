package com.bmp.sales;

import static com.bmp.sales.SalesApi.cash;
import static com.bmp.sales.SalesApi.json;
import static com.bmp.sales.SalesApi.tender;
import static com.bmp.sales.SalesApi.totals;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.bmp.sales.SalesTestApplication.Approvers;
import com.bmp.sales.api.SalesQueries;
import com.bmp.sales.api.dto.RegisterInfo;
import com.bmp.sales.application.LinePricer.LineInput;
import com.bmp.sales.application.LinePricer.PriceOverride;
import com.bmp.sales.application.command.BillHandlers;
import com.bmp.sales.application.command.BillHandlers.SubmitBill;
import com.bmp.sales.application.command.BillHandlers.TenderInput;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillDiscount;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.BillStatus;
import com.bmp.sales.domain.model.DiscountType;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.RegisterId;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * The paths around the main flows: register changes, shift errors, draft versions, unknown lines, windows for voids
 * and returns, explicit refunds, paging, and the offline branches the sync module will use.
 */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SalesEdgeCasesIT {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    BillHandlers bills;

    @Autowired
    SalesQueries queries;

    @Autowired
    Approvers approvers;

    private SalesApi api(String name) {
        return new SalesApi(mvc, jdbc, transactions, name);
    }

    private static String eggs(SalesApi api, String qty) {
        return api.line("EGG-RED-L", "piece", qty, "55.00", "");
    }

    private static String twoEggs(SalesApi api, UUID id) {
        return api.completed(
                id,
                api.nextNumber(),
                "",
                List.of(eggs(api, "2")),
                List.of(cash("110.00", "110.00")),
                totals("110.00", "0.00", "0.00", "110.00"));
    }

    private static <T> T in(SalesApi api, Supplier<T> action) {
        return Objects.requireNonNull(TenantContext.callWith(api.tenant, action::get));
    }

    private static LineInput eggLine(SalesApi api, String qty, String price, @Nullable PriceOverride override) {
        return new LineInput(
                UUID.randomUUID(),
                UUID.fromString(api.variant("EGG-RED-L").path("id").asString()),
                UUID.fromString(api.unit("EGG-RED-L", "piece")),
                new BigDecimal(qty),
                false,
                new BigDecimal(price),
                null,
                null,
                null,
                override);
    }

    private static SubmitBill offline(
            SalesApi api, List<LineInput> lines, @Nullable BillDiscount discount, String grand, @Nullable Instant at) {
        BigDecimal total = new BigDecimal(grand);
        return new SubmitBill(
                UUID.randomUUID(),
                api.nextNumber(),
                api.register,
                UUID.randomUUID(),
                SalesChannel.RETAIL,
                null,
                lines,
                discount,
                true,
                List.of(new TenderInput(UUID.randomUUID(), TenderMethod.CASH, total, total, null)),
                new Totals(
                        lines.stream()
                                .map(l -> l.qty().multiply(Objects.requireNonNull(l.unitPrice())))
                                .reduce(BigDecimal.ZERO, BigDecimal::add),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        total),
                at,
                null,
                null,
                null);
    }

    private Bill submitOffline(SalesApi api, SubmitBill bill) {
        return in(api, () -> bills.submit(bill, Mode.OFFLINE)).bill();
    }

    @Test
    void registersChangeUnderTheirVersionAndAnInactiveRegisterTakesNoShiftOrBill() {
        SalesApi api = api("Registers");
        MvcTestResult second = api.post(
                "/api/v1/registers",
                "{\"code\": \"R2\", \"name\": \"Back counter\", \"locationId\": \"" + api.location
                        + "\", \"billPrefix\": \"R2\"}");
        String path = "/api/v1/registers/" + SalesApi.id(second);

        assertThatProblem(api.patch(path, "{\"name\": \"Old\"}", "\"99\"")).hasStatus(412);
        MvcTestResult changed = api.patch(
                path,
                "{\"name\": \"Wholesale counter\", \"deviceId\": \"" + UUID.randomUUID()
                        + "\", \"billPrefix\": \"W1\", \"status\": \"INACTIVE\"}",
                ApiClient.etag(api.get(path)));
        assertThat(changed).hasStatusOk();
        assertThat(json(changed).path("billPrefix").asString()).isEqualTo("W1");
        assertThat(json(changed).path("status").asString()).isEqualTo("INACTIVE");
        assertThatProblem(api.patch("/api/v1/registers/" + UUID.randomUUID(), "{\"name\": \"X\"}", "\"0\""))
                .hasStatus(404)
                .hasCode("REGISTER_NOT_FOUND");
        assertThatProblem(api.post("/api/v1/shifts", "{\"registerId\": \"" + SalesApi.id(second) + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_REGISTER");
        assertThatProblem(api.submit(twoEggs(api, UUID.randomUUID())
                        .replace(api.register.toString(), SalesApi.id(second).toString())))
                .hasStatus(422)
                .hasCode("UNKNOWN_REGISTER");

        JsonNode first = json(api.get("/api/v1/registers?limit=1&locationId=" + api.location));
        assertThat(first.path("page").path("hasMore").asBoolean()).isTrue();
        JsonNode next = json(api.get("/api/v1/registers?limit=1&cursor="
                + first.path("page").path("nextCursor").asString()));
        assertThat(next.path("items").get(0).path("code").asString()).isEqualTo("R2");
        assertThatProblem(api.get("/api/v1/registers?cursor=!!")).hasStatus(400);
        Optional<RegisterInfo> known = in(api, () -> queries.findRegister(RegisterId.of(api.register)));
        assertThat(known).hasValueSatisfying(r -> assertThat(r.billPrefix()).isEqualTo("R1"));
        Optional<RegisterInfo> unknown = in(api, () -> queries.findRegister(RegisterId.of(UUID.randomUUID())));
        assertThat(unknown).isEmpty();
    }

    @Test
    void sal12AShiftOpensOncePerRegisterClosesOnceAndOnlineBillsNeedTheOpenOne() {
        SalesApi api = api("Shifts");
        assertThatProblem(api.post("/api/v1/shifts", "{\"registerId\": \"" + api.register + "\"}"))
                .hasStatus(409)
                .hasCode("SHIFT_ALREADY_OPEN");
        assertThatProblem(api.submit(api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        ", \"shiftId\": \"" + UUID.randomUUID() + "\"",
                        List.of(eggs(api, "2")),
                        List.of(cash("110.00", "110.00")),
                        totals("110.00", "0.00", "0.00", "110.00"))))
                .as("an online bill names the open shift or none")
                .hasStatus(409)
                .hasCode("SHIFT_NOT_OPEN");

        assertThat(api.post("/api/v1/shifts/" + api.shift + "/close", "{\"closedAt\": \"2026-11-25T08:00:00Z\"}"))
                .hasStatusOk();
        assertThatProblem(api.post("/api/v1/shifts/" + api.shift + "/close", "{}"))
                .hasStatus(409)
                .hasCode("SHIFT_STATE_INVALID");
        assertThatProblem(api.post("/api/v1/shifts/" + UUID.randomUUID() + "/close", "{}"))
                .hasStatus(404)
                .hasCode("SHIFT_NOT_FOUND");
        assertThatProblem(api.submit(twoEggs(api, UUID.randomUUID())))
                .hasStatus(409)
                .hasCode("SHIFT_NOT_OPEN");
        UUID next = UUID.randomUUID();
        String open = "{\"id\": \"" + next + "\", \"registerId\": \"" + api.register
                + "\", \"openedAt\": \"2026-11-25T08:30:00Z\"}";
        assertThat(SalesApi.id(api.post("/api/v1/shifts", api.cashier, open))).isEqualTo(next);
        assertThat(SalesApi.id(api.post("/api/v1/shifts", api.cashier, open)))
                .as("a till retrying the same shift")
                .isEqualTo(next);
    }

    @Test
    void sal02DraftsChangeOnlyWhileDraftsUnderTheirVersion() {
        SalesApi api = api("Drafts");
        api.settings("sales", "{\"allowCreditSales\": true}");
        UUID customer = api.creditCustomer("Hotel Lake View", "5000.00");
        UUID id = UUID.randomUUID();
        MvcTestResult draft = api.submit("{\"id\": \"" + id + "\", \"number\": \"" + api.nextNumber()
                + "\", \"registerId\": \"" + api.register + "\", \"channel\": \"RETAIL\", \"lines\": ["
                + eggs(api, "10") + "]}");
        String path = "/api/v1/bills/" + id;

        assertThatProblem(api.patch(path, "{\"channel\": \"WHOLESALE\"}", "\"99\""))
                .hasStatus(412);
        MvcTestResult wholesale = api.patch(
                path,
                "{\"channel\": \"WHOLESALE\", \"customerId\": \"" + customer
                        + "\", \"billDiscount\": {\"type\": \"AMOUNT\", \"value\": \"10.00\"}}",
                ApiClient.etag(draft));
        assertThat(wholesale).hasStatusOk();
        assertThat(json(wholesale)
                        .path("lines")
                        .get(0)
                        .path("unitPrice")
                        .path("amount")
                        .asString())
                .as("the lines were priced again from the wholesale list")
                .isEqualTo("50.00");
        assertThat(json(wholesale)
                        .path("totals")
                        .path("grandTotal")
                        .path("amount")
                        .asString())
                .isEqualTo("490.00");
        MvcTestResult done = api.post(
                path + "/complete",
                "{\"tenders\": [" + tender("CREDIT", "490.00", null) + "], \"totals\": "
                        + totals("500.00", "10.00", "0.00", "490.00") + "}");
        assertThat(json(done).path("status").asString()).isEqualTo("COMPLETED");
        assertThatProblem(api.patch(path, "{\"channel\": \"RETAIL\"}", ApiClient.etag(done)))
                .hasStatus(409)
                .hasCode("BILL_STATE_INVALID");
        assertThatProblem(api.post(
                        path + "/complete",
                        "{\"tenders\": [" + cash("490.00", "490.00") + "], \"totals\": "
                                + totals("500.00", "10.00", "0.00", "490.00") + "}"))
                .hasStatus(409)
                .hasCode("BILL_STATE_INVALID");
        String unknown = "/api/v1/bills/" + UUID.randomUUID();
        assertThatProblem(api.patch(unknown, "{\"channel\": \"RETAIL\"}", "\"0\""))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");
        assertThatProblem(api.post(
                        unknown + "/complete",
                        "{\"tenders\": [" + cash("1.00", "1.00") + "], \"totals\": "
                                + totals("1.00", "0.00", "0.00", "1.00") + "}"))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");
    }

    @Test
    void linesNeedAKnownActiveVariantAUnitOfItAndAValidWeight() {
        SalesApi api = api("Lines");
        String egg = api.variant("EGG-RED-L").path("id").asString();
        String unknownVariant = api.line("EGG-RED-L", "piece", "1", "55.00", "")
                .replace(egg, UUID.randomUUID().toString());
        String unknownUnit = api.line("EGG-RED-L", "piece", "1", "55.00", "")
                .replace(api.unit("EGG-RED-L", "piece"), UUID.randomUUID().toString());

        assertThatProblem(api.submit(draft(api, unknownVariant))).hasStatus(422).hasCode("UNKNOWN_VARIANT");
        assertThatProblem(api.submit(draft(api, unknownUnit))).hasStatus(422).hasCode("UNKNOWN_PACKAGING_UNIT");
        assertThatProblem(api.submit(draft(api, api.line("EGG-RED-L", "piece", "1", "55.00", ", \"weighed\": true"))))
                .as("eggs are not weighed")
                .hasStatus(422)
                .hasCode("WEIGHED_QTY_INVALID");
        assertThatProblem(api.submit(draft(api, eggs(api, "1"))
                        .replace(
                                "\"channel\": \"RETAIL\"",
                                "\"channel\": \"RETAIL\", \"customerId\": \"" + UUID.randomUUID() + "\"")))
                .hasStatus(422)
                .hasCode("UNKNOWN_CUSTOMER");
        assertThatProblem(api.submit(draft(api, eggs(api, "1")).replace("\"R1-", "\"R9-")))
                .as("the number carries the register's prefix")
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");

        assertThat(api.client.post("/api/v1/variants/" + egg + "/deactivate", api.owner, "{}"))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThatProblem(api.submit(draft(api, eggs(api, "1"))))
                        .as("a deactivated variant is not sold")
                        .hasStatus(422)
                        .hasCode("UNKNOWN_VARIANT"));
    }

    private static String refund(String method, String amount, @Nullable String reference) {
        return "{\"method\": \"" + method + "\", \"amount\": \"" + amount + "\""
                + (reference == null ? "" : ", \"reference\": \"" + reference + "\"") + "}";
    }

    private static String draft(SalesApi api, String line) {
        return "{\"id\": \"" + UUID.randomUUID() + "\", \"number\": \"" + api.nextNumber() + "\", \"registerId\": \""
                + api.register + "\", \"channel\": \"RETAIL\", \"lines\": [" + line + "]}";
    }

    @Test
    void sal03AnApprovedOverrideSetsThePriceOnlineAndAnUnapprovedOneIsFlaggedOffline() {
        SalesApi api = api("Overrides");
        UUID manager = approvers.add("sales:price-override");
        UUID nobody = approvers.add("sales:read");
        String override = ", \"priceOverride\": {\"unitPrice\": \"50.00\", \"reason\": \"Regular\", \"approvedBy\": \""
                + manager + "\"}";

        JsonNode bill = json(api.post(
                "/api/v1/bills",
                api.cashier,
                api.completed(
                        UUID.randomUUID(),
                        api.nextNumber(),
                        "",
                        List.of(api.line("EGG-RED-L", "piece", "2", null, override)),
                        List.of(cash("100.00", "100.00")),
                        totals("100.00", "0.00", "0.00", "100.00"))));
        assertThat(bill.path("lines").get(0).path("priceSource").asString()).isEqualTo("OVERRIDE");
        assertThatProblem(api.post(
                        "/api/v1/bills",
                        api.cashier,
                        api.completed(
                                UUID.randomUUID(),
                                api.nextNumber(),
                                "",
                                List.of(api.line(
                                        "EGG-RED-L",
                                        "piece",
                                        "2",
                                        null,
                                        override.replace(manager.toString(), nobody.toString()))),
                                List.of(cash("100.00", "100.00")),
                                totals("100.00", "0.00", "0.00", "100.00"))))
                .as("the named approver lacks the permission")
                .hasStatus(403)
                .hasCode("APPROVER_NOT_ALLOWED");

        Bill flagged = submitOffline(
                api,
                offline(
                        api,
                        List.of(eggLine(
                                api, "2", "50.00", new PriceOverride(new BigDecimal("50.00"), nobody, "Regular"))),
                        null,
                        "100.00",
                        NOW));
        assertThat(flagged.flags()).containsExactly(BillFlag.PRICE_MISMATCH);
        assertThat(flagged.lines().getFirst().unitPrice()).isEqualByComparingTo("50.00");
    }

    @Test
    void offlineBillsArriveCompletedAndAreFlaggedForLateDiscountsAndCreditTheTenantRefuses() {
        SalesApi api = api("Offline flags");
        UUID nobody = approvers.add("sales:read");
        SubmitBill draft = new SubmitBill(
                UUID.randomUUID(),
                api.nextNumber(),
                api.register,
                null,
                SalesChannel.RETAIL,
                null,
                List.of(eggLine(api, "2", "55.00", null)),
                null,
                false,
                List.of(),
                null,
                null,
                null,
                null,
                null);
        assertThatThrownBy(() -> in(api, () -> bills.submit(draft, Mode.OFFLINE)))
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageContaining("completed");

        Bill late = submitOffline(
                api,
                offline(
                        api,
                        List.of(eggLine(api, "2", "55.00", null)),
                        null,
                        "110.00",
                        NOW.minus(Duration.ofHours(100))));
        assertThat(late.flags()).contains(BillFlag.LATE_SYNC);

        BillDiscount half = new BillDiscount(DiscountType.PERCENT, new BigDecimal("50"), "Friend", nobody);
        Bill discounted =
                submitOffline(api, offline(api, List.of(eggLine(api, "2", "55.00", null)), half, "55.00", NOW));
        assertThat(discounted.flags())
                .as("the named approver lacks the permission: accepted and flagged offline")
                .contains(BillFlag.DISCOUNT_LIMIT_EXCEEDED);

        BigDecimal total = new BigDecimal("110.00");
        UUID customer = api.creditCustomer("Hotel Lake View", "1000.00");
        SubmitBill credit = new SubmitBill(
                UUID.randomUUID(),
                api.nextNumber(),
                api.register,
                api.shift,
                SalesChannel.RETAIL,
                customer,
                List.of(eggLine(api, "2", "55.00", null)),
                null,
                true,
                List.of(new TenderInput(UUID.randomUUID(), TenderMethod.CREDIT, total, null, null)),
                new Totals(total, BigDecimal.ZERO, BigDecimal.ZERO, total),
                NOW,
                null,
                null,
                null);
        assertThat(submitOffline(api, credit).flags())
                .as("the tenant does not allow credit sales: accepted and flagged offline")
                .contains(BillFlag.CREDIT_LIMIT_EXCEEDED);
        Bill voided =
                in(api, () -> bills.voidBill(credit.id(), "Duplicate", approvers.add("sales:void"), NOW, Mode.OFFLINE));
        assertThat(voided.status()).isEqualTo(BillStatus.VOIDED);
        assertThat(in(api, () -> bills.voidBill(credit.id(), "Duplicate", null, NOW, Mode.OFFLINE))
                        .status())
                .as("a till resending its void")
                .isEqualTo(BillStatus.VOIDED);
    }

    @Test
    void sal09Sal10VoidsAndReturnsKeepTheirWindowsAndRefundsAddUp() {
        SalesApi api = api("Windows");
        UUID manager = approvers.add("sales:void", "sales:return-override");
        UUID clerk = approvers.add("sales:read");
        Bill yesterday = submitOffline(
                api,
                offline(
                        api,
                        List.of(eggLine(api, "10", "55.00", null)),
                        null,
                        "550.00",
                        NOW.minus(Duration.ofHours(24))));
        Bill lastMonth = submitOffline(
                api,
                offline(
                        api,
                        List.of(eggLine(api, "10", "55.00", null)),
                        null,
                        "550.00",
                        NOW.minus(Duration.ofDays(20))));
        String void_ = "{\"reason\": \"Wrong customer\", \"approvedBy\": \"%s\"}";

        assertThatProblem(api.post("/api/v1/bills/" + yesterday.id() + "/void", void_.formatted(manager)))
                .hasStatus(409)
                .hasCode("VOID_WINDOW_PASSED");
        assertThatProblem(api.post("/api/v1/bills/" + yesterday.id() + "/void", void_.formatted(clerk)))
                .hasStatus(403)
                .hasCode("APPROVER_NOT_ALLOWED");
        assertThatProblem(api.post("/api/v1/bills/" + UUID.randomUUID() + "/void", void_.formatted(manager)))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");

        String line = yesterday.lines().getFirst().id().toString();
        String back = "{\"id\": \"%s\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \"%s\", \"qty\": \"2\", "
                + "\"restock\": true}], \"refundTenders\": [%s]%s}";
        assertThatProblem(api.post(
                        "/api/v1/bills/" + yesterday.id() + "/returns",
                        back.formatted(UUID.randomUUID(), line, refund("CARD", "110.00", "4111 1111 1111 1111"), "")))
                .hasStatus(422)
                .hasCode("CARD_DATA_NOT_ALLOWED");
        assertThatProblem(api.post(
                        "/api/v1/bills/" + yesterday.id() + "/returns",
                        back.formatted(UUID.randomUUID(), line, refund("CASH", "100.00", null), "")))
                .hasStatus(422)
                .hasCode("REFUND_TENDER_MISMATCH");
        UUID note = UUID.randomUUID();
        String split = back.formatted(
                note, line, refund("CASH", "60.00", null) + ", " + refund("CARD", "50.00", "AUTH-1"), "");
        assertThat(api.post("/api/v1/bills/" + yesterday.id() + "/returns", split))
                .hasStatus(201);
        assertThat(json(api.post("/api/v1/bills/" + yesterday.id() + "/returns", split))
                        .path("refundTenders"))
                .as("the same credit note again replays")
                .hasSize(2);
        assertThatProblem(api.post(
                        "/api/v1/bills/" + UUID.randomUUID() + "/returns",
                        back.formatted(UUID.randomUUID(), line, refund("CASH", "110.00", null), "")))
                .hasStatus(404)
                .hasCode("BILL_NOT_FOUND");

        // No seeded role holds sales:return without sales:return-override, so the late return runs as a caller
        // without permissions: only a named approver can open the window.
        List<Bill.ReturnLine> stale =
                List.of(new Bill.ReturnLine(lastMonth.lines().getFirst().id(), BigDecimal.ONE, false));
        assertThatThrownBy(() -> in(
                        api,
                        () -> bills.returnBill(
                                lastMonth.id(),
                                new BillHandlers.ReturnBill(UUID.randomUUID(), "Stale", stale, List.of(), null, null),
                                Mode.ONLINE)))
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageStartingWith("RETURN_WINDOW_PASSED");
        assertThat(in(
                                api,
                                () -> bills.returnBill(
                                        lastMonth.id(),
                                        new BillHandlers.ReturnBill(
                                                UUID.randomUUID(), "Stale", stale, List.of(), manager, null),
                                        Mode.ONLINE))
                        .total())
                .isEqualByComparingTo("55.00");

        api.settings("sales", "{\"voidRequiresManager\": false}");
        UUID today = UUID.randomUUID();
        api.submit(twoEggs(api, today));
        assertThat(json(api.post("/api/v1/bills/" + today + "/void", "{\"reason\": \"Test\"}"))
                        .path("status")
                        .asString())
                .as("no manager needed when the tenant says so")
                .isEqualTo("VOIDED");
    }

    @Test
    void billsAndCreditNotesArePagedByCursor() {
        SalesApi api = api("Paging");
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        api.submit(twoEggs(api, first));
        api.submit(twoEggs(api, second));
        for (UUID bill : List.of(first, second)) {
            String line = json(api.get("/api/v1/bills/" + bill))
                    .path("lines")
                    .get(0)
                    .path("id")
                    .asString();
            api.post(
                    "/api/v1/bills/" + bill + "/returns",
                    "{\"id\": \"" + UUID.randomUUID() + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \""
                            + line + "\", \"qty\": \"1\", \"restock\": true}]}");
        }

        JsonNode page = json(api.get("/api/v1/bills?limit=1&status=PARTIALLY_RETURNED"));
        assertThat(page.path("page").path("hasMore").asBoolean()).isTrue();
        JsonNode rest = json(api.get("/api/v1/bills?limit=1&status=PARTIALLY_RETURNED&cursor="
                + page.path("page").path("nextCursor").asString()));
        assertThat(List.of(
                        page.path("items").get(0).path("id").asString(),
                        rest.path("items").get(0).path("id").asString()))
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        JsonNode notes = json(api.get("/api/v1/credit-notes?limit=1"));
        assertThat(json(api.get("/api/v1/credit-notes?limit=1&cursor="
                                + notes.path("page").path("nextCursor").asString()))
                        .path("items"))
                .hasSize(1);
        assertThatProblem(api.get("/api/v1/bills?cursor=bm90LWEtdXVpZA")).hasStatus(400);
        assertThatProblem(api.get("/api/v1/credit-notes?cursor=!!")).hasStatus(400);
        assertThat(json(api.get("/api/v1/bills?registerId=" + api.register + "&businessDate=2026-11-25"))
                        .path("items"))
                .hasSize(2);
    }
}
