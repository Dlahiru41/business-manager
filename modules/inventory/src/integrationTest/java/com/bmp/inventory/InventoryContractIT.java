package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.json;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockCountPosted;
import com.bmp.inventory.api.events.StockLevelLow;
import com.bmp.inventory.api.events.StockReceived;
import com.bmp.inventory.api.events.StockTransformed;
import com.bmp.shared.event.DomainEvent;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ApiClient;
import com.bmp.test.web.ContractSecurity;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Inventory keeps its contracts: every operation protected as x-permission says; its events as AsyncAPI says. */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class InventoryContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Inventory security");
        String nobody =
                JwtTestTokens.builder().tenant(api.tenant).roles("NOBODY").build();
        ContractSecurity.assertEveryOperationIsProtected("inventory", mvc, context, nobody);
    }

    @Test
    void inventoryEventsConformToTheAsyncApiContract() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Inventory events");
        JsonNode oil = variant(api.product(OIL), "OIL");
        api.put(
                "/api/v1/variants/" + id(oil) + "/reorder-policy",
                "{\"locationId\": \"" + api.location + "\", \"reorderLevel\": \"180000\", \"reorderQty\": \"40000\"}",
                null);
        api.post("/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "10",
                 "unitCost": "13800.00"}]}
                """.formatted(api.location, id(oil), unit(oil, "can")));
        api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "DAMAGED", "lines": [{"variantId": "%s", "packagingUnitId": "%s",
                 "qty": "1"}]}
                """.formatted(api.location, id(oil), unit(oil, "can")));
        String open = json(api.client.get(
                        "/api/v1/transformation-definitions?kind=OPEN&variantId=" + id(oil), api.owner))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        api.post("/api/v1/stock/transformations", """
                {"definitionId": "%s", "locationId": "%s", "runs": "1"}
                """.formatted(open, api.location));
        String count = "/api/v1/stock/counts/"
                + json(api.post("/api/v1/stock/counts", "{\"locationId\": \"" + api.location + "\"}"))
                        .path("id")
                        .asString();
        api.put(
                count + "/lines",
                "{\"lines\": [{\"variantId\": \"" + id(oil)
                        + "\", \"stockState\": \"OPEN\", \"countedQty\": \"19000\"}]}",
                ApiClient.etag(api.get(count)));
        api.post(count + "/submit", "");
        api.post(count + "/post", "");

        conforms(StockReceived.class, "StockReceived");
        conforms(StockAdjusted.class, "StockAdjusted");
        conforms(StockTransformed.class, "StockTransformed");
        conforms(StockLevelLow.class, "StockLevelLow");
        conforms(StockCountPosted.class, "StockCountPosted");
    }

    private <E extends DomainEvent> void conforms(Class<E> type, String schema) {
        List<E> published = events.stream(type).toList();
        assertThat(published).as(schema).isNotEmpty();
        published.forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), schema));
    }
}
