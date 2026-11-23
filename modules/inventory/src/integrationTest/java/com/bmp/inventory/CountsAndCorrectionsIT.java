package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.EGGS;
import static com.bmp.inventory.InventoryApi.KAROLA;
import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.json;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.api.events.StockMovementLine;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
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

/**
 * Recounts and batch-tracked counts (INV-12, INV-14), cost true-ups of transformations and transfers into negative
 * stock carried on their events (INV-03, INV-09, INV-15), and quantities that move nothing.
 */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class CountsAndCorrectionsIT {

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
    ApplicationEvents events;

    static BigDecimal onHand(InventoryApi api, JsonNode variant, String state) {
        return api.sql(j -> j.sql("""
                        SELECT coalesce(sum(on_hand), 0) FROM inventory.stock_balance
                         WHERE variant_id = :v AND stock_state = :s""")
                .param("v", id(variant))
                .param("s", state)
                .query(BigDecimal.class)
                .single());
    }

    static BigDecimal totalValue(InventoryApi api) {
        return api.sql(j -> j.sql("SELECT coalesce(sum(stock_value), 0) FROM inventory.stock_balance")
                .query(BigDecimal.class)
                .single());
    }

    static BigDecimal costs(List<StockMovementLine> lines) {
        return lines.stream().map(StockMovementLine::costAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    static String adjustment(InventoryApi api, UUID location, JsonNode variant, String state, String qty) {
        return """
                {"locationId": "%s", "reason": "SPILLAGE", "lines": [{"variantId": "%s", "stockState": "%s", "qty": "%s"}]}
                """.formatted(location, id(variant), state, qty);
    }

    @Test
    void inv12ARecountIsComparedWithTheStockAtTheRecount() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Recount");
        JsonNode karola = variant(api.product(KAROLA), "KAROLA");
        api.post("/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "qty": "1000", "unitCost": "2.20"}]}
                """.formatted(api.location, id(karola)));
        String count = "/api/v1/stock/counts/"
                + json(api.post("/api/v1/stock/counts", "{\"locationId\": \"" + api.location + "\"}"))
                        .path("id")
                        .asString();
        String line = "{\"lines\": [{\"variantId\": \"" + id(karola) + "\", \"countedQty\": \"%s\"}]}";
        api.put(count + "/lines", line.formatted("950"), ApiClient.etag(api.get(count)));

        api.post("/api/v1/stock/adjustments", adjustment(api, api.location, karola, "DEFAULT", "100"));
        JsonNode recounted = json(api.put(count + "/lines", line.formatted("850"), ApiClient.etag(api.get(count))));
        assertThat(recounted.path("lines").get(0).path("systemQty").asString())
                .as("the recount's snapshot")
                .isEqualTo("900");
        api.post(count + "/submit", "");
        JsonNode posted = json(api.post(count + "/post", ""));

        assertThat(posted.path("lines").get(0).path("varianceQty").asString()).isEqualTo("-50");
        assertThat(onHand(api, karola, "DEFAULT"))
                .as("what was physically recounted")
                .isEqualByComparingTo("850");
        assertThatProblem(api.post(count + "/post", "")).hasStatus(409).hasCode("COUNT_ALREADY_POSTED");
    }

    @Test
    void inv14BatchTrackedVariantsAreCountedPerBatch() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Batch count");
        JsonNode milk = variant(api.product(MILK), "MILK");
        String count = "/api/v1/stock/counts/"
                + json(api.post("/api/v1/stock/counts", "{\"locationId\": \"" + api.location + "\"}"))
                        .path("id")
                        .asString();

        assertThatProblem(api.put(
                        count + "/lines",
                        "{\"lines\": [{\"variantId\": \"" + id(milk) + "\", \"countedQty\": \"12\"}]}",
                        ApiClient.etag(api.get(count))))
                .hasStatus(422)
                .hasCode("BATCH_REQUIRED");
    }

    @Test
    void inv09OpeningACanIntoNegativeOpenOilPublishesItsTrueUp() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Negative open oil");
        JsonNode oil = variant(api.product(OIL), "OIL");
        api.post("/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "1",
                 "unitCost": "13800.00"}]}
                """.formatted(api.location, id(oil), unit(oil, "can")));
        api.post("/api/v1/stock/adjustments", adjustment(api, api.location, oil, "OPEN", "500"));
        String open = json(api.client.get(
                        "/api/v1/transformation-definitions?kind=OPEN&variantId=" + id(oil), api.owner))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        BigDecimal before = totalValue(api);

        api.post("/api/v1/stock/transformations", """
                {"definitionId": "%s", "locationId": "%s",
                 "outputs": [{"variantId": "%s", "stockState": "OPEN", "qtyBase": "19400"}]}
                """.formatted(open, api.location, id(oil)));
        BigDecimal change = totalValue(api).subtract(before);

        assertThat(change).as("18,900 ml at 13,800 / 19,400").isEqualByComparingTo("-355.67");
        assertThat(events.stream(StockTransformed.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(costs(e.corrections())).isEqualByComparingTo("-355.67");
                    assertThat(costs(e.inputs()).add(costs(e.outputs())).add(costs(e.corrections())))
                            .as("the event's costs are the change in stock value")
                            .isEqualByComparingTo(change);
                });
    }

    @Test
    void inv15ATransferIntoNegativeStockPublishesItsTrueUp() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Negative transfer");
        JsonNode eggs = variant(api.product(EGGS), "EGG-RED-M");
        MvcTestResult store =
                api.client.post("/api/v1/locations", api.owner, "{\"name\": \"Store room\", \"type\": \"STORE\"}");
        assertThat(store).hasStatus(201);
        UUID room = ApiClient.id(store);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM inventory.location_ref WHERE location_id = :l")
                                        .param("l", room)
                                        .query(Long.class)
                                        .single()))
                        .isEqualTo(1L));
        api.post("/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "qty": "300", "unitCost": "42.00"}]}
                """.formatted(api.location, id(eggs)));
        api.post("/api/v1/stock/adjustments", adjustment(api, room, eggs, "DEFAULT", "10"));
        BigDecimal before = totalValue(api);

        api.post("/api/v1/stock/transfers", """
                {"fromLocationId": "%s", "toLocationId": "%s", "lines": [{"variantId": "%s", "qty": "30"}]}
                """.formatted(api.location, room, id(eggs)));
        BigDecimal change = totalValue(api).subtract(before);

        assertThat(change)
                .as("the ten eggs short in the store room take the transferred cost")
                .isEqualByComparingTo("-420.00");
        assertThat(events.stream(StockReceived.class)
                        .filter(e -> e.tenantId().equals(api.tenant)
                                && e.sourceType().equals("TRANSFER")))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.locationId().value()).isEqualTo(room);
                    assertThat(e.lines())
                            .singleElement()
                            .satisfies(l -> assertThat(l.qtyBase()).isEqualByComparingTo("30"));
                    assertThat(costs(e.corrections())).isEqualByComparingTo(change);
                });
    }

    @Test
    void aQuantityThatMovesNothingIsAValidationError() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Zero quantities");
        JsonNode karola = variant(api.product(KAROLA), "KAROLA");
        String repack = json(api.client.get(
                        "/api/v1/transformation-definitions?kind=REPACK&variantId=" + id(karola), api.owner))
                .path("items")
                .get(0)
                .path("id")
                .asString();

        assertThatProblem(api.post("/api/v1/stock/adjustments", adjustment(api, api.location, karola, "DEFAULT", "0")))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThatProblem(api.post("/api/v1/stock/transformations", """
                        {"definitionId": "%s", "locationId": "%s", "runs": "0"}
                        """.formatted(repack, api.location)))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThatProblem(api.post("/api/v1/stock/opening-balances", """
                        {"locationId": "%s", "lines": [{"variantId": "%s", "qty": "0.000", "unitCost": "1.00"}]}
                        """.formatted(api.location, id(karola))))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
    }
}
