package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.KAROLA;
import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.json;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.api.events.StockReceived;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
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

/** Batches and expiry on openings (INV-14), inactive variants and definitions, corrections, cursors. */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class InventoryEdgeCasesIT {

    static final String MILK = """
            {"code": "MILK", "name": "Milk", "template": "SIMPLE", "tracking": {"batches": true, "expiry": true}}
            """;
    static final String YOGURT = """
            {"code": "YOGURT", "name": "Yogurt", "template": "SIMPLE", "tracking": {"batches": true}}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    private static String opening(InventoryApi api, JsonNode variant, String extra) {
        return "{\"locationId\": \"" + api.location + "\", \"occurredAt\": \"2026-11-23T08:00:00Z\", \"lines\": [{"
                + "\"variantId\": \"" + variant.path("id").asString() + "\", \"qty\": \"10\", \"unitCost\": \"100.00\""
                + extra + "}]}";
    }

    @Test
    void inv14BatchTrackedOpeningsNeedTheirExpiryAndGetABatch() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Batches");
        JsonNode milk = variant(api.product(MILK), "MILK");
        JsonNode yogurt = variant(api.product(YOGURT), "YOGURT");

        assertThatProblem(api.post("/api/v1/stock/opening-balances", opening(api, milk, "")))
                .hasStatus(422)
                .hasCode("EXPIRY_REQUIRED");
        JsonNode lot = json(api.post(
                        "/api/v1/stock/opening-balances",
                        opening(api, milk, ", \"batchCode\": \"LOT-7\", \"expiresOn\": \"2026-12-01\"")))
                .path("movements")
                .get(0);
        JsonNode auto = json(api.post("/api/v1/stock/opening-balances", opening(api, yogurt, "")))
                .path("movements")
                .get(0);

        assertThat(lot.path("batchId").asString()).isNotBlank();
        assertThat(lot.path("occurredAt").asString()).startsWith("2026-11-23T08:00");
        assertThat(api.<String>sql(j -> j.sql("SELECT code FROM inventory.batch WHERE id = :id")
                        .param("id", UUID.fromString(lot.path("batchId").asString()))
                        .query(String.class)
                        .single()))
                .isEqualTo("LOT-7");
        assertThat(api.<String>sql(j -> j.sql("SELECT code FROM inventory.batch WHERE id = :id")
                        .param("id", UUID.fromString(auto.path("batchId").asString()))
                        .query(String.class)
                        .single()))
                .as("a code is generated when none is given")
                .startsWith("B20261123-");
        MvcTestResult wasted = api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "EXPIRED", "occurredAt": "2026-11-23T10:00:00Z",
                 "lines": [{"variantId": "%s", "batchId": "%s", "qty": "2"}]}
                """.formatted(
                api.location, milk.path("id").asString(), lot.path("batchId").asString()));
        assertThat(json(wasted).path("movements").get(0).path("batchId").asString())
                .isEqualTo(lot.path("batchId").asString());
        String count = "/api/v1/stock/counts/"
                + json(api.post("/api/v1/stock/counts", "{\"locationId\": \"" + api.location + "\"}"))
                        .path("id")
                        .asString();
        MvcTestResult counted = api.put(
                count + "/lines",
                """
                {"lines": [{"variantId": "%s", "batchId": "%s", "countedQty": "8"}]}
                """.formatted(milk.path("id").asString(), lot.path("batchId").asString()),
                ApiClient.etag(api.get(count)));
        api.post(count + "/submit", "");
        JsonNode posted = json(api.post(count + "/post", ""));
        assertThat(posted.path("lines").get(0).path("varianceQty").asString())
                .as("counted what the system holds")
                .isEqualTo("0");
        assertThat(json(counted).path("lines").get(0).path("batchId").asString())
                .isEqualTo(lot.path("batchId").asString());
    }

    @Test
    void stockIssuedBelowZeroIsTruedUpWhenTheOpeningArrives() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "True up");
        JsonNode karola = variant(api.product(KAROLA), "KAROLA");
        String id = karola.path("id").asString();
        api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "DAMAGED", "lines": [{"variantId": "%s", "qty": "100"}]}
                """.formatted(api.location, id));

        JsonNode movements = json(api.post(
                        "/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "1", "unitCost": "2200.00"}]}
                """.formatted(api.location, id, unit(karola, "kg"))))
                .path("movements");

        assertThat(movements).hasSize(2);
        assertThat(movements.get(1).path("type").asString()).isEqualTo("CORRECTION");
        assertThat(movements.get(1).path("costAmount").path("amount").asString())
                .as("100 g wasted at no cost, worth 220.00")
                .isEqualTo("-220.00");
        assertThat(events.stream(StockReceived.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.corrections()).hasSize(1));
    }

    @Test
    void inactiveVariantsAndRetiredDefinitionsTakeNoNewStock() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Inactive");
        JsonNode oil = variant(api.product(OIL), "OIL");
        String id = oil.path("id").asString();
        String open = json(api.client.get("/api/v1/transformation-definitions?kind=OPEN&variantId=" + id, api.owner))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        api.client.action("/api/v1/transformation-definitions/" + open + "/retire", api.owner);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<String>sql(j -> j.sql(
                                        "SELECT status FROM inventory.transformation_def_ref WHERE definition_id = :d")
                                .param("d", UUID.fromString(open))
                                .query(String.class)
                                .single()))
                        .isEqualTo("RETIRED"));

        assertThatProblem(api.post(
                        "/api/v1/stock/transformations",
                        "{\"definitionId\": \"" + open + "\", \"locationId\": \"" + api.location
                                + "\", \"runs\": \"1\"," + " \"occurredAt\": \"2026-11-23T08:00:00Z\"}"))
                .hasStatus(409)
                .hasCode("TRANSFORMATION_DEFINITION_INACTIVE");

        api.client.action("/api/v1/variants/" + id + "/deactivate", api.owner);
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<String>sql(
                                j -> j.sql("SELECT status FROM inventory.stock_item WHERE variant_id = :v")
                                        .param("v", UUID.fromString(id))
                                        .query(String.class)
                                        .single()))
                        .isEqualTo("INACTIVE"));
        assertThatProblem(api.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "DAMAGED", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(api.location, id)))
                .hasStatus(409)
                .hasCode("STOCK_ITEM_INACTIVE");
        assertThatProblem(api.get("/api/v1/stock/counts?cursor=bm90LWEtdXVpZA"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
        assertThatProblem(api.post("/api/v1/stock/transfers", """
                        {"fromLocationId": "%s", "toLocationId": "%s", "occurredAt": "2026-11-23T08:00:00Z",
                         "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(api.location, UUID.randomUUID(), id)))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
    }
}
