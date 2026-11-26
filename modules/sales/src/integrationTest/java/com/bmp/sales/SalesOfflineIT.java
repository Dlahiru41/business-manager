package com.bmp.sales;

import static com.bmp.sales.SalesApi.json;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.sales.api.events.SaleCompleted;
import com.bmp.sales.application.LinePricer.LineInput;
import com.bmp.sales.application.command.BillHandlers;
import com.bmp.sales.application.command.BillHandlers.SubmitBill;
import com.bmp.sales.application.command.BillHandlers.TenderInput;
import com.bmp.sales.domain.model.Bill;
import com.bmp.sales.domain.model.BillFlag;
import com.bmp.sales.domain.model.Mode;
import com.bmp.sales.domain.model.SalesChannel;
import com.bmp.sales.domain.model.TenderMethod;
import com.bmp.sales.domain.model.Totals;
import com.bmp.shared.context.TenantContext;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Instant;
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
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Offline bills (SAL-03, SAL-04, SAL-12) as the sync module submits them: accepted as the customer paid them, with
 * every difference flagged, and attached to the shift running at their time or to the register's offline shift.
 */
@SpringBootTest(classes = SalesTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class SalesOfflineIT {

    private static final Instant BEFORE_THE_SHIFT = Instant.parse("2026-11-25T08:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    BillHandlers bills;

    @Autowired
    ApplicationEvents events;

    private SubmitBill bill(SalesApi api, String unitPrice, String qty, Totals totals, Instant at) {
        return new SubmitBill(
                UUID.randomUUID(),
                api.nextNumber(),
                api.register,
                null,
                SalesChannel.RETAIL,
                null,
                List.of(new LineInput(
                        UUID.randomUUID(),
                        UUID.fromString(api.variant("EGG-RED-L").path("id").asString()),
                        UUID.fromString(api.unit("EGG-RED-L", "piece")),
                        new BigDecimal(qty),
                        false,
                        new BigDecimal(unitPrice),
                        null,
                        null,
                        null,
                        null)),
                null,
                true,
                List.of(new TenderInput(UUID.randomUUID(), TenderMethod.CASH, totals.grandTotal(), null, null)),
                totals,
                at,
                null,
                null,
                null);
    }

    private Bill offline(SalesApi api, SubmitBill bill) {
        return Objects.requireNonNull(TenantContext.callWith(api.tenant, () -> bills.submit(bill, Mode.OFFLINE)))
                .bill();
    }

    private static Totals totals(String grand) {
        BigDecimal value = new BigDecimal(grand);
        return new Totals(value, BigDecimal.ZERO, BigDecimal.ZERO, value);
    }

    @Test
    void anOfflineBillOutsideEveryShiftGoesToTheRegistersOfflineShiftWithItsDevicePrice() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Offline shift");

        Bill early = offline(api, bill(api, "54.00", "2", totals("108.00"), BEFORE_THE_SHIFT));
        Bill earlier = offline(api, bill(api, "55.00", "1", totals("55.00"), BEFORE_THE_SHIFT.minusSeconds(600)));

        assertThat(early.flags()).containsExactlyInAnyOrder(BillFlag.PRICE_MISMATCH, BillFlag.OFFLINE_SHIFT);
        assertThat(early.lines().getFirst().unitPrice()).isEqualByComparingTo("54.00");
        assertThat(early.totals().grandTotal()).isEqualByComparingTo("108.00");
        assertThat(early.shiftId()).isNotNull().isNotEqualTo(api.shift);
        assertThat(earlier.shiftId()).as("one offline shift per register").isEqualTo(early.shiftId());
        assertThat(earlier.flags())
                .as("no price was in force before 09:00, so the device's price stands")
                .containsExactlyInAnyOrder(BillFlag.PRICE_MISMATCH, BillFlag.OFFLINE_SHIFT);
        assertThat(api.post("/api/v1/shifts/" + api.shift + "/close", "{}")).hasStatusOk();
        assertThat(json(api.post("/api/v1/shifts", "{\"registerId\": \"" + api.register + "\"}"))
                        .path("status")
                        .asString())
                .as("the offline shift does not block opening the next shift")
                .isEqualTo("OPEN");
    }

    @Test
    void theDevicesTotalsStandWhenTheyDifferAndTheBillAttachesToTheShiftRunningAtItsTime() {
        SalesApi api = new SalesApi(mvc, jdbc, transactions, "Offline totals");

        Bill bill = offline(api, bill(api, "55.00", "2", totals("100.00"), Instant.parse("2026-11-25T09:00:00Z")));

        assertThat(bill.flags()).containsExactly(BillFlag.TOTALS_MISMATCH);
        assertThat(bill.totals().grandTotal()).as("the server's recomputation").isEqualByComparingTo("110.00");
        assertThat(Objects.requireNonNull(bill.deviceTotals()).grandTotal())
                .as("the customer paid 100")
                .isEqualByComparingTo("100.00");
        assertThat(bill.payableTotals()).isEqualTo(bill.deviceTotals());
        assertThat(events.stream(SaleCompleted.class).filter(e -> e.billId().equals(bill.id())))
                .singleElement()
                .satisfies(e -> assertThat(e.totals().grandTotal()).isEqualByComparingTo("100.00"));
        assertThat(bill.shiftId()).isEqualTo(api.shift);
        assertThat(bill.tenders())
                .singleElement()
                .satisfies(t -> assertThat(t.amount()).isEqualByComparingTo("100.00"));
        assertThat(json(api.get("/api/v1/bills?flagged=true")).path("items"))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.path("flags").toString()).contains("TOTALS_MISMATCH");
                    assertThat(b.path("deviceTotals")
                                    .path("grandTotal")
                                    .path("amount")
                                    .asString())
                            .isEqualTo("100.00");
                });
    }
}
