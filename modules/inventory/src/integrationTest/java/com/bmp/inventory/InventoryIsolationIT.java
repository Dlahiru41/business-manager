package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.EGGS;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.json;
import static com.bmp.inventory.InventoryApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.WithPostgres;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** Tenant A's stock is invisible to tenant B through every read and cannot be moved by B (06 par. 10). */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class InventoryIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void anotherTenantSeesNoneOfTheStockAndCannotMoveIt() {
        InventoryApi a = new InventoryApi(mvc, jdbc, transactions, "Inventory isolation A");
        InventoryApi b = new InventoryApi(mvc, jdbc, transactions, "Inventory isolation B");
        JsonNode small = variant(a.product(EGGS), "EGG-RED-S");
        a.post("/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "qty": "300", "unitCost": "38.00"}]}
                """.formatted(a.location, id(small)));
        a.put(
                "/api/v1/variants/" + id(small) + "/reorder-policy",
                "{\"locationId\": \"" + a.location + "\", \"reorderLevel\": \"400\", \"reorderQty\": \"600\"}",
                null);
        String count = json(a.post("/api/v1/stock/counts", "{\"locationId\": \"" + a.location + "\"}"))
                .path("id")
                .asString();

        assertThat(json(b.get("/api/v1/stock/balances")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/stock/movements")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/stock/valuation")).path("rows")).isEmpty();
        assertThat(json(b.get("/api/v1/stock/valuation"))
                        .path("total")
                        .path("amount")
                        .asString())
                .isEqualTo("0.00");
        assertThat(json(b.get("/api/v1/stock/reorder-list")).path("items")).isEmpty();
        assertThatProblem(b.get("/api/v1/stock/counts/" + count)).hasStatus(404);
        assertThatProblem(b.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "DAMAGED", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(b.location, id(small))))
                .hasStatus(422)
                .hasCode("UNKNOWN_VARIANT");
        assertThatProblem(b.post("/api/v1/stock/adjustments", """
                        {"locationId": "%s", "reason": "DAMAGED", "lines": [{"variantId": "%s", "qty": "1"}]}
                        """.formatted(a.location, UUID.randomUUID())))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
        assertThatProblem(b.put(
                        "/api/v1/variants/" + id(small) + "/reorder-policy",
                        "{\"locationId\": \"" + b.location + "\", \"reorderLevel\": \"1\", \"reorderQty\": \"1\"}",
                        null))
                .hasStatus(404)
                .hasCode("STOCK_ITEM_NOT_FOUND");
        assertThat(json(b.post("/api/v1/admin/stock/rebuild-balances", ""))
                        .path("balances")
                        .asInt())
                .as("B's rebuild sees only B's balances")
                .isZero();
        assertThat(b.<Long>sql(j -> j.sql("SELECT count(*) FROM inventory.stock_movement WHERE tenant_id = :a")
                        .param("a", a.tenant.value())
                        .query(Long.class)
                        .single()))
                .isZero();
        assertThat(a.<Long>sql(j -> j.sql("SELECT count(*) FROM inventory.stock_balance")
                        .query(Long.class)
                        .single()))
                .as("A still has its balance")
                .isEqualTo(1L);
    }
}
