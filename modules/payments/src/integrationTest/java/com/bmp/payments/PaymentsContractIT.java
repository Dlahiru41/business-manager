package com.bmp.payments;

import static com.bmp.payments.PaymentsShop.cash;
import static com.bmp.payments.PaymentsShop.json;
import static com.bmp.payments.PaymentsShop.tender;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.payments.PaymentsTestApplication.PaymentEvents;
import com.bmp.payments.api.events.DrawerReconciled;
import com.bmp.payments.api.events.PaymentCaptured;
import com.bmp.payments.api.events.PaymentRefunded;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/** Payments keeps its contracts: every operation protected as x-permission says; its events as AsyncAPI says. */
@SpringBootTest(classes = PaymentsTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PaymentsContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PaymentEvents recorded;

    @Autowired
    JsonMapper json;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        PaymentsShop shop = new PaymentsShop(mvc, jdbc, transactions, "Payments security");
        String nobody =
                JwtTestTokens.builder().tenant(shop.tenant).roles("NOBODY").build();
        ContractSecurity.assertEveryOperationIsProtected("payments", mvc, context, nobody);
    }

    @Test
    void paymentsEventsConformToTheAsyncApiContract() {
        PaymentsShop shop = new PaymentsShop(mvc, jdbc, transactions, "Payments events");
        String session = shop.openDrawer(shop.cashier, "1000.00").path("id").asString();
        var bill = shop.bill("2", cash("60.00", "100.00"), tender("CARD", "50.00", "AUTH-1"));
        String card = shop.payments(bill.path("id").asString(), 2).findValues("id").stream()
                .map(n -> n.asString())
                .filter(id -> json(shop.get("/api/v1/payments/" + id))
                        .path("method")
                        .asString()
                        .equals("CARD"))
                .findFirst()
                .orElseThrow();
        assertThat(shop.post(
                        "/api/v1/payments/" + card + "/refunds",
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"amount\": \"10.00\", \"reason\": \"Price\"}"))
                .hasStatus(201);
        assertThat(
                        shop.post(
                                "/api/v1/drawer-sessions/" + session + "/count",
                                shop.cashier,
                                "{\"denominations\": [{\"value\": \"1000\", \"count\": 1}, {\"value\": \"50\", \"count\": 1}]}"))
                .hasStatusOk();
        assertThat(shop.post("/api/v1/drawer-sessions/" + session + "/reconcile", "{}"))
                .hasStatusOk();

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(recorded.of(PaymentCaptured.class, shop.tenant))
                        .hasSize(2));
        assertThat(recorded.of(PaymentRefunded.class, shop.tenant)).hasSize(1);
        assertThat(recorded.of(DrawerReconciled.class, shop.tenant))
                .singleElement()
                .satisfies(e -> assertThat(e.variance())
                        .as("1,060 expected, 1,050 counted")
                        .isEqualByComparingTo("-10"));
        recorded.of(PaymentCaptured.class, shop.tenant)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "PaymentCaptured"));
        recorded.of(PaymentRefunded.class, shop.tenant)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "PaymentRefunded"));
        recorded.of(DrawerReconciled.class, shop.tenant)
                .forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "DrawerReconciled"));
    }
}
