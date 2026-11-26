package com.bmp.payments;

import static com.bmp.payments.PaymentsShop.cash;
import static com.bmp.payments.PaymentsShop.json;
import static com.bmp.payments.PaymentsShop.tender;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.payments.api.PaymentCommands;
import com.bmp.payments.api.PaymentQueries;
import com.bmp.payments.api.dto.DrawerSessionInfo;
import com.bmp.payments.application.command.PaymentHandlers;
import com.bmp.payments.domain.model.Refund;
import com.bmp.persistence.event.OutOfOrderEventException;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * The paths around the main flows: the drawer commands sync replays, refunds whose sale is not recorded yet, credit-only
 * returns and voids, counter refunds in cash, and the variance alert.
 */
@SpringBootTest(classes = PaymentsTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PaymentsEdgeCasesIT {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    PaymentCommands commands;

    @Autowired
    PaymentQueries queries;

    @Autowired
    PaymentHandlers handlers;

    private PaymentsShop shop(String name) {
        return new PaymentsShop(mvc, jdbc, transactions, name);
    }

    private static <T> T in(PaymentsShop shop, Supplier<T> action) {
        return Objects.requireNonNull(TenantContext.callWith(shop.tenant, action::get));
    }

    @Test
    void syncReplaysDrawerCommandsByTheirIdsAndTheQueriesSeeTheDrawer() {
        PaymentsShop shop = shop("Drawer commands");
        UUID session = UUID.randomUUID();
        PaymentCommands.OpenDrawer open =
                new PaymentCommands.OpenDrawer(session, shop.register, new BigDecimal("2000.00"), NOW, shop.cashierId);

        DrawerSessionInfo opened = in(shop, () -> commands.openDrawer(open));
        assertThat(in(shop, () -> commands.openDrawer(open)).id())
                .as("a replay")
                .isEqualTo(opened.id());
        UUID movement = UUID.randomUUID();
        PaymentCommands.CashMovementCommand paidOut = new PaymentCommands.CashMovementCommand(
                movement, session, shop.register, "PAID_OUT", new BigDecimal("300.00"), "Delivery tip", NOW);
        in(shop, () -> {
            commands.recordCashMovement(paidOut);
            commands.recordCashMovement(paidOut);
            return true;
        });
        assertThat(in(shop, () -> queries.activeDrawer(shop.register)))
                .hasValueSatisfying(d ->
                        assertThat(d.expectedCash()).as("not paid out twice").isEqualByComparingTo("1700.00"));
        assertThatThrownBy(() -> in(shop, () -> {
                    commands.recordCashMovement(new PaymentCommands.CashMovementCommand(
                            UUID.randomUUID(), session, shop.register, "BRIBE", BigDecimal.ONE, "No", NOW));
                    return true;
                }))
                .isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> in(shop, () -> {
                    commands.recordCashMovement(new PaymentCommands.CashMovementCommand(
                            UUID.randomUUID(), session, shop.register, "SALE", BigDecimal.ONE, "By hand", NOW));
                    return true;
                }))
                .as("sales come from bills, not by hand")
                .isInstanceOf(BusinessRuleViolation.class);
        assertThatThrownBy(() -> in(shop, () -> {
                    commands.recordCashMovement(new PaymentCommands.CashMovementCommand(
                            UUID.randomUUID(), session, shop.register, "DROP", BigDecimal.ZERO, "Nothing", NOW));
                    return true;
                }))
                .isInstanceOf(BusinessRuleViolation.class);

        DrawerSessionInfo counted = in(
                shop,
                () -> commands.countDrawer(new PaymentCommands.CountDrawer(
                        session,
                        shop.register,
                        List.of(
                                new PaymentCommands.Denomination(new BigDecimal("1000"), 1),
                                new PaymentCommands.Denomination(new BigDecimal("100"), 2)),
                        NOW,
                        shop.cashierId)));
        assertThat(counted.status()).isEqualTo("COUNTED");
        assertThat(counted.variance()).as("500 short").isEqualByComparingTo("-500.00");
        assertThat(in(shop, () -> queries.findDrawer(session)))
                .hasValueSatisfying(d -> assertThat(d.countedCash()).isEqualByComparingTo("1200"));
        assertThat(in(shop, () -> queries.findDrawer(UUID.randomUUID()))).isEmpty();
        assertThat(in(shop, () -> queries.activeDrawer(UUID.randomUUID()))).isEmpty();
        assertThatThrownBy(() -> in(shop, () -> {
                    commands.recordCashMovement(new PaymentCommands.CashMovementCommand(
                            UUID.randomUUID(), session, UUID.randomUUID(), "PAID_OUT", BigDecimal.TEN, "Tip", NOW));
                    return true;
                }))
                .as("SYN-01: a till reaches only its own register's drawer")
                .hasMessageStartingWith("DRAWER_OF_ANOTHER_REGISTER");
    }

    @Test
    void aVarianceAboveTheAlertAmountIsReconciledAndAlerted() {
        PaymentsShop shop = shop("Variance alert");
        String session = shop.openDrawer(shop.cashier, "5000.00").path("id").asString();
        String path = "/api/v1/drawer-sessions/" + session;
        assertThat(shop.post(
                        path + "/count", shop.cashier, "{\"denominations\": [{\"value\": \"1000\", \"count\": 4}]}"))
                .hasStatusOk();

        JsonNode reconciled = json(shop.post(path + "/reconcile", "{\"note\": \"1,000 missing\"}"));

        assertThat(reconciled.path("variance").path("amount").asString()).isEqualTo("-1000.00");
        assertThat(reconciled.path("status").asString()).isEqualTo("RECONCILED");
    }

    @Test
    void refundsOfASaleNotRecordedYetWaitAndCreditOnlyReturnsAndVoidsMoveNoMoney() {
        PaymentsShop shop = shop("Out of order");
        UUID bill = UUID.randomUUID();
        PaymentHandlers.SaleReturn early = new PaymentHandlers.SaleReturn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                bill,
                shop.register,
                "Cracked",
                NOW,
                List.of(new PaymentHandlers.ReturnRefund(UUID.randomUUID(), "CASH", new BigDecimal("10.00"))));

        assertThatThrownBy(() -> in(shop, () -> handlers.refundReturn(early)))
                .isInstanceOf(OutOfOrderEventException.class);
        assertThatThrownBy(() -> in(
                        shop,
                        () -> handlers.refundVoid(new PaymentHandlers.SaleVoid(
                                UUID.randomUUID(), bill, shop.register, "Wrong", NOW, true))))
                .isInstanceOf(OutOfOrderEventException.class);
        assertThat(in(
                        shop,
                        () -> handlers.refundReturn(new PaymentHandlers.SaleReturn(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                bill,
                                shop.register,
                                "Credit",
                                NOW,
                                List.of(new PaymentHandlers.ReturnRefund(
                                        UUID.randomUUID(), "CREDIT", BigDecimal.TEN))))))
                .as("a credit refund reduces what the customer owes; no money moves")
                .isEmpty();
        assertThat(in(
                        shop,
                        () -> handlers.refundVoid(new PaymentHandlers.SaleVoid(
                                UUID.randomUUID(), bill, shop.register, "Credit", NOW, false))))
                .isEmpty();
    }

    @Test
    void aReturnRefundSpreadOverPaymentsIsRecordedOnceAndNeverBeyondThem() {
        PaymentsShop shop = shop("Spread refund");
        JsonNode bill = shop.bill("2", cash("60.00", "60.00"), tender("CARD", "50.00", "AUTH-9"));
        shop.payments(bill.path("id").asString(), 2);
        UUID refundId = UUID.randomUUID();
        PaymentHandlers.SaleReturn back = new PaymentHandlers.SaleReturn(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.fromString(bill.path("id").asString()),
                shop.register,
                "All of it and more",
                NOW,
                List.of(new PaymentHandlers.ReturnRefund(refundId, "CASH", new BigDecimal("130.00"))));

        List<Refund> refunds = in(shop, () -> handlers.refundReturn(back));

        assertThat(refunds).hasSize(2);
        assertThat(refunds.getFirst().id()).isEqualTo(refundId);
        assertThat(refunds.stream().map(Refund::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("110 paid, 20 could not be refunded")
                .isEqualByComparingTo("110.00");
        assertThat(in(shop, () -> handlers.refundReturn(back)))
                .as("delivered again")
                .isEmpty();
        assertThat(in(
                        shop,
                        () -> handlers.refundVoid(new PaymentHandlers.SaleVoid(
                                UUID.randomUUID(),
                                UUID.fromString(bill.path("id").asString()),
                                shop.register,
                                "Late",
                                NOW,
                                true))))
                .as("nothing is left to give back")
                .isEmpty();
    }

    @Test
    void counterRefundsInCashNeedAnOpenDrawerAndCollectionsNameTheirRegister() {
        PaymentsShop shop = shop("Counter");
        UUID customer = com.bmp.test.web.ApiClient.id(shop.client.post(
                "/api/v1/parties", shop.owner, "{\"type\": \"CUSTOMER\", \"displayName\": \"Lake View\"}"));
        JsonNode bill = shop.bill("2", tender("CARD", "110.00", "AUTH-3"));
        String card =
                shop.payments(bill.path("id").asString(), 1).get(0).path("id").asString();
        String refund = "{\"id\": \"%s\", \"amount\": \"%s\", \"reason\": \"Price\"%s}";

        assertThatProblem(shop.post(
                        "/api/v1/payments/" + card + "/refunds",
                        refund.formatted(UUID.randomUUID(), "10.00", ", \"method\": \"CASH\"")))
                .as("the bill's register has no open drawer")
                .hasStatus(409)
                .hasCode("DRAWER_NOT_OPEN");
        assertThatProblem(shop.post(
                        "/api/v1/payments/" + card + "/refunds", refund.formatted(UUID.randomUUID(), "0.00", "")))
                .hasStatus(422);
        assertThatProblem(shop.post(
                        "/api/v1/payments",
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"customerId\": \"" + customer
                                + "\", \"method\": \"CASH\", \"amount\": \"10.00\"}"))
                .as("cash names its register")
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThat(shop.post(
                        "/api/v1/payments",
                        "{\"id\": \"" + UUID.randomUUID() + "\", \"customerId\": \"" + customer
                                + "\", \"method\": \"CHEQUE\", \"amount\": \"10.00\", \"registerId\": \""
                                + shop.register + "\"}"))
                .hasStatus(201);
    }
}
