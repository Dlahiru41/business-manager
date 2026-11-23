package com.bmp;

import static com.bmp.test.fixture.PilotShop.json;
import static com.bmp.test.fixture.PilotShop.unit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.inventory.api.events.StockAdjusted;
import com.bmp.inventory.api.events.StockLevelLow;
import com.bmp.shared.context.TenantContext;
import com.bmp.test.fixture.PilotShop;
import com.bmp.test.fixture.PilotShopFixture;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.TestJwtConfiguration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * T02-09: the pilot shop receives eggs, oil and karola from a supplier through procurement, inventory books them,
 * opens a can, packs karola, writes off broken eggs, raises one reorder alert and values the stock. Exact numbers;
 * the oil density (1,086.96 ml/kg, so 19,565.28 ml per can) is the fixture's placeholder.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@WithPostgres
@Import(TestJwtConfiguration.class)
@RecordApplicationEvents
class ReceiveAndTransformFlowIT {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    private PilotShop shop;
    private String location;
    private String supplier;

    @BeforeEach
    void setUp() {
        shop = PilotShopFixture.create(mvc, "Receive and transform " + UUID.randomUUID());
        location = json(shop.get("/api/v1/locations"))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        MvcTestResult created =
                shop.post("/api/v1/parties", "{\"type\": \"SUPPLIER\", \"displayName\": \"Kegalle Poultry Farm\"}");
        assertThat(created).hasStatus(201);
        supplier = json(created).path("id").asString();
        List<String> variants = new ArrayList<>();
        for (String sku : List.of("EGG-RED-S", "EGG-RED-M", "EGG-RED-L", "OIL", "KAROLA")) {
            variants.add(shop.variant(sku).path("id").asString());
        }
        // The catalog's variants reach inventory asynchronously; receipts need them there.
        await().atMost(WAIT)
                .untilAsserted(() -> assertThat(inTenant(() -> jdbc.sql(
                                        "SELECT count(*) FROM inventory.stock_item WHERE variant_id::text = ANY (:ids)")
                                .param("ids", variants.toArray(String[]::new))
                                .query(Long.class)
                                .single()))
                        .isEqualTo(5L));
    }

    private <T> T inTenant(java.util.function.Supplier<T> work) {
        return Objects.requireNonNull(TenantContext.callWith(
                shop.tenant(), () -> new TransactionTemplate(transactions).execute(s -> work.get())));
    }

    private String line(String sku, String unitCode, String qty, String unitCost, String extra) {
        JsonNode variant = shop.variant(sku);
        return "{\"variantId\": \"%s\", \"packagingUnitId\": \"%s\", \"qty\": \"%s\", \"unitCost\": \"%s\"%s}"
                .formatted(variant.path("id").asString(), unit(variant, unitCode), qty, unitCost, extra);
    }

    /** Receives and posts a delivery; returns the posted receipt. */
    private JsonNode receive(String... lines) {
        MvcTestResult created = shop.post(
                "/api/v1/goods-receipts",
                "{\"supplierId\": \"%s\", \"locationId\": \"%s\", \"lines\": [%s]}"
                        .formatted(supplier, location, String.join(", ", lines)));
        assertThat(created).hasStatus(201);
        MvcTestResult posted =
                shop.post("/api/v1/goods-receipts/" + json(created).path("id").asString() + "/post", "");
        assertThat(posted).hasStatusOk();
        return json(posted);
    }

    private JsonNode balance(String sku, String state) {
        for (JsonNode b : json(shop.get("/api/v1/stock/balances?variantId="
                        + shop.variant(sku).path("id").asString()))
                .path("items")) {
            if (b.path("stockState").asString().equals(state)) {
                return b;
            }
        }
        throw new AssertionError("No balance of " + sku + " in " + state);
    }

