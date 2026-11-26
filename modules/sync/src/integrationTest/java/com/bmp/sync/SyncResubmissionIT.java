package com.bmp.sync;

import static com.bmp.sync.SyncTill.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.bmp.sales.api.SalesCommands;
import com.bmp.sync.SyncTestApplication.Devices;
import com.bmp.test.persistence.WithPostgres;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/**
 * SYN-04, SYN-05 under failure: processing dies in the middle of a batch (the database goes away while the third bill
 * is stored); the till sends the whole batch again and every record takes effect exactly once.
 */
@SpringBootTest(classes = SyncTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SyncResubmissionIT {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Devices devices;

    @MockitoSpyBean
    SalesCommands sales;

    @Test
    void syn04Syn05ABatchKilledHalfWayIsSentAgainAndEveryRecordTakesEffectOnce() {
        SyncTill till = new SyncTill(mvc, jdbc, transactions, devices, "Killed half-way");
        UUID shift = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        String[] batch = {
            till.shiftOpen(shift, NOW.minusSeconds(3600)),
            till.bill(UUID.randomUUID(), 1, "1", shift, NOW.minusSeconds(3000), shift),
            till.bill(UUID.randomUUID(), 2, "2", shift, NOW.minusSeconds(2500), shift),
            till.bill(third, 3, "3", shift, NOW.minusSeconds(2000), shift),
            till.bill(UUID.randomUUID(), 4, "4", shift, NOW.minusSeconds(1500), shift)
        };
        AtomicBoolean killed = new AtomicBoolean();
        doAnswer(call -> {
                    SalesCommands.OfflineBill bill = call.getArgument(0);
                    if (bill.id().equals(third) && killed.compareAndSet(false, true)) {
                        throw new DataAccessResourceFailureException("The database connection was lost");
                    }
                    return call.callRealMethod();
                })
                .when(sales)
                .submitBill(any());

        MvcTestResult cut = till.upload(null, batch);

        assertThat(cut.getResponse().getStatus())
                .as("a technical failure stops the batch")
                .isGreaterThanOrEqualTo(500);
        assertThat(bills(till)).as("what was stored before the failure stays").isEqualTo(2L);

        JsonNode resent = json(till.upload(null, batch));

        assertThat(resent.path("results"))
                .extracting(r -> r.path("status").asString())
                .containsExactly("DUPLICATE", "DUPLICATE", "DUPLICATE", "ACCEPTED", "ACCEPTED");
        assertThat(bills(till)).isEqualTo(4L);
        assertThat(till.<Long>sql(j -> j.sql("SELECT count(*) FROM sync.upload_record")
                        .query(Long.class)
                        .single()))
                .as("one outcome per record")
                .isEqualTo(5L);
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(
                                till.<Long>sql(j -> j.sql("""
                                        SELECT (SELECT count(*) FROM payments.payment)
                                             + (SELECT count(*) FROM inventory.stock_movement WHERE type = 'SALE')""").query(Long.class).single()))
                        .as("each bill paid once and issued from stock once")
                        .isEqualTo(8L));
        assertThat(json(till.upload(null, batch)).path("duplicates").asInt())
                .as("and once more: nothing new")
                .isEqualTo(5);
    }

    @Test
    void aFailureInsideTheOwningModuleIsNeverStoredAsARejection() {
        SyncTill till = new SyncTill(mvc, jdbc, transactions, devices, "Bug in sales");
        UUID shift = UUID.randomUUID();
        UUID bill = UUID.randomUUID();
        String[] batch = {
            till.shiftOpen(shift, NOW.minusSeconds(3600)), till.bill(bill, 1, "1", shift, NOW.minusSeconds(3000), shift)
        };
        AtomicBoolean broken = new AtomicBoolean(true);
        doAnswer(call -> {
                    if (broken.getAndSet(false)) {
                        throw new IllegalArgumentException("a bug, not a bad payload");
                    }
                    return call.callRealMethod();
                })
                .when(sales)
                .submitBill(any());

        assertThat(till.upload(null, batch).getResponse().getStatus()).isGreaterThanOrEqualTo(500);
        assertThat(json(till.upload(null, batch)).path("results"))
                .extracting(r -> r.path("status").asString())
                .as("the bill was not rejected for good: sent again, it is accepted")
                .containsExactly("DUPLICATE", "ACCEPTED");
    }

    private static long bills(SyncTill till) {
        return till.<Long>sql(
                j -> j.sql("SELECT count(*) FROM sales.bill").query(Long.class).single());
    }
}
