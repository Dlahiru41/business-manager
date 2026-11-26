package com.bmp.sync;

import static com.bmp.sync.SyncTill.json;
import static com.bmp.sync.SyncTill.record;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.sync.SyncTestApplication.Devices;
import com.bmp.sync.SyncTestApplication.Gaps;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** Offline tills through the sync API (SYN-01..SYN-08). */
@SpringBootTest(classes = SyncTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class SyncApiIT {

    private static final Instant NOW = Instant.parse("2026-11-25T09:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    Devices devices;

    @Autowired
    Gaps gaps;

    private SyncTill till(String name) {
        return new SyncTill(mvc, jdbc, transactions, devices, name);
    }

    private static JsonNode results(JsonNode upload) {
        return upload.path("results");
    }

    @Test
    void syn01OnlyADeviceBoundToAnActiveRegisterSyncs() {
        SyncTill till = till("Devices");

        assertThatProblem(till.get("/api/v1/sync/bootstrap", till.owner))
                .hasStatus(403)
                .hasCode("DEVICE_REQUIRED");
        String stranger = JwtTestTokens.builder()
                .tenant(till.tenant)
                .device(devices.unbound(till.location))
                .roles("DEVICE")
                .build();
        assertThatProblem(till.get("/api/v1/sync/status", stranger))
                .hasStatus(403)
                .hasCode("DEVICE_NOT_BOUND");
        assertThat(json(till.get("/api/v1/sync/status")).path("registerId").asString())
                .isEqualTo(till.register.toString());
    }

    @Test
    void syn02Syn03ABootstrapHoldsTheTillsDataAndChangesSendsOnlyWhatChanged() {
        SyncTill till = till("Bootstrap");

        JsonNode bootstrap = json(till.get("/api/v1/sync/bootstrap"));

        assertThat(bootstrap.path("register").path("billPrefix").asString()).isEqualTo("R1");
        assertThat(bootstrap.path("register").path("lastNumber").asLong()).isZero();
        JsonNode datasets = bootstrap.path("datasets");
        assertThat(datasets.path("catalog").path("data").path("variants")).isNotEmpty();
        assertThat(datasets.path("priceLists").path("data")).isNotEmpty();
        assertThat(datasets.path("settings")
                        .path("data")
                        .path("sales")
                        .path("returnWindowDays")
                        .asInt())
                .isEqualTo(7);
        assertThat(datasets.path("customers").path("data")).isEmpty();
        assertThat(datasets.path("receiptTemplate")
                        .path("data")
                        .path("shopName")
                        .asString())
                .as("no template yet: the tenant's name heads the receipt")
                .startsWith("Bootstrap");
        String cursors = String.join(
                ",",
                java.util.stream.StreamSupport.stream(datasets.properties().spliterator(), false)
                        .map(e ->
                                e.getKey() + ":" + e.getValue().path("version").asString())
                        .toList());
        assertThat(json(till.get("/api/v1/sync/changes?cursors=" + cursors))
                        .path("datasets")
                        .properties())
                .as("nothing changed")
                .isEmpty();

        assertThat(till.client.post(
                        "/api/v1/parties",
                        till.owner,
                        "{\"type\": \"CUSTOMER\", \"displayName\": \"Hotel Lake View\"}"))
                .hasStatus(201);
        JsonNode changed =
                json(till.get("/api/v1/sync/changes?cursors=" + cursors)).path("datasets");
        assertThat(changed.properties()).extracting(java.util.Map.Entry::getKey).containsExactly("customers");
        assertThat(changed.path("customers")
                        .path("data")
                        .get(0)
                        .path("displayName")
                        .asString())
                .isEqualTo("Hotel Lake View");
        assertThat(till.client.post(
                        "/api/v1/receipt-templates",
                        till.owner,
                        "{\"name\": \"Counter\", \"paperWidthMm\": 58, \"language\": \"en\", \"isDefault\": true,"
                                + " \"header\": {\"lines\": [\"Lanka Mart\", \"12 Main Street\"]}}"))
                .hasStatus(201);
        JsonNode template = json(till.get("/api/v1/sync/changes?cursors=" + cursors))
                .path("datasets")
                .path("receiptTemplate")
                .path("data");
        assertThat(template.path("shopName").asString()).isEqualTo("Lanka Mart");
        assertThat(template.path("addressLines").get(0).asString()).isEqualTo("12 Main Street");
        assertThat(template.path("paperWidthMm").asInt()).isEqualTo(58);
        assertThatProblem(till.get("/api/v1/sync/changes?cursors=gossip:1")).hasStatus(400);
    }

    @Test
    void syn04Syn05AnOfflineDayIsUploadedOnceAndAReplayIsDuplicate() {
        SyncTill till = till("Offline day");
        UUID shift = UUID.randomUUID();
        UUID drawer = UUID.randomUUID();
        UUID bill = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        String egg = till.shop.variant("EGG-RED-L").path("id").asString();
        String[] day = {
            till.shiftOpen(shift, NOW.minusSeconds(3600)),
            till.drawerOpen(drawer, "1000.00", NOW.minusSeconds(3600)),
            till.bill(bill, line, 1, "10", shift, NOW.minusSeconds(1800), shift),
            record(
                    "CASH_MOVEMENT",
                    UUID.randomUUID(),
                    "{\"sessionId\": \"" + drawer
                            + "\", \"type\": \"PAID_OUT\", \"amount\": \"100.00\", \"reason\": \"Ice\", \"occurredAt\": \""
                            + NOW.minusSeconds(1700) + "\"}",
                    drawer),
            record(
                    "RETURN",
                    UUID.randomUUID(),
                    "{\"billId\": \"" + bill + "\", \"reason\": \"Cracked\", \"lines\": "
                            + "[{\"billLineId\": \"" + line
                            + "\", \"qty\": \"2\", \"restock\": true}], \"returnedAt\": \""
                            + NOW.minusSeconds(1600) + "\"}",
                    bill),
            record(
                    "WASTAGE",
                    UUID.randomUUID(),
                    "{\"reason\": \"BREAKAGE\", \"lines\": [{\"variantId\": \"" + egg
                            + "\", \"qty\": \"3\"}], \"occurredAt\": \"" + NOW.minusSeconds(1500) + "\"}"),
            record(
                    "DRAWER_COUNT",
                    UUID.randomUUID(),
                    "{\"sessionId\": \"" + drawer + "\", \"denominations\": "
                            + "[{\"value\": \"1000\", \"count\": 1}, {\"value\": \"100\", \"count\": 3}], \"countedAt\": \""
                            + NOW.minusSeconds(600) + "\"}",
                    drawer),
            record(
                    "SHIFT_CLOSE",
                    UUID.randomUUID(),
                    "{\"shiftId\": \"" + shift + "\", \"closedAt\": \"" + NOW.minusSeconds(500) + "\"}",
                    shift)
        };

        JsonNode first = json(till.upload(NOW, day));

        assertThat(results(first))
                .allSatisfy(r ->
                        assertThat(r.path("status").asString()).as(r.toString()).isEqualTo("ACCEPTED"));
        assertThat(first.path("accepted").asInt()).isEqualTo(8);
        JsonNode replay = json(till.upload(NOW, day));
        assertThat(replay.path("duplicates").asInt()).isEqualTo(8);
        JsonNode sold = json(till.client.get("/api/v1/bills/" + bill, till.owner));
        assertThat(sold.path("status").asString()).isEqualTo("PARTIALLY_RETURNED");
        assertThat(sold.path("shiftId").asString()).isEqualTo(shift.toString());
        // The bill's cash reaches the drawer through the payments listener, after the count in the same batch: the
        // count's variance is a snapshot, reconciliation recomputes it (ADR-028 §6).
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(
                                json(till.client.get("/api/v1/drawer-sessions/" + drawer + "/expected", till.owner))
                                        .path("expected")
                                        .path("amount")
                                        .asString())
                        .as("1000 + 550 − 100 − 110")
                        .isEqualTo("1340.00"));
        assertThat(json(till.client.get("/api/v1/drawer-sessions/" + drawer, till.owner))
                        .path("countedCash")
                        .path("amount")
                        .asString())
                .isEqualTo("1300.00");
        assertThat(till.<Long>sql(j -> j.sql("SELECT count(*) FROM inventory.stock_movement WHERE type = 'WASTAGE'")
                        .query(Long.class)
                        .single()))
                .as("the wastage is booked once")
                .isEqualTo(1L);
    }

    @Test
    void syn04ARejectedRecordTakesItsDependantsAlongAndBadPayloadsAreRejected() {
        SyncTill till = till("Rejections");
        UUID voidOfNothing = UUID.randomUUID();

        JsonNode upload = json(till.upload(
                null,
                record(
                        "VOID",
                        voidOfNothing,
                        "{\"billId\": \"" + UUID.randomUUID() + "\", \"reason\": \"Wrong\", \"voidedAt\": \"" + NOW
                                + "\"}"),
                record(
                        "SHIFT_CLOSE",
                        UUID.randomUUID(),
                        "{\"shiftId\": \"" + UUID.randomUUID() + "\", \"closedAt\": \"" + NOW + "\"}",
                        voidOfNothing),
                record("BILL", UUID.randomUUID(), "{\"number\": \"R1-000001\"}"),
                record(
                        "WASTAGE",
                        UUID.randomUUID(),
                        "{\"reason\": \"MAGIC\", \"lines\": [{\"variantId\": \"" + UUID.randomUUID()
                                + "\", \"qty\": \"1\"}], \"occurredAt\": \"" + NOW + "\"}"),
                till.bill(UUID.randomUUID(), 7, "1", null, NOW)
                        .replace("\"amount\": \"55.00\"", "\"amount\": \"55.00004\"")));

        assertThat(results(upload))
                .extracting(r -> r.path("status").asString() + " "
                        + r.path("problem").path("code").asString())
                .containsExactly(
                        "REJECTED BILL_NOT_FOUND",
                        "REJECTED DEPENDENCY_REJECTED",
                        "REJECTED VALIDATION_FAILED",
                        "REJECTED VALIDATION_FAILED",
                        "REJECTED VALIDATION_FAILED");
        assertThat(results(upload).get(4).path("problem").path("detail").asString())
                .as("an amount with five decimals, named")
                .contains("tenders[0].amount");
        assertThat(upload.path("rejected").asInt()).isEqualTo(5);
        JsonNode again = json(till.upload(
                null, record("SHIFT_OPEN", UUID.randomUUID(), "{\"openedAt\": \"" + NOW + "\"}", voidOfNothing)));
        assertThat(results(again).get(0).path("problem").path("code").asString())
                .as("rejected in an earlier batch")
                .isEqualTo("DEPENDENCY_REJECTED");
    }

    @Test
    void syn01ATillActsForItsOwnRegisterAndRefundsAReturnFromItsOwnDrawer() {
        SyncTill counter1 = till("Two counters");
        UUID register2 = UUID.fromString(json(counter1.client.post(
                        "/api/v1/registers",
                        counter1.owner,
                        "{\"code\": \"R2\", \"locationId\": \"" + counter1.location + "\", \"billPrefix\": \"R2\"}"))
                .path("id")
                .asString());
        String till2 = JwtTestTokens.builder()
                .tenant(counter1.tenant)
                .device(devices.bind(register2, counter1.location))
                .roles("DEVICE")
                .build();
        UUID shift1 = UUID.randomUUID();
        UUID drawer1 = UUID.randomUUID();
        UUID bill1 = UUID.randomUUID();
        UUID line1 = UUID.randomUUID();
        json(counter1.upload(
                null,
                counter1.shiftOpen(shift1, NOW.minusSeconds(3600)),
                counter1.drawerOpen(drawer1, "1000.00", NOW.minusSeconds(3600)),
                counter1.bill(bill1, line1, 1, "4", shift1, NOW.minusSeconds(3000), shift1)));
        UUID drawer2 = UUID.randomUUID();
        String fromCounter2 = String.join(
                ", ",
                counter1.drawerOpen(drawer2, "500.00", NOW.minusSeconds(1800)),
                record(
                        "SHIFT_CLOSE",
                        UUID.randomUUID(),
                        "{\"shiftId\": \"" + shift1 + "\", \"closedAt\": \"" + NOW + "\"}"),
                record(
                        "DRAWER_COUNT",
                        UUID.randomUUID(),
                        "{\"sessionId\": \"" + drawer1 + "\", \"denominations\": [{\"value\": \"1000\", \"count\": 1}],"
                                + " \"countedAt\": \"" + NOW + "\"}"),
                record(
                        "RETURN",
                        UUID.randomUUID(),
                        "{\"billId\": \"" + bill1 + "\", \"reason\": \"Cracked\", \"lines\": [{\"billLineId\": \""
                                + line1 + "\", \"qty\": \"1\", \"restock\": true}], \"refundTenders\": [{\"method\":"
                                + " \"CASH\", \"amount\": \"55.00\"}], \"returnedAt\": \"" + NOW.minusSeconds(60)
                                + "\"}"));

        JsonNode results = results(json(SyncTill.checked(mvc.post()
                .uri("/api/v1/sync/uploads")
                .header("Authorization", "Bearer " + till2)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"records\": [" + fromCounter2 + "]}")
                .exchange())));

        assertThat(results)
                .extracting(r -> r.path("status").asString() + " "
                        + r.path("problem").path("code").asString(""))
                .containsExactly(
                        "ACCEPTED ",
                        "REJECTED SHIFT_OF_ANOTHER_REGISTER",
                        "REJECTED DRAWER_OF_ANOTHER_REGISTER",
                        "ACCEPTED ");
        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(counter1.<Map<String, Object>>sql(
                                j -> j.sql("""
                                        SELECT register_id::text AS register, drawer_session_id::text AS drawer, amount
                                          FROM payments.refund""").query().singleRow()))
                        .as("the return at counter 2 is paid from counter 2's drawer")
                        .containsEntry("register", register2.toString())
                        .containsEntry("drawer", drawer2.toString()));
    }

    @Test
    void aBatchCutOffHalfWayResumesWhereItStopped() {
        SyncTill till = till("Resume");
        UUID shift = UUID.randomUUID();
        String[] all = {
            till.shiftOpen(shift, NOW.minusSeconds(3600)),
            till.bill(UUID.randomUUID(), 1, "1", shift, NOW.minusSeconds(3000), shift),
            till.bill(UUID.randomUUID(), 2, "2", shift, NOW.minusSeconds(2000), shift),
            till.bill(UUID.randomUUID(), 3, "3", shift, NOW.minusSeconds(1000), shift)
        };

        json(till.upload(null, all[0], all[1]));
        JsonNode resumed = json(till.upload(null, all));

        assertThat(results(resumed))
                .extracting(r -> r.path("status").asString())
                .containsExactly("DUPLICATE", "DUPLICATE", "ACCEPTED", "ACCEPTED");
        assertThat(till.<Long>sql(j -> j.sql("SELECT count(*) FROM sales.bill")
                        .query(Long.class)
                        .single()))
                .isEqualTo(3L);
    }

    @Test
    void syn06AMissingBillNumberIsReportedOnce() {
        SyncTill till = till("Gaps");

        json(till.upload(
                null,
                till.bill(UUID.randomUUID(), 1, "1", null, NOW),
                till.bill(UUID.randomUUID(), 3, "1", null, NOW)));
        json(till.upload(null, till.bill(UUID.randomUUID(), 4, "1", null, NOW)));

        assertThat(gaps.of(till.register)).singleElement().satisfies(e -> {
            assertThat(e.missingNumbers()).containsExactly("R1-000002");
            assertThat(e.billPrefix()).isEqualTo("R1");
        });
        assertThat(json(till.get("/api/v1/sync/status")).path("missingNumbers").toString())
                .isEqualTo("[\"R1-000002\"]");
    }

    @Test
    void syn07Syn08ADeviceClockFarOffAndALateBillAreFlagged() {
        SyncTill till = till("Skew");
        UUID late = UUID.randomUUID();

        json(till.upload(
                NOW.plus(Duration.ofMinutes(10)), till.bill(late, 1, "1", null, NOW.minus(Duration.ofHours(100)))));

        JsonNode status = json(till.get("/api/v1/sync/status"));
        assertThat(status.path("clockSkewSeconds").asLong()).isEqualTo(600);
        assertThat(status.path("flags").toString()).contains("CLOCK_SKEW");
        assertThat(json(till.client.get("/api/v1/bills/" + late, till.owner))
                        .path("flags")
                        .toString())
                .contains("LATE_SYNC");
        json(till.upload(NOW.plusSeconds(30), till.bill(UUID.randomUUID(), 2, "1", null, NOW)));
        assertThat(json(till.get("/api/v1/sync/status")).path("flags"))
                .as("the clock is right again")
                .isEmpty();
    }
}