    private void awaitBalance(String sku, String state, String onHand) {
        await().atMost(WAIT).untilAsserted(() -> {
            JsonNode items = json(shop.get("/api/v1/stock/balances?variantId="
                            + shop.variant(sku).path("id").asString()))
                    .path("items");
            assertThat(items)
                    .filteredOn(b -> b.path("stockState").asString().equals(state))
                    .singleElement()
                    .satisfies(b -> assertThat(b.path("onHand").asString()).isEqualTo(onHand));
        });
    }

    private String definition(String sku, String kind, String outputState) {
        for (JsonNode d : json(shop.get("/api/v1/transformation-definitions?kind=" + kind + "&variantId="
                        + shop.variant(sku).path("id").asString()))
                .path("items")) {
            for (JsonNode l : d.path("lines")) {
                if (l.path("direction").asString().equals("OUT")
                        && l.path("stockState").asString().equals(outputState)) {
                    return d.path("id").asString();
                }
            }
        }
        throw new AssertionError("No " + kind + " definition of " + sku + " into " + outputState);
    }

    private JsonNode adjust(String reason, String sku, String qty) {
        MvcTestResult adjusted = shop.post("/api/v1/stock/adjustments", """
                {"locationId": "%s", "reason": "%s", "lines": [{"variantId": "%s", "qty": "%s"}]}
                """.formatted(
                        location, reason, shop.variant(sku).path("id").asString(), qty));
        assertThat(adjusted).hasStatus(201);
        return json(adjusted);
    }

