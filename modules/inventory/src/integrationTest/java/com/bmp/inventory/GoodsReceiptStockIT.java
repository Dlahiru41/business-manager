package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.EGGS;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.InventoryTestApplication.StockEventRecorder;
import com.bmp.procurement.api.events.GoodsReceived;
import com.bmp.procurement.api.events.GoodsReceivedLine;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.LocationId;
import com.bmp.shared.id.PackagingUnitId;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.id.VariantId;
import com.bmp.test.persistence.WithPostgres;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Posted goods receipts become stock (T02-06, PRO-04, INV-03, INV-14): one RECEIPT movement per line at its line
 * total, returns at current cost, batches for tracked variants, once per event however often it is delivered.
 */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class GoodsReceiptStockIT {

    static final Instant RECEIVED = Instant.parse("2026-11-23T07:30:00Z");
    static final String MILK = """
            {"code": "MILK", "name": "Milk", "template": "SIMPLE", "tracking": {"batches": true, "expiry": true}}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    StockEventRecorder recorded;

    static GoodsReceivedLine line(
            JsonNode variant,
            String unitCode,
            String qty,
            String qtyInBase,
            String unitCost,
            @Nullable String batchCode,
            @Nullable LocalDate expiresOn) {
        BigDecimal quantity = new BigDecimal(qty);
        BigDecimal cost = new BigDecimal(unitCost);
        BigDecimal size = new BigDecimal(qtyInBase);
        return new GoodsReceivedLine(
                UUID.randomUUID(),
                null,
                VariantId.of(id(variant)),
                PackagingUnitId.of(UUID.fromString(unit(variant, unitCode))),
                quantity,
                quantity.multiply(size),
                "DEFAULT",
                cost,
                cost.divide(size, 6, java.math.RoundingMode.HALF_UP),
                quantity.multiply(cost).setScale(2, java.math.RoundingMode.HALF_UP),
                batchCode,
                expiresOn);
    }

    static GoodsReceived receipt(InventoryApi api, @Nullable UUID corrects, List<GoodsReceivedLine> lines) {
        return receipt(api, corrects, RECEIVED, lines);
    }

    static GoodsReceived receipt(
            InventoryApi api, @Nullable UUID corrects, Instant receivedAt, List<GoodsReceivedLine> lines) {
        UUID receiptId = UUID.randomUUID();
        return new GoodsReceived(
                UUID.randomUUID(),
                api.tenant,
                RECEIVED,
                receiptId,
                1,
                EventMetadata.empty(),
                receiptId,
                "GRN-000001",
                PartyId.of(UUID.randomUUID()),
                LocationId.of(api.location),
                null,
                corrects,
                receivedAt,
                "LKR",
                lines.stream().map(GoodsReceivedLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add),
                lines);
    }

    void publish(InventoryApi api, GoodsReceived event) {
        TenantContext.runWith(
                api.tenant,
                () -> new TransactionTemplate(transactions).executeWithoutResult(s -> publisher.publishEvent(event)));
    }

    List<Map<String, Object>> movements(InventoryApi api, UUID receiptId, int expected) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(this.movements(api, receiptId)).hasSize(expected));
        return movements(api, receiptId);
    }

    List<Map<String, Object>> movements(InventoryApi api, UUID receiptId) {
        return api.sql(j -> j.sql("""
                        SELECT type, qty_base, cost_amount, reason_code, source_type, batch_id, occurred_at
                        FROM inventory.stock_movement WHERE source_id = :r ORDER BY qty_base DESC, type""").param("r", receiptId).query().listOfRows());
    }

    BigDecimal onHand(InventoryApi api, JsonNode variant) {
        return api.sql(j -> j.sql("SELECT coalesce(sum(on_hand), 0) FROM inventory.stock_balance WHERE variant_id = :v")
                .param("v", id(variant))
                .query(BigDecimal.class)
                .single());
    }

    BigDecimal value(InventoryApi api, JsonNode variant) {
        return api.sql(
                j -> j.sql("SELECT coalesce(sum(stock_value), 0) FROM inventory.stock_balance WHERE variant_id = :v")
                        .param("v", id(variant))
                        .query(BigDecimal.class)
                        .single());
    }

    @Test
    void pro04Inv03TheEggDeliveryBecomesReceiptMovementsAtItsLineTotalsOnce() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Egg receipt");
        JsonNode eggs = api.product(EGGS);
        JsonNode small = variant(eggs, "EGG-RED-S");
        JsonNode medium = variant(eggs, "EGG-RED-M");
        JsonNode large = variant(eggs, "EGG-RED-L");
        GoodsReceived delivery = receipt(
                api,
                null,
                List.of(
                        line(small, "piece", "300", "1", "38.00", null, null),
                        line(medium, "tray", "10", "30", "1260.00", null, null),
                        line(large, "piece", "260", "1", "46.50", null, null)));

        publish(api, delivery);
        List<Map<String, Object>> posted = movements(api, delivery.receiptId(), 3);
        publish(api, delivery);

        assertThat(posted).allSatisfy(m -> {
            assertThat(m.get("type")).isEqualTo("RECEIPT");
            assertThat(m.get("source_type")).isEqualTo("GOODS_RECEIPT");
            assertThat(m.get("reason_code")).isNull();
        });
        assertThat(posted)
                .extracting(m -> ((BigDecimal) m.get("cost_amount")).toPlainString())
                .containsExactly("11400.0000", "12600.0000", "12090.0000");
        assertThat(onHand(api, medium)).isEqualByComparingTo("300");
        assertThat(value(api, medium)).isEqualByComparingTo("12600.00");
        assertThat(api.<BigDecimal>sql(
                        j -> j.sql("SELECT avg_unit_cost FROM inventory.stock_balance WHERE variant_id = :v")
                                .param("v", id(large))
                                .query(BigDecimal.class)
                                .single()))
                .isEqualByComparingTo("46.50");
        assertThat(posted.getFirst().get("occurred_at").toString()).startsWith("2026-11-23 07:30");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(recorded.received(api.tenant))
                        .singleElement()
                        .satisfies(e -> {
                            assertThat(e.sourceType()).isEqualTo("GOODS_RECEIPT");
                            assertThat(e.sourceId()).isEqualTo(delivery.receiptId());
                            assertThat(e.lines()).hasSize(3);
                        }));
        assertThat(movements(api, delivery.receiptId()))
                .as("the second delivery of the same event changed nothing")
                .hasSize(3);
        assertThat(onHand(api, small)).isEqualByComparingTo("300");
    }

    @Test
    void aReturnToTheSupplierLeavesAtCurrentCostAndMayGoBelowZero() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Supplier return");
        JsonNode small = variant(api.product(EGGS), "EGG-RED-S");
        GoodsReceived first = receipt(api, null, List.of(line(small, "piece", "100", "1", "36.00", null, null)));
        GoodsReceived second = receipt(api, null, List.of(line(small, "piece", "100", "1", "40.00", null, null)));
        publish(api, first);
        movements(api, first.receiptId(), 1);
        publish(api, second);
        movements(api, second.receiptId(), 1);

        GoodsReceived back =
                receipt(api, first.receiptId(), List.of(line(small, "piece", "-50", "1", "36.00", null, null)));
        publish(api, back);
        Map<String, Object> returned = movements(api, back.receiptId(), 1).getFirst();

        assertThat(returned.get("type")).isEqualTo("RECEIPT");
        assertThat(returned.get("reason_code")).isEqualTo("RETURN_TO_SUPPLIER");
        assertThat((BigDecimal) returned.get("cost_amount"))
                .as("50 at the moving average of 38.00, not the 36.00 they cost")
                .isEqualByComparingTo("-1900.00");
        assertThat(onHand(api, small)).isEqualByComparingTo("150");
        assertThat(value(api, small)).isEqualByComparingTo("5700.00");

        GoodsReceived tooMany =
                receipt(api, first.receiptId(), List.of(line(small, "piece", "-200", "1", "36.00", null, null)));
        publish(api, tooMany);
        movements(api, tooMany.receiptId(), 1);
        assertThat(onHand(api, small)).as("goods that left are recorded").isEqualByComparingTo("-50");
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(recorded.received(api.tenant))
                        .filteredOn(e -> e.sourceId().equals(back.receiptId()))
                        .singleElement()
                        .satisfies(
                                e -> assertThat(e.lines().getFirst().qtyBase()).isEqualByComparingTo("-50")));
    }

    @Test
    void inv14TrackedVariantsReceiveIntoTheNamedOrAGeneratedBatchEvenWhenInactive() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Receipt batches");
        JsonNode milk = variant(api.product(MILK), "MILK");
        GoodsReceived named = receipt(
                api, null, List.of(line(milk, "piece", "24", "1", "180.00", "LOT-9", LocalDate.parse("2026-12-01"))));
        publish(api, named);
        Map<String, Object> lot = movements(api, named.receiptId(), 1).getFirst();

        assertThat(api.<String>sql(j -> j.sql("SELECT code FROM inventory.batch WHERE id = :id")
                        .param("id", lot.get("batch_id"))
                        .query(String.class)
                        .single()))
                .isEqualTo("LOT-9");

        api.client.action("/api/v1/variants/" + id(milk) + "/deactivate", api.owner);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<String>sql(
                                j -> j.sql("SELECT status FROM inventory.stock_item WHERE variant_id = :v")
                                        .param("v", id(milk))
                                        .query(String.class)
                                        .single()))
                        .isEqualTo("INACTIVE"));
        GoodsReceived generated = receipt(
                api, null, List.of(line(milk, "piece", "12", "1", "180.00", null, LocalDate.parse("2026-12-05"))));
        publish(api, generated);
        Map<String, Object> auto = movements(api, generated.receiptId(), 1).getFirst();

        assertThat(api.<String>sql(j -> j.sql("SELECT code FROM inventory.batch WHERE id = :id")
                        .param("id", auto.get("batch_id"))
                        .query(String.class)
                        .single()))
                .startsWith("B20261123-");
        assertThat(onHand(api, milk)).isEqualByComparingTo("36");
    }

    @Test
    void inv16FifoLayersFollowPostingOrderSoARebuildChangesNothing() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "FIFO out of order");
        var settings = api.client.get("/api/v1/settings/inventory", api.owner);
        assertThat(api.client.patch(
                        "/api/v1/settings/inventory",
                        api.owner,
                        "{\"values\": {\"costingMethod\": \"FIFO\"}}",
                        com.bmp.test.web.ApiClient.etag(settings)))
                .hasStatusOk();
        JsonNode small = variant(api.product(EGGS), "EGG-RED-S");
        GoodsReceived later = receipt(
                api,
                null,
                Instant.parse("2026-11-23T08:30:00Z"),
                List.of(line(small, "piece", "100", "1", "36.00", null, null)));
        GoodsReceived earlier = receipt(
                api,
                null,
                Instant.parse("2026-11-23T08:00:00Z"),
                List.of(line(small, "piece", "100", "1", "40.00", null, null)));
        publish(api, later);
        movements(api, later.receiptId(), 1);
        publish(api, earlier);
        movements(api, earlier.receiptId(), 1);

        api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "BREAKAGE", "lines": [{"variantId": "%s", "qty": "150"}]}
                """.formatted(api.location, id(small)));

        assertThat(value(api, small))
                .as("the layer posted first went first: 100 at 36, then 50 at 40")
                .isEqualByComparingTo("2000.00");
        assertThat(InventoryApi.json(api.post("/api/v1/admin/stock/rebuild-balances", ""))
                        .path("differences")
                        .asInt())
                .isZero();
    }

    @Test
    void aReceiptOfAVariantNotReplicatedYetIsRetriedLaterNotParked() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Receipt before variant");
        JsonNode small = variant(api.product(EGGS), "EGG-RED-S");
        GoodsReceivedLine known = line(small, "piece", "10", "1", "38.00", null, null);
        GoodsReceivedLine unknown = new GoodsReceivedLine(
                UUID.randomUUID(),
                null,
                VariantId.of(UUID.randomUUID()),
                known.packagingUnitId(),
                known.qty(),
                known.qtyBase(),
                "DEFAULT",
                known.unitCost(),
                known.unitCostBase(),
                known.lineTotal(),
                null,
                null);
        GoodsReceived early = receipt(api, null, List.of(known, unknown));

        publish(api, early);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(jdbc.sql("""
                                        SELECT count(*) FROM platform.event_publication
                                         WHERE completion_date IS NULL AND listener_id LIKE '%ProcurementListener%'
                                           AND serialized_event LIKE :receipt AND status = 'FAILED'""")
                                .param("receipt", "%" + early.receiptId() + "%")
                                .query(Long.class)
                                .single())
                        .as("delivery failed and waits to be resubmitted")
                        .isEqualTo(1L));
        assertThat(api.<Long>sql(j -> j.sql("SELECT count(*) FROM platform.failed_event WHERE event_id = :e")
                        .param("e", early.eventId())
                        .query(Long.class)
                        .single()))
                .as("not parked: a missing variant is not a reason to refuse posted stock")
                .isZero();
        assertThat(movements(api, early.receiptId()))
                .as("nothing booked yet, the inbox rolled back")
                .isEmpty();
    }
}
