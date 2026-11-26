package com.bmp.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.sales.SalesTestApplication.Approvers;
import com.bmp.sales.api.SalesCommands;
import com.bmp.sales.api.SalesQueries;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.id.RegisterId;
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

/** The offline commands sync replays through sales' API (SYN-04), and the bill numbers sync checks for gaps. */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SalesCommandsIT {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    SalesCommands commands;

    @Autowired
    SalesQueries queries;

    @Autowired
    Approvers approvers;

    private static <T> T in(SalesApi api, Supplier<T> action) {
        return Objects.requireNonNull(TenantContext.callWith(api.tenant, action::get));
    }

    private static SalesCommands.OfflineBill bill(
            SalesApi api, UUID id, String number, String channel, SalesCommands.OfflineDiscount discount) {
        BigDecimal total = new BigDecimal("99.00");
        return new SalesCommands.OfflineBill(
                id,
                number,
                api.register,
                api.shift,
                channel,
                null,
                null,
                null,
                List.of(new SalesCommands.OfflineLine(
                        UUID.randomUUID(),
                        UUID.fromString(api.variant("EGG-RED-L").path("id").asString()),
                        UUID.fromString(api.unit("EGG-RED-L", "piece")),
                        new BigDecimal("2"),
                        false,
                        new BigDecimal("55.00"),
                        null,
                        null,
                        null)),
                discount,
                List.of(new SalesCommands.OfflineTender(UUID.randomUUID(), "CASH", total, total, null)),
                new SalesCommands.OfflineTotals(
                        new BigDecimal("110.00"), new BigDecimal("11.00"), BigDecimal.ZERO, total),
                NOW);
    }

    @Test
    void syncReplaysShiftsBillsVoidsAndReturnsOffline() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Commands");
        UUID manager = approvers.add("sales:void");
        SalesCommands.OfflineDiscount tenPercent =
                new SalesCommands.OfflineDiscount("PERCENT", BigDecimal.TEN, "Regular", null);

        in(api, () -> {
            commands.closeShift(api.shift, NOW);
            commands.closeShift(api.shift, NOW.plusSeconds(60));
            UUID next = UUID.randomUUID();
            commands.openShift(next, api.register, null, NOW.plusSeconds(120));
            commands.openShift(next, api.register, null, NOW.plusSeconds(120));
            return next;
        });
        UUID sold = UUID.randomUUID();
        UUID voided = UUID.randomUUID();
        in(api, () -> {
            commands.submitBill(bill(api, sold, "R1-000001", "RETAIL", tenPercent));
            commands.submitBill(bill(api, voided, "R1-000003", "RETAIL", null));
            commands.voidBill(voided, "Wrong customer", manager, NOW);
            return true;
        });
        JsonNode bill = SalesApi.json(api.get("/api/v1/bills/" + sold));
        String line = bill.path("lines").get(0).path("id").asString();
        in(api, () -> {
            commands.returnBill(new SalesCommands.OfflineReturn(
                    UUID.randomUUID(),
                    sold,
                    "Cracked",
                    List.of(new SalesCommands.OfflineReturnLine(UUID.fromString(line), BigDecimal.ONE, true)),
                    List.of(new SalesCommands.OfflineRefund("CASH", new BigDecimal("49.50"), null)),
                    null,
                    NOW.plusSeconds(300)));
            return true;
        });

        assertThat(bill.path("totals").path("discountTotal").path("amount").asString())
                .isEqualTo("11.00");
        assertThat(SalesApi.json(api.get("/api/v1/bills/" + voided))
                        .path("status")
                        .asString())
                .isEqualTo("VOIDED");
        assertThat(SalesApi.json(api.get("/api/v1/bills/" + sold))
                        .path("status")
                        .asString())
                .isEqualTo("PARTIALLY_RETURNED");
        assertThat(in(api, () -> queries.billSequences(RegisterId.of(api.register))))
                .containsExactly(1L, 3L);
        assertThat(in(api, () -> queries.billSequences(RegisterId.of(UUID.randomUUID()))))
                .isEmpty();
        assertThatThrownBy(() -> in(api, () -> {
                    commands.submitBill(bill(api, UUID.randomUUID(), "R1-000004", "MARKET", null));
                    return true;
                }))
                .isInstanceOf(BusinessRuleViolation.class)
                .hasMessageContaining("Unknown value MARKET");
        assertThatThrownBy(() -> in(api, () -> {
                    commands.returnBill(new SalesCommands.OfflineReturn(
                            UUID.randomUUID(),
                            sold,
                            "Odd",
                            List.of(new SalesCommands.OfflineReturnLine(UUID.fromString(line), BigDecimal.ONE, false)),
                            List.of(new SalesCommands.OfflineRefund("BITCOIN", BigDecimal.ONE, null)),
                            null,
                            NOW));
                    return true;
                }))
                .isInstanceOf(BusinessRuleViolation.class);
    }
}
