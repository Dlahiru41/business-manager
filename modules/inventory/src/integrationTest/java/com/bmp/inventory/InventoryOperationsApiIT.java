package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.KAROLA;
import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.json;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Duration;
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

/** T02-05 through the API: openings, wastage, transfers, transformations and counts (INV-08..12, 15, 17). */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class InventoryOperationsApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    private static String opening(InventoryApi api, JsonNode variant, String unitCode, String qty, String unitCost) {
        return "{\"locationId\": \"" + api.location + "\", \"lines\": [{\"variantId\": \""
                + variant.path("id").asString()
                + "\", \"packagingUnitId\": \"" + unit(variant, unitCode) + "\", \"qty\": \"" + qty
                + "\", \"unitCost\": \"" + unitCost + "\"}]}";
    }

    private static String definition(InventoryApi api, JsonNode variant, String kind) {
        JsonNode items = ApiClient.json(api.client.get(
                        "/api/v1/transformation-definitions?kind=" + kind + "&variantId="
                                + variant.path("id").asString(),
                        api.owner))
                .path("items");
        String id = items.get(0).path("id").asString();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<Long>sql(j -> j.sql(
                                        "SELECT count(*) FROM inventory.transformation_def_ref WHERE definition_id = :d")
                                .param("d", UUID.fromString(id))
                                .query(Long.class)
                                .single()))
                        .isEqualTo(1L));
        return id;
    }

    private static BigDecimal onHand(InventoryApi api, JsonNode variant, String state) {
        return api.sql(j -> j.sql("""
                        SELECT coalesce(sum(on_hand), 0) FROM inventory.stock_balance
                         WHERE variant_id = :v AND stock_state = :s
                        """)
                .param("v", UUID.fromString(variant.path("id").asString()))
                .param("s", state)
                .query(BigDecimal.class)
                .single());
    }

    @Test
    void inv17Inv10AnOpenedCanBecomesOpenOilAtTheSameCost() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Open can");
        JsonNode oil = variant(api.product(OIL), "OIL");
        String open = definition(api, oil, "OPEN");

        MvcTestResult opened = api.post("/api/v1/stock/opening-balances", opening(api, oil, "can", "2", "13800.00"));

        assertThat(opened).hasStatus(201);
        JsonNode movement = json(opened).path("movements").get(0);
        assertThat(movement.path("qtyBase").asString()).isEqualTo("39130.56");
        assertThat(movement.path("costAmount").path("amount").asString()).isEqualTo("27600.00");
        assertThat(movement.path("stockState").asString()).isEqualTo("SEALED");
        assertThat(movement.path("type").asString()).isEqualTo("OPENING");
        assertThatProblem(api.post("/api/v1/stock/opening-balances", opening(api, oil, "can", "1", "1.00")))
                .as("once per variant, location and state")
                .hasStatus(409)
                .hasCode("OPENING_BALANCE_EXISTS");
        assertThat(events.stream(StockReceived.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.sourceType()).isEqualTo("OPENING_BALANCE"));

        MvcTestResult run = api.post("/api/v1/stock/transformations", """
                {"definitionId": "%s", "locationId": "%s",
                 "outputs": [{"variantId": "%s", "stockState": "OPEN", "qtyBase": "19400"}], "note": "Opened at the counter"}
                """.formatted(
                        open, api.location, oil.path("id").asString()));

        assertThat(run).hasStatus(201);
        JsonNode body = json(run);
        assertThat(body.path("inputs").get(0).path("qtyBase").asString()).isEqualTo("19565.28");
        assertThat(body.path("inputs").get(0).path("costAmount").path("amount").asString())
                .isEqualTo("13800.00");
        assertThat(body.path("outputs").get(0).path("actualQtyBase").asString()).isEqualTo("19400");
        assertThat(body.path("outputs").get(0).path("costAmount").path("amount").asString())
                .isEqualTo("13800.00");
        assertThat(body.path("outputs").get(0).path("unitCost").asString())
                .as("13800 / 19400")
                .isEqualTo("0.71134");
        assertThat(body.path("yieldVarianceQty").asString()).isEqualTo("-165.28");
        assertThat(onHand(api, oil, "SEALED")).isEqualByComparingTo("19565.28");
        assertThat(onHand(api, oil, "OPEN")).isEqualByComparingTo("19400");
        String runPath = "/api/v1/stock/transformations/" + body.path("id").asString();
        assertThat(json(api.get(runPath)).path("totalCost").path("amount").asString())
                .isEqualTo("13800.00");
        assertThat(json(api.get("/api/v1/stock/transformations?definitionId=" + open))
                        .path("items"))
                .hasSize(1);
        assertThat(events.stream(StockTransformed.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.totalCost()).isEqualByComparingTo("13800.00"));
        assertThatProblem(api.get("/api/v1/stock/transformations/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("TRANSFORMATION_RUN_NOT_FOUND");
        assertThatProblem(api.post("/api/v1/stock/transformations", """
                        {"definitionId": "%s", "locationId": "%s",
                         "outputs": [{"variantId": "%s", "stockState": "SEALED", "qtyBase": "1"}]}
                        """.formatted(
                                open, api.location, oil.path("id").asString())))
                .hasStatus(422)
                .hasCode("INVALID_OUTPUTS");
        assertThatProblem(api.post(
                        "/api/v1/stock/transformations",
                        "{\"definitionId\": \"" + UUID.randomUUID() + "\", \"locationId\": \"" + api.location + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_TRANSFORMATION_DEFINITION");
    }

    @Test
    void inv09LooseKarolaIsPackedIntoFourPacketsWithItsCost() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Repack");
        JsonNode karola = variant(api.product(KAROLA), "KAROLA");
        String repack = definition(api, karola, "REPACK");
        api.post("/api/v1/stock/opening-balances", opening(api, karola, "kg", "2", "2200.00"));

        JsonNode run = json(api.post("/api/v1/stock/transformations", """
                {"definitionId": "%s", "locationId": "%s", "runs": "4"}
                """.formatted(repack, api.location)));

        assertThat(run.path("inputs").get(0).path("qtyBase").asString()).isEqualTo("1000");
        assertThat(run.path("inputs").get(0).path("costAmount").path("amount").asString())
                .isEqualTo("2200.00");
        assertThat(run.path("outputs").get(0).path("stockState").asString()).isEqualTo("PACKED_PACKET_250");
        assertThat(run.path("outputs").get(0).path("actualQtyBase").asString())
                .as("four 250 g packets")
                .isEqualTo("1000");
        assertThat(run.path("outputs").get(0).path("costAmount").path("amount").asString())
                .isEqualTo("2200.00");
        assertThat(run.path("yieldVarianceQty").asString()).isEqualTo("0");
        assertThat(onHand(api, karola, "DEFAULT")).isEqualByComparingTo("1000");
    }

    @Test
    void inv11Inv08WastageLeavesAtCurrentCostAndABlockingPolicyRefusesNegativeStock() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Wastage");
        JsonNode karola = variant(api.product(KAROLA), "KAROLA");
        api.post("/api/v1/stock/opening-balances", opening(api, karola, "kg", "1", "2200.00"));
        String id = karola.path("id").asString();

        MvcTestResult wasted = api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "SPOILAGE", "lines": [{"variantId": "%s", "qty": "100"}]}
                """.formatted(api.location, id));

        assertThat(wasted).hasStatus(201);
        JsonNode movement = json(wasted).path("movements").get(0);
        assertThat(movement.path("type").asString()).isEqualTo("WASTAGE");
        assertThat(movement.path("reasonCode").asString()).isEqualTo("SPOILAGE");
        assertThat(movement.path("costAmount").path("amount").asString()).isEqualTo("-220.00");
        assertThat(events.stream(StockAdjusted.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.reason()).isEqualTo("SPOILAGE"));
        assertThatProblem(api.client.post("/api/v1/stock/adjustments", api.owner, """
                        {"locationId": "%s", "reason": "OTHER", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(api.location, id)))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThat(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "OTHER", "note": "Given to a neighbour",
                         "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(api.location, id)))
                .hasStatus(201);

        var settings = api.client.get("/api/v1/settings/inventory", api.owner);
        api.client.patch(
                "/api/v1/settings/inventory",
                api.owner,
                "{\"values\": {\"negativeStockPolicy\": \"BLOCK\"}}",
                ApiClient.etag(settings));
        assertThatProblem(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "THEFT", "lines": [{"variantId": "%s", "qty": "5000"}]}
                        """.formatted(api.location, id)))
                .hasStatus(409)
                .hasCode("INSUFFICIENT_STOCK");
        assertThat(onHand(api, karola, "DEFAULT")).as("nothing was posted").isEqualByComparingTo("899");
        assertThatProblem(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "THEFT", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(UUID.randomUUID(), id)))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
        assertThatProblem(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "THEFT", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(api.location, UUID.randomUUID())))
                .hasStatus(422)
                .hasCode("UNKNOWN_VARIANT");
        assertThatProblem(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "THEFT",
                         "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "1"}]}
                        """.formatted(api.location, id, UUID.randomUUID())))
                .hasStatus(422)
                .hasCode("UNKNOWN_PACKAGING_UNIT");
        assertThatProblem(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "THEFT", "lines": [{"variantId": "%s", "batchId": "%s", "qty": "1"}]}
                        """.formatted(api.location, id, UUID.randomUUID())))
                .hasStatus(422)
                .hasCode("UNKNOWN_BATCH");
    }

    @Test
    void inv15TransfersMoveStockAndItsCostBetweenLocations() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Transfer");
        MvcTestResult store =
                api.client.post("/api/v1/locations", api.owner, "{\"name\": \"Store room\", \"type\": \"STORE\"}");
        UUID storeId = ApiClient.id(store);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM inventory.location_ref WHERE location_id = :l")
                                        .param("l", storeId)
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(1L));
        JsonNode oil = variant(api.product(OIL), "OIL");
        api.post("/api/v1/stock/opening-balances", opening(api, oil, "can", "2", "13800.00"));

        MvcTestResult moved = api.post("/api/v1/stock/transfers", """
                {"fromLocationId": "%s", "toLocationId": "%s",
                 "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "1"}]}
                """.formatted(
                        api.location, storeId, oil.path("id").asString(), unit(oil, "can")));

        assertThat(moved).hasStatus(201);
        JsonNode movements = json(moved).path("movements");
        assertThat(movements).hasSize(2);
        assertThat(movements.get(0).path("type").asString()).isEqualTo("TRANSFER_OUT");
        assertThat(movements.get(0).path("costAmount").path("amount").asString())
                .isEqualTo("-13800.00");
        assertThat(movements.get(1).path("type").asString()).isEqualTo("TRANSFER_IN");
        assertThat(movements.get(1).path("locationId").asString()).isEqualTo(storeId.toString());
        assertThat(movements.get(1).path("costAmount").path("amount").asString())
                .isEqualTo("13800.00");
        assertThatProblem(api.post("/api/v1/stock/transfers", """
                        {"fromLocationId": "%s", "toLocationId": "%s", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(
                                api.location, api.location, oil.path("id").asString())))
                .hasStatus(422)
                .hasCode("TRANSFER_SAME_LOCATION");
    }

    @Test
    void inv12ACountBooksCountedMinusTheSnapshotAndMovementsSinceStayBooked() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Count");
        JsonNode karola = variant(api.product(KAROLA), "KAROLA");
        String id = karola.path("id").asString();
        api.post("/api/v1/stock/opening-balances", opening(api, karola, "kg", "1", "2200.00"));
        MvcTestResult created = api.post(
                "/api/v1/stock/counts", "{\"locationId\": \"" + api.location + "\", \"variantIds\": [\"" + id + "\"]}");
        String path = "/api/v1/stock/counts/" + json(created).path("id").asString();
        String line = "{\"lines\": [{\"variantId\": \"" + id + "\", \"countedQty\": \"950\"}]}";

        MvcTestResult counted = api.put(path + "/lines", line, ApiClient.etag(created));
        assertThat(json(counted).path("lines").get(0).path("systemQty").asString())
                .as("the snapshot of the count")
                .isEqualTo("1000");
        api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "SPILLAGE", "lines": [{"variantId": "%s", "qty": "100"}]}
                """.formatted(api.location, id));
        assertThatProblem(api.post(path + "/post", "")).hasStatus(409).hasCode("COUNT_STATE_INVALID");
        assertThat(api.post(path + "/submit", "")).hasStatusOk();
        assertThatProblem(api.put(path + "/lines", line, ApiClient.etag(api.get(path))))
                .hasStatus(409)
                .hasCode("COUNT_STATE_INVALID");

        MvcTestResult posted = api.post(path + "/post", "");

        assertThat(posted).hasStatusOk();
        JsonNode result = json(posted).path("lines").get(0);
        assertThat(result.path("varianceQty").asString()).isEqualTo("-50");
        assertThat(result.path("varianceCost").path("amount").asString()).isEqualTo("-110.00");
        assertThat(onHand(api, karola, "DEFAULT"))
                .as("950 counted, then 100 spilt")
                .isEqualByComparingTo("850");
        assertThat(events.stream(StockCountPosted.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.totalVarianceCost()).isEqualByComparingTo("-110.00"));
        assertThatProblem(api.post(path + "/cancel", "")).hasStatus(409).hasCode("COUNT_ALREADY_POSTED");
        assertThat(json(api.get("/api/v1/stock/counts?status=POSTED")).path("items"))
                .hasSize(1);
        String other = "/api/v1/stock/counts/"
                + json(api.post("/api/v1/stock/counts", "{\"locationId\": \"" + api.location + "\"}"))
                        .path("id")
                        .asString();
        assertThat(json(api.post(other + "/cancel", "")).path("status").asString())
                .isEqualTo("CANCELLED");
        assertThatProblem(api.get("/api/v1/stock/counts/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("STOCK_COUNT_NOT_FOUND");
        assertThatProblem(api.put(path + "/lines", line, "W/\"0\""))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
    }
}
