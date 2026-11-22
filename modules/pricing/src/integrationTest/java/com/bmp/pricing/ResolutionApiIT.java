package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.EGGS;
import static com.bmp.pricing.PricingApi.OIL;
import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.json;
import static com.bmp.pricing.PricingApi.row;
import static com.bmp.pricing.PricingApi.unit;
import static com.bmp.pricing.PricingApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.test.persistence.WithPostgres;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** Basket resolution through the API: lists, rules, taxes, derivation and line outcomes (PRC-04..PRC-07, PRC-10). */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class ResolutionApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private PricingApi api;
    private JsonNode eggs;
    private JsonNode redL;
    private JsonNode oil;

    @BeforeEach
    void setUp() {
        api = new PricingApi(mvc, jdbc, transactions, "Resolution");
        eggs = api.product(EGGS);
        redL = variant(eggs, "EGG-RED-L");
        oil = variant(api.product(OIL), "OIL");
        prices("RETAIL", row(redL, "piece", "55.00"));
        prices("WHOLESALE", row(redL, "piece", "48.00") + "," + row(oil, "can", "14500.00"));
    }

    private void prices(String list, String rows) {
        String path = "/api/v1/price-lists/" + api.list(list) + "/prices";
        assertThat(api.put(path, "{\"prices\": [" + rows + "]}", etag(api.get(path))))
                .hasStatusOk();
    }

    private JsonNode resolve(String channel, String lines) {
        MvcTestResult resolved =
                api.post("/api/v1/prices:resolve", "{\"channel\": \"" + channel + "\", \"lines\": [" + lines + "]}");
        assertThat(resolved).hasStatusOk();
        return json(resolved);
    }

    private static String line(String id, JsonNode variant, String unitCode, String qty) {
        return "{\"lineId\": \"" + id + "\", \"variantId\": \""
                + variant.path("id").asString() + "\", \"packagingUnitId\": \"" + unit(variant, unitCode)
                + "\", \"qty\": \"" + qty + "\"}";
    }

    @Test
    void prc04Prc10TheChannelSelectsTheListAndLinesCarryListVersionAndSource() {
        JsonNode retail = resolve("RETAIL", line("1", redL, "piece", "10"));
        JsonNode wholesale = resolve("WHOLESALE", line("1", redL, "piece", "10") + "," + line("2", oil, "can", "1"));

        JsonNode line = retail.path("lines").get(0);
        assertThat(line.path("status").asString()).isEqualTo("PRICED");
        assertThat(line.path("unitPrice").path("amount").asString()).isEqualTo("55.00");
        assertThat(line.path("lineTotal").path("amount").asString()).isEqualTo("550.00");
        assertThat(line.path("priceListId").asString()).isEqualTo(api.list("RETAIL"));
        assertThat(line.path("priceListVersion").asInt()).isEqualTo(1);
        assertThat(line.path("source").asString()).isEqualTo("LIST");
        assertThat(wholesale
                        .path("lines")
                        .get(0)
                        .path("unitPrice")
                        .path("amount")
                        .asString())
                .isEqualTo("48.00");
        assertThat(wholesale.path("totals").path("lineTotal").path("amount").asString())
                .isEqualTo("14980.00");
        assertThat(wholesale.path("currency").asString()).isEqualTo("LKR");
    }

    @Test
    void prc05AMissingPriceIsALineStatusAndDerivationIsATenantChoice() {
        JsonNode basket = resolve("RETAIL", line("1", redL, "tray", "1") + "," + line("2", redL, "piece", "1"));

        assertThat(basket.path("lines").get(0).path("status").asString()).isEqualTo("PRICE_NOT_FOUND");
        assertThat(basket.path("lines").get(1).path("status").asString()).isEqualTo("PRICED");
        assertThat(basket.path("totals").path("lineTotal").path("amount").asString())
                .isEqualTo("55.00");

        MvcTestResult settings = api.client.get("/api/v1/settings/pricing", api.owner);
        api.client.patch(
                "/api/v1/settings/pricing", api.owner, "{\"values\": {\"derivePriceFromBase\": true}}", etag(settings));
        JsonNode derived =
                resolve("RETAIL", line("1", redL, "tray", "2")).path("lines").get(0);
        assertThat(derived.path("source").asString()).isEqualTo("DERIVED");
        assertThat(derived.path("unitPrice").path("amount").asString()).isEqualTo("1650.00");
        assertThat(derived.path("lineTotal").path("amount").asString()).isEqualTo("3300.00");
    }

    @Test
    void prc06AWholesaleQuantityBreakAppliesFromThreeHundredPieces() {
        assertThat(api.post("/api/v1/price-rules", """
                        {"code": "EGG-300", "name": "Eggs, 300 pieces and more", "kind": "QTY_BREAK",
                         "conditions": {"channel": "WHOLESALE", "productIds": ["%s"], "minQtyBase": "300"},
                         "effect": {"type": "PERCENT_OFF", "value": "5"}, "priority": 10}
                        """.formatted(eggs.path("id").asString())))
                .hasStatus(201);

        JsonNode below = resolve("WHOLESALE", line("1", redL, "piece", "299"))
                .path("lines")
                .get(0);
        JsonNode trays = resolve("WHOLESALE", line("1", redL, "piece", "300"))
                .path("lines")
                .get(0);

        assertThat(below.path("appliedRules")).isEmpty();
        assertThat(trays.path("appliedRules").toString()).isEqualTo("[\"EGG-300\"]");
        assertThat(trays.path("source").asString()).isEqualTo("RULE:EGG-300");
        assertThat(trays.path("amount").path("amount").asString())
                .as("300 × 48 × 0.95")
                .isEqualTo("13680.00");
        assertThat(trays.path("discount").path("amount").asString()).isEqualTo("720.00");
    }

    @Test
    void prc07TaxIsBackCalculatedFromTaxInclusivePricesOfTheVariantsCategory() {
        String vat = json(api.post("/api/v1/tax-categories", "{\"code\": \"VAT_STANDARD\", \"name\": \"VAT\"}"))
                .path("id")
                .asString();
        assertThat(api.post(
                        "/api/v1/tax-rates",
                        "{\"taxCategoryId\": \"" + vat
                                + "\", \"code\": \"VAT\", \"name\": \"VAT\", \"ratePct\": \"18\","
                                + " \"validFrom\": \"2026-01-01\"}"))
                .hasStatus(201);
        String product = "/api/v1/products/" + eggs.path("id").asString();
        MvcTestResult read = api.client.get(product, api.owner);
        assertThat(api.client.patch(product, api.owner, "{\"taxCategoryId\": \"" + vat + "\"}", etag(read)))
                .hasStatusOk();
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(api.<Long>sql(
                                j -> j.sql("SELECT count(*) FROM pricing.variant_price_ref WHERE tax_category_id = :t")
                                        .param("t", UUID.fromString(vat))
                                        .query(Long.class)
                                        .single()))
                        .as("VariantUpdated carried the tax category to the replica")
                        .isEqualTo(2L));

        JsonNode line =
                resolve("RETAIL", line("1", redL, "piece", "10")).path("lines").get(0);

        assertThat(line.path("taxInclusive").asBoolean()).isTrue();
        assertThat(line.path("taxAmount").path("amount").asString()).isEqualTo("83.90");
        assertThat(line.path("netAmount").path("amount").asString()).isEqualTo("466.10");
        assertThat(line.path("taxes").get(0).path("code").asString()).isEqualTo("VAT");
        assertThatProblem(api.client.post(
                        "/api/v1/products",
                        api.owner,
                        "{\"code\": \"SOAP\", \"name\": \"Soap\", \"template\": \"SIMPLE\", \"taxCategoryId\": \""
                                + UUID.randomUUID() + "\"}"))
                .as("the catalog checks tax categories through the pricing SPI")
                .hasStatus(422)
                .hasCode("UNKNOWN_TAX_CATEGORY");
    }

    @Test
    void unknownVariantsAndUnitsThatAreNotSoldAreNotSellable() {
        JsonNode basket = resolve(
                "RETAIL",
                "{\"variantId\": \"" + UUID.randomUUID() + "\", \"packagingUnitId\": \"" + UUID.randomUUID()
                        + "\", \"qty\": \"1\"}," + line("2", oil, "ml", "100"));

        assertThat(basket.path("lines"))
                .allSatisfy(l -> assertThat(l.path("status").asString()).isEqualTo("NOT_SELLABLE"));
        assertThat(basket.path("totals").path("lineTotal").path("amount").asString())
                .isEqualTo("0.00");
        assertThatProblem(api.client.post(
                        "/api/v1/prices:resolve", api.owner, "{\"channel\": \"RETAIL\", \"lines\": []}"))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
    }
}
