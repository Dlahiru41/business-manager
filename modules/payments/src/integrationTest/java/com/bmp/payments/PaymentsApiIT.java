package com.bmp.payments;

import static com.bmp.payments.PaymentsShop.cash;
import static com.bmp.payments.PaymentsShop.json;
import static com.bmp.payments.PaymentsShop.tender;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.payments.PaymentsTestApplication.Approvers;
import com.bmp.payments.PaymentsTestApplication.PaymentEvents;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.payments.application.command.PaymentHandlers;
import com.bmp.shared.context.TenantContext;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
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

/** Payments of the pilot shop's bills, account collections, refunds, drawers and daily totals (PAY-01..PAY-06). */
@SpringBootTest(classes = PaymentsTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class PaymentsApiIT {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    @Autowired
    PaymentHandlers handlers;

    @Autowired
    Approvers approvers;

    @Autowired
    PaymentEvents recorded;

    private PaymentsShop shop(String name) {
        return new PaymentsShop(mvc, jdbc, transactions, name);
    }

    private static String expected(PaymentsShop shop, String session) {
        return json(shop.get("/api/v1/drawer-sessions/" + session + "/expected"))
                .path("expected")
                .path("amount")
                .asString();
    }

    private static UUID customer(PaymentsShop shop, String name) {
        MvcTestResult created = shop.client.post(
                "/api/v1/parties", shop.owner, "{\"type\": \"CUSTOMER\", \"displayName\": \"" + name + "\"}");
        assertThat(created).hasStatus(201);
        return com.bmp.test.web.ApiClient.id(created);
    }

    @Test
    void pay01EveryTenderButCreditIsAPaymentOnceAndCashLandsInTheDrawer() {
        PaymentsShop shop = shop("Bill payments");
        String session = shop.openDrawer(shop.cashier, "5000.00").path("id").asString();

        JsonNode cashBill = shop.bill("10", cash("550.00", "1000.00"));
        JsonNode mixed = shop.bill("2", cash("60.00", "100.00"), tender("CARD", "50.00", "AUTH-4471"));
        JsonNode cashPayment = shop.payments(cashBill.path("id").asString(), 1).get(0);
        JsonNode mixedPayments = shop.payments(mixed.path("id").asString(), 2);

        assertThat(cashPayment.path("kind").asString()).isEqualTo("BILL");
        assertThat(cashPayment.path("amount").path("amount").asString()).isEqualTo("550.00");
        assertThat(cashPayment.path("tendered").path("amount").asString()).isEqualTo("1000.00");
        assertThat(cashPayment.path("changeGiven").path("amount").asString()).isEqualTo("450.00");
        assertThat(cashPayment.path("drawerSessionId").asString()).isEqualTo(session);
        assertThat(mixedPayments)
                .extracting(p -> p.path("method").asString())
                .containsExactlyInAnyOrder("CASH", "CARD");
        assertThat(expected(shop, session)).as("5,000 float + 550 + 60 cash").isEqualTo("5610.00");

        JsonNode tenderOf = mixed.path("tenders").get(0);
        List<?> again = Objects.requireNonNull(TenantContext.callWith(
                shop.tenant,
                () -> handlers.captureSale(new PaymentHandlers.Sale(
                        UUID.randomUUID(),
                        UUID.fromString(mixed.path("id").asString()),
                        null,
                        shop.register,
                        null,
                        TODAY,
                        Instant.parse("2026-11-25T09:00:00Z"),
                        "LKR",
                        List.of(new PaymentHandlers.SaleTender(
                                UUID.fromString(tenderOf.path("id").asString()),
                                tenderOf.path("method").asString(),
                                new BigDecimal(
                                        tenderOf.path("amount").path("amount").asString()),
                                null,
                                null,
                                null))))));
        assertThat(again).as("a tender seen before is not paid twice").isEmpty();
        assertThat(recorded.of(PaymentCaptured.class, shop.tenant)).hasSize(3);
    }

    @Test
    void pay02ACustomerPaysOnAccountWithCashIntoAnOpenDrawerOrByCard() {
        PaymentsShop shop = shop("Collections");
        UUID customer = customer(shop, "Hotel Lake View");
        String session = shop.openDrawer(shop.cashier, "1000.00").path("id").asString();
        UUID id = UUID.randomUUID();
        String collection = "{\"id\": \"%s\", \"customerId\": \"%s\", \"method\": \"%s\", \"amount\": \"%s\"%s}";

        MvcTestResult paid = shop.post(
                "/api/v1/payments",
                shop.cashier,
                collection.formatted(
                        id,
                        customer,
                        "CASH",
                        "2500.00",
                        ", \"tendered\": \"3000.00\", \"registerId\": \"" + shop.register + "\""));
        assertThat(paid).hasStatus(201);
        assertThat(json(paid).path("kind").asString()).isEqualTo("ACCOUNT");
        assertThat(json(paid).path("changeGiven").path("amount").asString()).isEqualTo("500.00");
        assertThat(expected(shop, session)).isEqualTo("3500.00");
        assertThat(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                id,
                                customer,
                                "CASH",
                                "2500.00",
                                ", \"tendered\": \"3000.00\", \"registerId\": \"" + shop.register + "\"")))
                .as("the same payment again")
                .hasStatus(201);
        assertThat(expected(shop, session)).as("not twice").isEqualTo("3500.00");

        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                UUID.randomUUID(), UUID.randomUUID(), "CARD", "10.00", ", \"reference\": \"A\"")))
                .hasStatus(422)
                .hasCode("UNKNOWN_CUSTOMER");
        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(UUID.randomUUID(), customer, "CARD", "10.00", "")))
                .hasStatus(422)
                .hasCode("REFERENCE_REQUIRED");
        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                UUID.randomUUID(), customer, "CARD", "10.00", ", \"reference\": \"4111111111111111\"")))
                .hasStatus(422)
                .hasCode("CARD_DATA_NOT_ALLOWED");
        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                UUID.randomUUID(),
                                customer,
                                "CASH",
                                "10.00",
                                ", \"tendered\": \"5.00\", \"registerId\": \"" + shop.register + "\"")))
                .hasStatus(422)
                .hasCode("TENDERED_TOO_LOW");
        UUID back = shop.register("R2");
        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                UUID.randomUUID(), customer, "CASH", "10.00", ", \"registerId\": \"" + back + "\"")))
                .hasStatus(409)
                .hasCode("DRAWER_NOT_OPEN");
        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                UUID.randomUUID(),
                                customer,
                                "CASH",
                                "10.00",
                                ", \"registerId\": \"" + UUID.randomUUID() + "\"")))
                .hasStatus(422)
                .hasCode("UNKNOWN_REGISTER");
        assertThat(shop.post(
                        "/api/v1/payments",
                        shop.cashier,
                        collection.formatted(
                                UUID.randomUUID(),
                                customer,
                                "BANK_TRANSFER",
                                "4000.00",
                                ", \"reference\": \"SLIP-77\"")))
                .hasStatus(201);
    }

    @Test
    void pay03RefundsNeverExceedThePaymentAndFollowReturnsAndVoids() {
        PaymentsShop shop = shop("Refunds");
        String session = shop.openDrawer(shop.cashier, "5000.00").path("id").asString();
        JsonNode cardBill = shop.bill("2", tender("CARD", "110.00", "AUTH-1"));
        String card = shop.payments(cardBill.path("id").asString(), 1)
                .get(0)
                .path("id")
                .asString();
        String refund = "{\"id\": \"%s\", \"amount\": \"%s\", \"reason\": \"%s\"%s}";

        UUID first = UUID.randomUUID();
        assertThat(shop.post("/api/v1/payments/" + card + "/refunds", refund.formatted(first, "80.00", "Cracked", "")))
                .hasStatus(201);
        assertThat(shop.post("/api/v1/payments/" + card + "/refunds", refund.formatted(first, "80.00", "Cracked", "")))
                .as("the same refund again")
                .hasStatus(201);
        assertThatProblem(shop.post(
                        "/api/v1/payments/" + card + "/refunds",
                        refund.formatted(UUID.randomUUID(), "40.00", "More", "")))
                .hasStatus(409)
                .hasCode("REFUND_EXCEEDS_PAYMENT");
        assertThat(json(shop.get("/api/v1/payments/" + card))
                        .path("refunded")
                        .path("amount")
                        .asString())
                .isEqualTo("80.00");
        assertThatProblem(shop.post(
                        "/api/v1/payments/" + card + "/refunds",
                        refund.formatted(UUID.randomUUID(), "10.00", "4111 1111 1111 1111", "")))
                .hasStatus(422)
                .hasCode("CARD_DATA_NOT_ALLOWED");
        assertThatProblem(shop.post(
                        "/api/v1/payments/" + UUID.randomUUID() + "/refunds",
                        refund.formatted(UUID.randomUUID(), "1.00", "None", "")))
                .hasStatus(404)
                .hasCode("PAYMENT_NOT_FOUND");
        assertThat(shop.post(
                        "/api/v1/payments/" + card + "/refunds",
                        refund.formatted(UUID.randomUUID(), "30.00", "Cash back", ", \"method\": \"CASH\"")))
                .as("a card payment refunded in cash leaves the open drawer")
                .hasStatus(201);
        assertThat(expected(shop, session)).isEqualTo("4970.00");

        JsonNode cashBill = shop.bill("10", cash("550.00", "550.00"));
        shop.payments(cashBill.path("id").asString(), 1);
        assertThat(shop.client.post(
                        "/api/v1/bills/" + cashBill.path("id").asString() + "/returns",
                        shop.owner,
                        "{\"id\": \"" + UUID.randomUUID()
                                + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \""
                                + cashBill.path("lines").get(0).path("id").asString()
                                + "\", \"qty\": \"2\", \"restock\": true}]}"))
                .hasStatus(201);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(expected(shop, session))
                        .as("550 in, 110 back for the return")
                        .isEqualTo("5410.00"));

        UUID manager = approvers.add("sales:void");
        JsonNode mixed = shop.bill("4", cash("120.00", "120.00"), tender("CARD", "100.00", "AUTH-2"));
        shop.payments(mixed.path("id").asString(), 2);
        assertThat(shop.client.post(
                        "/api/v1/bills/" + mixed.path("id").asString() + "/void",
                        shop.owner,
                        "{\"reason\": \"Wrong customer\", \"approvedBy\": \"" + manager + "\"}"))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(shop.payments(mixed.path("id").asString(), 2))
                        .allSatisfy(p -> assertThat(
                                        p.path("refunded").path("amount").asString())
                                .isEqualTo(p.path("amount").path("amount").asString())));
        assertThat(expected(shop, session)).as("the void's cash came back out").isEqualTo("5410.00");
        assertThat(recorded.of(PaymentRefunded.class, shop.tenant))
                .extracting(PaymentRefunded::reason, PaymentRefunded::creditNoteId)
                .as("the return's refund names its credit note; the void's names none")
                .anySatisfy(t -> assertThat(t.toList()).element(1).isNotNull())
                .extracting(t -> t.toList().get(0))
                .contains("Cracked", "Cash back", "VOID: Wrong customer");
    }

    @Test
    void pay04ADrawerIsOperatedByItsCashierCountedByDenominationAndReconciled() {
        PaymentsShop shop = shop("Drawer");
        JsonNode drawer = shop.openDrawer(shop.cashier, "5000.00");
        String session = drawer.path("id").asString();
        String path = "/api/v1/drawer-sessions/" + session;
        assertThat(drawer.path("status").asString()).isEqualTo("OPEN");
        assertThat(drawer.path("movements").get(0).path("type").asString()).isEqualTo("FLOAT");
        assertThatProblem(shop.post(
                        "/api/v1/drawer-sessions",
                        shop.cashier,
                        "{\"registerId\": \"" + shop.register + "\", \"openingFloat\": \"100.00\"}"))
                .hasStatus(409)
                .hasCode("DRAWER_ALREADY_OPEN");

        shop.bill("10", cash("550.00", "1000.00"));
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(expected(shop, session)).isEqualTo("5550.00"));
        String movement = "{\"type\": \"%s\", \"amount\": \"%s\", \"reason\": \"%s\"}";
        assertThat(shop.post(path + "/movements", shop.cashier, movement.formatted("PAID_OUT", "250.00", "Ice")))
                .hasStatus(201);
        assertThat(shop.post(path + "/movements", shop.cashier, movement.formatted("DROP", "2000.00", "Safe")))
                .hasStatus(201);
        assertThatProblem(shop.post(
                        path + "/movements",
                        shop.cashier(UUID.randomUUID()),
                        movement.formatted("PAID_IN", "10.00", "Not mine")))
                .as("another cashier's drawer")
                .hasStatus(403)
                .hasCode("DRAWER_NOT_YOURS");
        assertThat(shop.post(path + "/movements", movement.formatted("PAID_IN", "100.00", "Change from bank")))
                .as("a manager may")
                .hasStatus(201);
        JsonNode expected = json(shop.get(path + "/expected"));
        assertThat(expected.path("expected").path("amount").asString())
                .as("5000 + 550 − 250 + 100 − 2000")
                .isEqualTo("3400.00");
        assertThat(expected.path("paidOut").path("amount").asString()).isEqualTo("250.00");
        assertThat(expected.path("drops").path("amount").asString()).isEqualTo("2000.00");

        assertThatProblem(shop.post(
                        path + "/count", shop.cashier, "{\"denominations\": [{\"value\": \"25\", \"count\": 1}]}"))
                .hasStatus(422)
                .hasCode("DENOMINATION_UNKNOWN");
        JsonNode counted = json(shop.post(
                path + "/count",
                shop.cashier,
                "{\"denominations\": [{\"value\": \"1000\", \"count\": 3}, {\"value\": \"100\", \"count\": 3}]}"));
        assertThat(counted.path("status").asString()).isEqualTo("COUNTED");
        assertThat(counted.path("variance").path("amount").asString())
                .as("100.00 short")
                .isEqualTo("-100.00");
        assertThatProblem(shop.post(path + "/movements", movement.formatted("PAID_IN", "1.00", "Late")))
                .hasStatus(409)
                .hasCode("DRAWER_STATE_INVALID");
        assertThatProblem(shop.post(path + "/reconcile", shop.cashier, "{}"))
                .as("a cashier does not reconcile")
                .hasStatus(403);

        JsonNode reconciled = json(shop.post(path + "/reconcile", "{\"note\": \"Short 100\"}"));
        assertThat(reconciled.path("status").asString()).isEqualTo("RECONCILED");
        assertThat(events.stream(DrawerReconciled.class)
                        .filter(e -> e.sessionId().toString().equals(session)))
                .singleElement()
                .satisfies(e -> assertThat(e.variance()).isEqualByComparingTo("-100.00"));
        assertThatProblem(shop.post(path + "/reconcile", "{}")).hasStatus(409).hasCode("DRAWER_STATE_INVALID");
        assertThat(json(shop.get("/api/v1/drawer-sessions?registerId=" + shop.register + "&status=RECONCILED"))
                        .path("items"))
                .hasSize(1);
        assertThat(shop.openDrawer(shop.cashier, "5000.00").path("status").asString())
                .as("the next drawer opens once the last is reconciled")
                .isEqualTo("OPEN");
        assertThatProblem(shop.get("/api/v1/drawer-sessions/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("DRAWER_SESSION_NOT_FOUND");
    }

    @Test
    void pay06TheDaysTotalsByMethodNetOfRefunds() {
        PaymentsShop shop = shop("Summary");
        shop.openDrawer(shop.cashier, "0.00");
        JsonNode cardBill = shop.bill("2", tender("CARD", "110.00", "AUTH-1"));
        JsonNode cashBill = shop.bill("10", cash("550.00", "550.00"));
        String card = shop.payments(cardBill.path("id").asString(), 1)
                .get(0)
                .path("id")
                .asString();
        shop.payments(cashBill.path("id").asString(), 1);
        assertThat(shop.post(
                        "/api/v1/payments/" + card + "/refunds",
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"amount\": \"10.00\", \"reason\": \"Price\"}"))
                .hasStatus(201);

        JsonNode summary = json(shop.get("/api/v1/payments/summary?date=" + TODAY + "&registerId=" + shop.register));

        assertThat(summary.path("byMethod"))
                .extracting(
                        m -> m.path("method").asString() + " " + m.path("count").asInt() + " "
                                + m.path("captured").path("amount").asString() + " "
                                + m.path("refunded").path("amount").asString() + " "
                                + m.path("net").path("amount").asString())
                .containsExactly("CARD 1 110.00 10.00 100.00", "CASH 1 550.00 0.00 550.00");
        assertThat(summary.path("net").path("amount").asString()).isEqualTo("650.00");
        assertThat(json(shop.get("/api/v1/payments/summary?date=2026-11-24")).path("byMethod"))
                .isEmpty();
        assertThat(json(shop.get("/api/v1/payments?method=CARD&businessDate=" + TODAY))
                        .path("items"))
                .hasSize(1);
        JsonNode first = json(shop.get("/api/v1/payments?limit=1"));
        assertThat(json(shop.get("/api/v1/payments?limit=1&cursor="
                                + first.path("page").path("nextCursor").asString()))
                        .path("items"))
                .hasSize(1);
        assertThatProblem(shop.get("/api/v1/payments?cursor=!!")).hasStatus(400);
        assertThatProblem(shop.get("/api/v1/payments/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PAYMENT_NOT_FOUND");
    }
}
