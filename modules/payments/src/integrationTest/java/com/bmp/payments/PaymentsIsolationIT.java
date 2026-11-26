package com.bmp.payments;

import static com.bmp.payments.PaymentsShop.cash;
import static com.bmp.payments.PaymentsShop.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Tenant A's payments, refunds and drawers are invisible to tenant B; RLS covers the schema and the payment, refund and
 * cash movement ledgers are append-only.
 */
@SpringBootTest(classes = PaymentsTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PaymentsIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void paymentsSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(
                List.of("payments"), List.of("payments.payment", "payments.refund", "payments.cash_movement"));
    }

    @Test
    void anotherTenantSeesNoneOfThePaymentsAndDrawersOfOne() {
        PaymentsShop a = new PaymentsShop(mvc, jdbc, transactions, "Payments isolation A");
        PaymentsShop b = new PaymentsShop(mvc, jdbc, transactions, "Payments isolation B");
        String session = a.openDrawer(a.cashier, "1000.00").path("id").asString();
        String bill = a.bill("2", cash("110.00", "110.00")).path("id").asString();
        String payment = a.payments(bill, 1).get(0).path("id").asString();

        assertThatProblem(b.get("/api/v1/payments/" + payment)).hasStatus(404).hasCode("PAYMENT_NOT_FOUND");
        assertThatProblem(b.get("/api/v1/drawer-sessions/" + session))
                .hasStatus(404)
                .hasCode("DRAWER_SESSION_NOT_FOUND");
        assertThatProblem(b.post(
                        "/api/v1/payments/" + payment + "/refunds",
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"amount\": \"1.00\", \"reason\": \"Not mine\"}"))
                .hasStatus(404)
                .hasCode("PAYMENT_NOT_FOUND");
        assertThatProblem(b.post(
                        "/api/v1/drawer-sessions/" + session + "/movements",
                        "{\"type\": \"PAID_OUT\", \"amount\": \"1.00\", \"reason\": \"Not mine\"}"))
                .hasStatus(404)
                .hasCode("DRAWER_SESSION_NOT_FOUND");
        assertThatProblem(b.post(
                        "/api/v1/drawer-sessions",
                        "{\"registerId\": \"" + a.register + "\", \"openingFloat\": \"1.00\"}"))
                .as("A's register is unknown to B")
                .hasStatus(422)
                .hasCode("UNKNOWN_REGISTER");
        assertThat(json(b.get("/api/v1/payments")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/drawer-sessions")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/payments/summary?date=2026-11-25")).path("byMethod"))
                .isEmpty();
        assertThat(b.<Long>sql(j -> j.sql("SELECT count(*) FROM payments.payment WHERE tenant_id = :a")
                        .param("a", a.tenant.value())
                        .query(Long.class)
                        .single()))
                .isZero();
    }
}