    @Test
    void thePilotShopReceivesTransformsWastesReordersAndValuesItsStock() {
        // 1. 300 small, 300 medium and 260 large red eggs at different costs.
        JsonNode eggs = receive(
                line("EGG-RED-S", "piece", "300", "38.00", ""),
                line("EGG-RED-M", "piece", "300", "42.00", ""),
                line("EGG-RED-L", "piece", "260", "46.50", ""));
        assertThat(eggs.path("number").asString()).isEqualTo("GRN-000001");
        awaitBalance("EGG-RED-L", "DEFAULT", "260");
        awaitBalance("EGG-RED-S", "DEFAULT", "300");
        awaitBalance("EGG-RED-M", "DEFAULT", "300");
        assertThat(balance("EGG-RED-S", "DEFAULT").path("avgUnitCost").asString())
                .isEqualTo("38");
        assertThat(balance("EGG-RED-M", "DEFAULT")
                        .path("stockValue")
                        .path("amount")
                        .asString())
                .isEqualTo("12600.00");
        assertThat(balance("EGG-RED-L", "DEFAULT").path("avgUnitCost").asString())
                .isEqualTo("46.5");

        // 2. Ten cans of oil at 13,800.00: 195,652.8 ml sealed.
        receive(line("OIL", "can", "10", "13800.00", ""));
        awaitBalance("OIL", "SEALED", "195652.8");
        assertThat(balance("OIL", "SEALED").path("stockValue").path("amount").asString())
                .isEqualTo("138000.00");

        // 3. One can opened, 19,400 ml measured: cost conserved, 13,800 / 19,400 per open ml.
        JsonNode run = json(shop.post("/api/v1/stock/transformations", """
                {"definitionId": "%s", "locationId": "%s",
                 "outputs": [{"variantId": "%s", "stockState": "OPEN", "qtyBase": "19400"}]}
                """.formatted(
                        definition("OIL", "OPEN", "OPEN"),
                        location,
                        shop.variant("OIL").path("id").asString())));
        assertThat(run.path("inputs").get(0).path("qtyBase").asString()).isEqualTo("19565.28");
        assertThat(run.path("outputs").get(0).path("costAmount").path("amount").asString())
                .isEqualTo("13800.00");
        assertThat(run.path("yieldVarianceQty").asString()).isEqualTo("-165.28");
        assertThat(balance("OIL", "SEALED").path("onHand").asString()).isEqualTo("176087.52");
        assertThat(balance("OIL", "SEALED").path("stockValue").path("amount").asString())
                .isEqualTo("124200.00");
        assertThat(balance("OIL", "OPEN").path("onHand").asString()).isEqualTo("19400");
        assertThat(balance("OIL", "OPEN").path("avgUnitCost").asString())
                .as("13800 / 19400")
                .isEqualTo("0.71134");

        // 4. Two kilograms of loose karola received, one packed into four 250 g packets.
        receive(line("KAROLA", "kg", "2", "2200.00", ""));
        awaitBalance("KAROLA", "DEFAULT", "2000");
        JsonNode packed = json(shop.post("/api/v1/stock/transformations", """
                {"definitionId": "%s", "locationId": "%s", "runs": "4"}
                """.formatted(
                        definition("KAROLA", "REPACK", "PACKED_PACKET_250"), location)));
        assertThat(packed.path("outputs").get(0).path("actualQtyBase").asString())
                .isEqualTo("1000");
        assertThat(balance("KAROLA", "DEFAULT").path("onHand").asString()).isEqualTo("1000");
        assertThat(balance("KAROLA", "PACKED_PACKET_250")
                        .path("stockValue")
                        .path("amount")
                        .asString())
                .isEqualTo("2200.00");

        // 5. Six broken medium eggs leave at their cost.
        adjust("BREAKAGE", "EGG-RED-M", "6");
        assertThat(events.stream(StockAdjusted.class).filter(e -> e.tenantId().equals(shop.tenant())))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.reason()).isEqualTo("BREAKAGE");
                    assertThat(e.lines().getFirst().costAmount()).isEqualByComparingTo("-252.00");
                });

        // 6. Large eggs reorder at 250: crossing it alerts once.
        String large = shop.variant("EGG-RED-L").path("id").asString();
        assertThat(shop.put(
                        "/api/v1/variants/" + large + "/reorder-policy",
                        "{\"locationId\": \"" + location + "\", \"reorderLevel\": \"250\", \"reorderQty\": \"300\", "
                                + "\"preferredSupplierId\": \"" + supplier + "\"}",
                        null))
                .hasStatusOk();
        adjust("BREAKAGE", "EGG-RED-L", "5");
        adjust("BREAKAGE", "EGG-RED-L", "10");
        adjust("BREAKAGE", "EGG-RED-L", "1");
        assertThat(events.stream(StockLevelLow.class).filter(e -> e.tenantId().equals(shop.tenant())))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.available()).isEqualByComparingTo("245");
                    assertThat(e.reorderLevel()).isEqualByComparingTo("250");
                });
        assertThat(json(shop.get("/api/v1/stock/reorder-list")).path("items"))
                .singleElement()
                .satisfies(i -> assertThat(i.path("available").asString()).isEqualTo("244"));

        // 7. The valuation equals the balances: Σ value, and Σ on hand × average cost up to its rounding.
        JsonNode valuation = json(shop.get("/api/v1/stock/valuation"));
        BigDecimal byValue = BigDecimal.ZERO;
        BigDecimal byAverage = BigDecimal.ZERO;
        for (JsonNode b : json(shop.get("/api/v1/stock/balances?limit=200")).path("items")) {
            byValue = byValue.add(
                    new BigDecimal(b.path("stockValue").path("amount").asString()));
            byAverage = byAverage.add(new BigDecimal(b.path("onHand").asString())
                    .multiply(new BigDecimal(b.path("avgUnitCost").asString()))
                    .setScale(2, RoundingMode.HALF_UP));
        }
        assertThat(valuation.path("total").path("amount").asString()).isEqualTo("177494.00");
        assertThat(new BigDecimal(valuation.path("total").path("amount").asString()))
                .isEqualByComparingTo(byValue);
        assertThat(byAverage).isCloseTo(byValue, org.assertj.core.data.Offset.offset(new BigDecimal("1.00")));
        assertThat(json(shop.post("/api/v1/admin/stock/rebuild-balances", ""))
                        .path("differences")
                        .asInt())
                .as("the balances are what the ledger says")
                .isZero();
    }
}
