package com.bmp.inventory;

import static com.bmp.inventory.InventoryApi.EGGS;
import static com.bmp.inventory.InventoryApi.OIL;
import static com.bmp.inventory.InventoryApi.id;
import static com.bmp.inventory.InventoryApi.json;
import static com.bmp.inventory.InventoryApi.unit;
import static com.bmp.inventory.InventoryApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.procurement.api.spi.ReorderSuggestion;
import com.bmp.procurement.api.spi.ReorderSuggestionProvider;
import com.bmp.shared.id.PartyId;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.web.ApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** Balances, the ledger, valuation, reorder levels and the balance rebuild through the API (T02-07, INV-13, INV-16). */
@SpringBootTest(classes = InventoryTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class StockQueriesIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ReorderSuggestionProvider suggestions;

    /** A shop with oil cans and eggs opened at 07:00, a broken can at 08:30. */
    static final class Stocked {
        final InventoryApi api;
        final JsonNode oil;
        final JsonNode small;
        final JsonNode medium;
        final JsonNode large;

        Stocked(MockMvcTester mvc, JdbcClient jdbc, PlatformTransactionManager transactions, String name) {
            this.api = new InventoryApi(mvc, jdbc, transactions, name);
            this.oil = variant(api.product(OIL), "OIL");
            JsonNode eggs = api.product(EGGS);
            this.small = variant(eggs, "EGG-RED-S");
            this.medium = variant(eggs, "EGG-RED-M");
            this.large = variant(eggs, "EGG-RED-L");
            assertThat(api.post("/api/v1/stock/opening-balances", """
                            {"locationId": "%s", "occurredAt": "2026-11-23T07:00:00Z", "lines": [
                              {"variantId": "%s", "packagingUnitId": "%s", "qty": "10", "unitCost": "13800.00"},
                              {"variantId": "%s", "qty": "300", "unitCost": "38.00"},
                              {"variantId": "%s", "packagingUnitId": "%s", "qty": "10", "unitCost": "1260.00"}]}
                            """.formatted(
                            api.location, id(oil), unit(oil, "can"), id(small), id(medium), unit(medium, "tray"))))
                    .hasStatus(201);
            assertThat(api.post("/api/v1/stock/adjustments", """
                            {"locationId": "%s", "reason": "DAMAGED", "occurredAt": "2026-11-23T08:30:00Z",
                             "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "1"},
                                       {"variantId": "%s", "qty": "5"}]}
                            """.formatted(
                                    api.location, id(oil), unit(oil, "can"), id(large))))
                    .hasStatus(201);
        }

        MvcTestResult policy(JsonNode variant, String body, String ifMatch) {
            return InventoryApi.checked(
                    api.client.put("/api/v1/variants/" + id(variant) + "/reorder-policy", api.owner, body, ifMatch));
        }
    }

    @Test
    void balancesShowStockValueAndReorderStateAndArePagedInKeyOrder() {
        Stocked shop = new Stocked(mvc, jdbc, transactions, "Balances");
        InventoryApi api = shop.api;
        shop.policy(
                shop.small,
                "{\"locationId\": \"" + api.location + "\", \"reorderLevel\": \"400\", " + "\"reorderQty\": \"600\"}",
                null);

        JsonNode oil = json(api.get("/api/v1/stock/balances?variantId=" + id(shop.oil)))
                .path("items");
        assertThat(oil).singleElement().satisfies(b -> {
            assertThat(b.path("stockState").asString()).isEqualTo("SEALED");
            assertThat(b.path("onHand").asString()).isEqualTo("176087.52");
            assertThat(b.path("available").asString()).isEqualTo("176087.52");
            assertThat(b.path("stockValue").path("amount").asString()).isEqualTo("124200.00");
            assertThat(b.path("sku").asString()).isEqualTo("OIL");
            assertThat(b.path("belowReorder").asBoolean()).isFalse();
            assertThat(b.path("negative").asBoolean()).isFalse();
        });
        assertThat(json(api.get("/api/v1/stock/balances?belowReorder=true")).path("items"))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.path("variantId").asString())
                            .isEqualTo(id(shop.small).toString());
                    assertThat(b.path("reorderLevel").asString()).isEqualTo("400");
                    assertThat(b.path("belowReorder").asBoolean()).isTrue();
                });
        assertThat(json(api.get("/api/v1/stock/balances?negative=true")).path("items"))
                .singleElement()
                .satisfies(b -> assertThat(b.path("onHand").asString()).isEqualTo("-5"));
        assertThat(json(api.get("/api/v1/stock/balances?state=OPEN")).path("items"))
                .isEmpty();

        JsonNode first = json(api.get("/api/v1/stock/balances?limit=2&locationId=" + api.location));
        assertThat(first.path("items")).hasSize(2);
        assertThat(first.path("page").path("hasMore").asBoolean()).isTrue();
        JsonNode rest = json(api.get("/api/v1/stock/balances?limit=2&cursor="
                + first.path("page").path("nextCursor").asString()));
        assertThat(rest.path("items")).hasSize(2);
        assertThat(rest.path("page").path("hasMore").asBoolean()).isFalse();
        assertThat(List.of(
                        first.path("items").get(1).path("variantId").asString(),
                        rest.path("items").get(0).path("variantId").asString()))
                .as("key order")
                .isSorted();
        assertThatProblem(api.get("/api/v1/stock/balances?cursor=bm90LWEtdXVpZA"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void movementsAreNewestFirstFilteredAndPaged() {
        Stocked shop = new Stocked(mvc, jdbc, transactions, "Movements");
        InventoryApi api = shop.api;

        JsonNode all = json(api.get("/api/v1/stock/movements?limit=3"));
        assertThat(all.path("items")).hasSize(3);
        assertThat(all.path("items").get(0).path("type").asString()).isEqualTo("WASTAGE");
        assertThat(all.path("items").get(0).path("occurredAt").asString()).startsWith("2026-11-23T08:30");
        JsonNode next = json(api.get("/api/v1/stock/movements?limit=3&cursor="
                + all.path("page").path("nextCursor").asString()));
        assertThat(next.path("items")).hasSize(2);
        assertThat(next.path("items"))
                .allSatisfy(m -> assertThat(m.path("type").asString()).isEqualTo("OPENING"));
        assertThat(json(api.get("/api/v1/stock/movements?type=OPENING&variantId=" + id(shop.oil)))
                        .path("items"))
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.path("qtyBase").asString()).isEqualTo("195652.8");
                    assertThat(m.path("costAmount").path("amount").asString()).isEqualTo("138000.00");
                    assertThat(m.path("sourceType").asString()).isEqualTo("OPENING_BALANCE");
                });
        assertThat(json(api.get("/api/v1/stock/movements?from=2026-11-23T08:00:00Z&to=2026-11-23T09:00:00Z"))
                        .path("items"))
                .hasSize(2);
        assertThat(json(api.get("/api/v1/stock/movements?locationId=" + UUID.randomUUID()))
                        .path("items"))
                .isEmpty();
        assertThatProblem(api.get("/api/v1/stock/movements?cursor=bm90LWEtdGltZQ"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void valuationIsTheSumOfMovementCostsUpToAMoment() {
        Stocked shop = new Stocked(mvc, jdbc, transactions, "Valuation");
        InventoryApi api = shop.api;

        JsonNode before = json(api.get("/api/v1/stock/valuation?asOf=2026-11-23T08:00:00Z"));
        assertThat(before.path("total").path("amount").asString()).isEqualTo("162000.00");
        assertThat(before.path("groupBy").asString()).isEqualTo("variant");
        assertThat(before.path("rows")).hasSize(3);
        JsonNode now = json(api.get("/api/v1/stock/valuation"));
        assertThat(now.path("total").path("amount").asString())
                .as("a can and five large eggs at no cost went since")
                .isEqualTo("148200.00");
        assertThat(now.path("rows"))
                .extracting(r -> r.path("label").asString())
                .contains("OIL", "EGG-RED-S", "EGG-RED-M", "EGG-RED-L");
        JsonNode products = json(api.get("/api/v1/stock/valuation?groupBy=product"));
        assertThat(products.path("rows"))
                .extracting(r -> r.path("label").asString() + "="
                        + r.path("value").path("amount").asString())
                .containsExactly("Cooking oil=124200.00", "Eggs=24000.00");
        assertThat(json(api.get("/api/v1/stock/valuation?groupBy=category")).path("rows"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.path("key").asString()).isEqualTo("00000000-0000-0000-0000-000000000000");
                    assertThat(r.path("value").path("amount").asString()).isEqualTo("148200.00");
                });
        JsonNode locations = json(api.get("/api/v1/stock/valuation?groupBy=location&locationId=" + api.location));
        assertThat(locations.path("rows"))
                .singleElement()
                .satisfies(r -> assertThat(r.path("key").asString()).isEqualTo(api.location.toString()));
        assertThat(locations.path("locationId").asString()).isEqualTo(api.location.toString());
    }

    @Test
    void inv13ReorderLevelsChangeUnderTheirVersionAndFeedTheReorderList() {
        Stocked shop = new Stocked(mvc, jdbc, transactions, "Reorder list");
        InventoryApi api = shop.api;
        UUID supplier = UUID.randomUUID();
        String body = "{\"locationId\": \"" + api.location + "\", \"reorderLevel\": \"%s\", \"reorderQty\": \"600\", "
                + "\"preferredSupplierId\": \"" + supplier + "\"}";

        MvcTestResult created = shop.policy(shop.small, body.formatted("400"), null);
        assertThat(created).hasStatusOk();
        assertThat(json(created).path("version").asLong()).isZero();
        assertThatProblem(shop.policy(shop.small, body.formatted("350"), null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        MvcTestResult changed = shop.policy(shop.small, body.formatted("350"), ApiClient.etag(created));
        assertThat(json(changed).path("reorderLevel").asString()).isEqualTo("350");
        assertThatProblem(shop.policy(shop.small, body.formatted("300"), ApiClient.etag(created)))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
        assertThatProblem(api.client.put(
                        "/api/v1/variants/" + UUID.randomUUID() + "/reorder-policy",
                        api.owner,
                        body.formatted("1"),
                        null))
                .hasStatus(404)
                .hasCode("STOCK_ITEM_NOT_FOUND");
        assertThatProblem(shop.policy(
                        shop.medium,
                        "{\"locationId\": \"" + UUID.randomUUID()
                                + "\", \"reorderLevel\": \"1\", \"reorderQty\": \"1\"}",
                        null))
                .hasStatus(422)
                .hasCode("UNKNOWN_LOCATION");
        shop.policy(
                shop.medium,
                "{\"locationId\": \"" + api.location + "\", \"reorderLevel\": \"100\", " + "\"reorderQty\": \"300\"}",
                null);

        assertThat(json(api.get("/api/v1/stock/reorder-list")).path("items"))
                .singleElement()
                .satisfies(i -> {
                    assertThat(i.path("sku").asString()).isEqualTo("EGG-RED-S");
                    assertThat(i.path("available").asString()).isEqualTo("300");
                    assertThat(i.path("reorderLevel").asString()).isEqualTo("350");
                    assertThat(i.path("reorderQty").asString()).isEqualTo("600");
                    assertThat(i.path("preferredSupplierId").asString()).isEqualTo(supplier.toString());
                });
        assertThat(json(api.get("/api/v1/stock/reorder-list?locationId=" + UUID.randomUUID()))
                        .path("items"))
                .isEmpty();
        List<ReorderSuggestion> suggested = api.inTenant(() -> suggestions.suggestions(null));
        assertThat(suggested).singleElement().satisfies(s -> {
            assertThat(s.variantId().value()).isEqualTo(id(shop.small));
            assertThat(s.preferredSupplierId()).isEqualTo(PartyId.of(supplier));
            assertThat(s.reorderQtyBase()).isEqualByComparingTo("600");
            assertThat(s.lastUnitCostBase()).as("the opening's cost per egg").isEqualByComparingTo("38");
        });
        MvcTestResult raised = shop.policy(
                shop.small,
                "{\"locationId\": \"" + api.location + "\", \"reorderLevel\": \"10\", \"reorderQty\": \"600\"}",
                ApiClient.etag(changed));
        assertThat(json(raised).path("preferredSupplierId").isMissingNode()
                        || json(raised).path("preferredSupplierId").isNull())
                .isTrue();
        assertThat(json(api.get("/api/v1/stock/reorder-list")).path("items")).isEmpty();
    }

    @Test
    void inv16RebuildingFromTheLedgerMatchesTheIncrementalBalancesAndRepairsADrift() {
        Stocked shop = new Stocked(mvc, jdbc, transactions, "Rebuild");
        InventoryApi api = shop.api;
        api.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "BREAKAGE", "lines": [{"variantId": "%s", "qty": "7"}]}
                """.formatted(api.location, id(shop.medium)));

        JsonNode clean = json(api.post("/api/v1/admin/stock/rebuild-balances", ""));
        assertThat(clean.path("differences").asInt()).isZero();
        assertThat(clean.path("balances").asInt()).isEqualTo(4);

        api.sql(j -> j.sql("""
                        UPDATE inventory.stock_balance SET on_hand = on_hand + 1, stock_value = stock_value + 99
                         WHERE variant_id = :v""").param("v", id(shop.medium)).update());
        JsonNode repaired = json(api.post("/api/v1/admin/stock/rebuild-balances", ""));
        assertThat(repaired.path("differences").asInt()).isEqualTo(1);
        assertThat(json(api.get("/api/v1/stock/balances?variantId=" + id(shop.medium)))
                        .path("items"))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.path("onHand").asString()).isEqualTo("293");
                    assertThat(b.path("stockValue").path("amount").asString()).isEqualTo("12306.00");
                });
        assertThatProblem(api.client.post(
                        "/api/v1/admin/stock/rebuild-balances",
                        com.bmp.test.security.JwtTestTokens.forRole(
                                api.tenant, com.bmp.shared.security.SystemRole.STOREKEEPER),
                        ""))
                .hasStatus(403);
    }

    @Test
    void inv16FifoBalancesAndLayersRebuildToTheSameState() {
        InventoryApi api = new InventoryApi(mvc, jdbc, transactions, "Rebuild FIFO");
        var settings = api.client.get("/api/v1/settings/inventory", api.owner);
        assertThat(api.client.patch(
                        "/api/v1/settings/inventory",
                        api.owner,
                        "{\"values\": {\"costingMethod\": \"FIFO\"}}",
                        ApiClient.etag(settings)))
                .hasStatusOk();
        JsonNode small = variant(api.product(EGGS), "EGG-RED-S");
        String adjust = """
                {"locationId": "%s", "reason": "BREAKAGE", "lines": [{"variantId": "%s", "qty": "%s"}]}
                """;
        api.post("/api/v1/stock/adjustments", adjust.formatted(api.location, id(small), "10"));
        api.post("/api/v1/stock/opening-balances", """
                {"locationId": "%s", "lines": [{"variantId": "%s", "qty": "100", "unitCost": "36.00"}]}
                """.formatted(api.location, id(small)));
        api.post("/api/v1/stock/adjustments", adjust.formatted(api.location, id(small), "25"));

        JsonNode rebuilt = json(api.post("/api/v1/admin/stock/rebuild-balances", ""));
        assertThat(rebuilt.path("differences").asInt()).isZero();
        api.sql(j -> j.sql("UPDATE inventory.cost_layer SET qty_remaining = 1 WHERE variant_id = :v")
                .param("v", id(small))
                .update());
        assertThat(json(api.post("/api/v1/admin/stock/rebuild-balances", ""))
                        .path("differences")
                        .asInt())
                .isEqualTo(1);
        assertThat(api.<java.math.BigDecimal>sql(
                        j -> j.sql("SELECT sum(qty_remaining) FROM inventory.cost_layer WHERE variant_id = :v")
                                .param("v", id(small))
                                .query(java.math.BigDecimal.class)
                                .single()))
                .isEqualByComparingTo("65");
    }
}
