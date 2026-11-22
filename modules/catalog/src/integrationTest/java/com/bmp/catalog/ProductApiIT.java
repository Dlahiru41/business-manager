package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.EGGS;
import static com.bmp.catalog.CatalogApi.KAROLA;
import static com.bmp.catalog.CatalogApi.OIL;
import static com.bmp.catalog.CatalogApi.SOYA;
import static com.bmp.catalog.CatalogApi.etag;
import static com.bmp.catalog.CatalogApi.json;
import static com.bmp.catalog.CatalogApi.unit;
import static com.bmp.catalog.CatalogApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.api.events.ProductCreated;
import com.bmp.catalog.api.events.ProductUpdated;
import com.bmp.catalog.api.events.TransformationDefinitionPublished;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.test.persistence.WithPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** Products created from templates in one call, changed, deactivated and converted (T01-04). */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class ProductApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    private CatalogApi api;

    @BeforeEach
    void setUp() {
        api = new CatalogApi(mvc, "Products");
    }

    private List<String> skus(JsonNode product) {
        List<String> skus = new ArrayList<>();
        product.path("variants").forEach(v -> skus.add(v.path("sku").asString()));
        return skus;
    }

    @Test
    void cat05Cat06EggsAreTwelveVariantsWithGeneratedSkusAndNoGoldenYellowSmallSizes() {
        MvcTestResult created = api.post("/api/v1/products", EGGS);

        assertThat(created)
                .hasStatus(201)
                .hasHeader("ETag", "W/\"" + json(created).path("version").asLong() + "\"");
        JsonNode eggs = json(created);
        assertThat(created.getResponse().getHeader("Location"))
                .isEqualTo("/api/v1/products/" + eggs.path("id").asString());
        assertThat(eggs.path("variantCount").asInt()).isEqualTo(12);
        assertThat(skus(eggs))
                .hasSize(12)
                .contains("EGG-RED-XS", "EGG-WHITE-XL", "EGG-GOLDEN_YELLOW-L", "EGG-GOLDEN_YELLOW-XL")
                .doesNotContain("EGG-GOLDEN_YELLOW-XS", "EGG-GOLDEN_YELLOW-S", "EGG-GOLDEN_YELLOW-M");
        JsonNode redL = variant(eggs, "EGG-RED-L");
        assertThat(redL.path("name").asString()).isEqualTo("Eggs Red L");
        assertThat(redL.path("attributeValues")).hasSize(2);
        assertThat(unit(redL, "piece").path("isBase").asBoolean())
                .as("CAT-02 base unit")
                .isTrue();
        assertThat(unit(redL, "piece").path("qtyInBase").asString()).isEqualTo("1");
        assertThat(unit(redL, "tray").path("qtyInBase").asString()).isEqualTo("30");
        assertThat(eggs.path("exclusions")).hasSize(3);
        assertThat(events.stream(ProductCreated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(1);
        assertThat(events.stream(VariantCreated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(12)
                .allSatisfy(e -> assertThat(e.packagingUnits()).hasSize(2));
    }

    @Test
    void cat09OilIsABulkBreakWithAnOpenDefinitionPublishedForTheCan() {
        JsonNode oil = api.create(OIL);
        JsonNode variant = variant(oil, "OIL");
        JsonNode can = unit(variant, "can");

        assertThat(oil.path("baseUnit").asString()).isEqualTo("ml");
        assertThat(oil.path("tracking").path("decimalQty").asBoolean()).isTrue();
        assertThat(variant.path("stockStates").toString()).isEqualTo("[\"SEALED\",\"OPEN\"]");
        assertThat(variant.path("sellMode").asString()).isEqualTo("BREAK_ON_SALE");
        assertThat(can.path("qtyInBase").asString()).as("18 kg at density 0.92").isEqualTo("19565.28");
        assertThat(can.path("stockState").asString()).isEqualTo("SEALED");
        assertThat(can.path("definedAs").path("unit").asString()).isEqualTo("kg");
        assertThat(unit(variant, "bottle-187").path("qtyInBase").asString()).isEqualTo("187.5");
        assertThat(unit(variant, "bottle-750").path("stockState").asString()).isEqualTo("OPEN");
        assertThat(unit(variant, "bottle-1000").path("isDefaultSell").asBoolean())
                .isTrue();
        assertThat(unit(variant, "ml").path("canSell").asBoolean()).isFalse();
        assertThat(variant.path("sellable").asBoolean()).isTrue();
        assertThat(variant.path("purchasable").asBoolean()).isTrue();

        MvcTestResult definitions = api.get("/api/v1/transformation-definitions?variantId="
                + variant.path("id").asString());
        JsonNode open = json(definitions).path("items").get(0);
        assertThat(json(definitions).path("items")).hasSize(1);
        assertThat(open.path("code").asString()).isEqualTo("OIL-OPEN-CAN");
        assertThat(open.path("kind").asString()).isEqualTo("OPEN");
        assertThat(open.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(open.path("defVersion").asInt()).isEqualTo(1);
        assertThat(open.path("lines").get(0).path("qtyBase").asString()).isEqualTo("19565.28");
        assertThat(events.stream(TransformationDefinitionPublished.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.version()).isEqualTo(1));
    }

    @Test
    void cat09KarolaKeepsOwnPacketsInTheirOwnStateWithRepackDefinitions() {
        JsonNode karola = api.create(KAROLA);
        JsonNode variant = variant(karola, "KAROLA");

        assertThat(karola.path("tracking").path("weighed").asBoolean()).isTrue();
        assertThat(variant.path("stockStates").toString())
                .isEqualTo("[\"DEFAULT\",\"PACKED_PACKET_250\",\"PACKED_PACKET_500\"]");
        assertThat(unit(variant, "kg").path("qtyInBase").asString()).isEqualTo("1000");
        assertThat(unit(variant, "kg").path("stockState").asString()).isEqualTo("DEFAULT");
        assertThat(unit(variant, "packet-250").path("stockState").asString()).isEqualTo("PACKED_PACKET_250");
        JsonNode definitions = json(api.get("/api/v1/transformation-definitions?kind=REPACK&variantId="
                + variant.path("id").asString()));
        List<String> codes = new ArrayList<>();
        definitions.path("items").forEach(d -> codes.add(d.path("code").asString()));
        assertThat(codes).containsExactly("KAROLA-REPACK-PACKET-250", "KAROLA-REPACK-PACKET-500");
    }

    @Test
    void cat09BrandedSoyaIsTwoBrandsTimesTwoSizesCountedInPacks() {
        JsonNode soya = api.create(SOYA);

        assertThat(soya.path("baseUnit").asString()).isEqualTo("pack");
        assertThat(skus(soya))
                .containsExactlyInAnyOrder("SOYA-MSL-G90", "SOYA-MSL-G180", "SOYA-VTM-G90", "SOYA-VTM-G180");
    }

    @Test
    void shouldReplayACreateWithTheSameIdempotencyKeyAndRefuseAnotherBody() {
        String key = UUID.randomUUID().toString();
        MvcTestResult first = api.client.post("/api/v1/products", api.owner, SOYA, key);
        MvcTestResult replay = api.client.post("/api/v1/products", api.owner, SOYA, key);

        assertThat(first).hasStatus(201);
        assertThat(replay).hasStatus(201).hasHeader("Idempotent-Replayed", "true");
        assertThat(json(replay).path("id").asString())
                .isEqualTo(json(first).path("id").asString());
        assertThatProblem(api.client.post("/api/v1/products", api.owner, KAROLA, key))
                .hasStatus(422)
                .hasCode("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void shouldRefuseInvalidProductsWithTheirErrorCodes() {
        api.create(SOYA);
        assertThatProblem(api.post("/api/v1/products", SOYA)).hasStatus(409).hasCode("PRODUCT_CODE_TAKEN");
        assertThatProblem(api.post(
                        "/api/v1/products",
                        "{\"code\": \"SHIRT\", \"name\": \"Shirt\", \"template\": \"VARIANT_MATRIX\"}"))
                .hasStatus(422)
                .hasCode("ATTRIBUTES_REQUIRED");
        assertThatProblem(api.post(
                        "/api/v1/products",
                        "{\"code\": \"X1\", \"name\": \"X\", \"template\": \"SIMPLE\", \"baseUnit\": \"furlong\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_UNIT");
        assertThatProblem(api.post("/api/v1/products", """
                        {"code": "X2", "name": "Oil without density", "template": "BULK_BREAK",
                         "packagingUnits": [{"code": "can", "name": "Can", "definedAs": {"value": "18", "unit": "kg"},
                                             "canBuy": true}]}
                        """)).hasStatus(422).hasCode("UNIT_NOT_CONVERTIBLE");
        assertThatProblem(api.post("/api/v1/products", """
                        {"code": "X3", "name": "X", "template": "SIMPLE",
                         "packagingUnits": [{"code": "box", "name": "Box", "qtyInBase": "6", "stockState": "OPEN"}]}
                        """)).hasStatus(422).hasCode("INVALID_STOCK_STATE");
        assertThatProblem(api.post("/api/v1/products", """
                        {"code": "X4", "name": "X", "template": "SIMPLE",
                         "packagingUnits": [{"code": "piece", "name": "Pair", "qtyInBase": "2"}]}
                        """)).hasStatus(422).hasCode("INVALID_QTY_IN_BASE");
        assertThatProblem(api.post("/api/v1/products", """
                        {"code": "X5", "name": "X", "template": "SIMPLE",
                         "packagingUnits": [{"code": "box", "name": "Box", "qtyInBase": "6"},
                                            {"code": "BOX", "name": "Box", "qtyInBase": "12"}]}
                        """)).hasStatus(409).hasCode("PACKAGING_UNIT_CODE_TAKEN");
        assertThatProblem(api.post("/api/v1/products", """
                        {"code": "X6", "name": "X", "template": "BULK_BREAK",
                         "conversions": [{"fromUnit": "kg", "toUnit": "ml", "factor": "0", "source": "DENSITY"}]}
                        """)).hasStatus(422).hasCode("INVALID_CONVERSION");
        assertThatProblem(api.post(
                        "/api/v1/products",
                        "{\"code\": \"X7\", \"name\": \"X\", \"template\": \"SIMPLE\", \"categoryId\": \""
                                + UUID.randomUUID() + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_CATEGORY");
        assertThatProblem(
                        api.client.post("/api/v1/products", api.owner, "{\"code\": \"X8\", \"template\": \"SIMPLE\"}"))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThatProblem(api.post("/api/v1/products", manyValues()))
                .hasStatus(422)
                .hasCode("TOO_MANY_VARIANTS");
        assertThatProblem(api.post("/api/v1/products", """
                        {"code": "X9", "name": "X", "template": "VARIANT_MATRIX",
                         "attributes": [{"code": "SIZE", "name": "Size", "values": [{"value": "L"}]}],
                         "exclusions": [{"SIZE": "XXXL"}]}
                        """)).hasStatus(422).hasCode("UNKNOWN_ATTRIBUTE_VALUE");
        assertThat(json(api.get("/api/v1/products?template=SIMPLE")).path("items"))
                .isEmpty();
    }

    @Test
    void shouldReadListAndFilterProducts() {
        JsonNode eggs = api.create(EGGS);
        api.create(OIL);
        api.create(SOYA);
        String id = eggs.path("id").asString();

        MvcTestResult plain = api.get("/api/v1/products/" + id);
        MvcTestResult withVariants = api.get("/api/v1/products/" + id + "?include=variants");
        MvcTestResult withUnits = api.get("/api/v1/products/" + id + "?include=variants,units");

        assertThat(plain).hasStatusOk();
        assertThat(json(plain).has("variants")).isFalse();
        assertThat(json(withVariants).path("variants")).hasSize(12);
        assertThat(json(withVariants).path("variants").get(0).has("packagingUnits"))
                .isFalse();
        assertThat(json(withUnits).path("variants").get(0).path("packagingUnits"))
                .hasSize(2);
        assertThat(json(api.get("/api/v1/products?q=oil")).path("items")).hasSize(1);
        assertThat(json(api.get("/api/v1/products?template=BRANDED_PACKET")).path("items"))
                .hasSize(1);
        JsonNode first = json(api.get("/api/v1/products?limit=2"));
        assertThat(first.path("items")).hasSize(2);
        JsonNode second = json(api.get("/api/v1/products?limit=2&cursor="
                + first.path("page").path("nextCursor").asString()));
        assertThat(second.path("items")).hasSize(1);
        assertThat(second.path("page").path("hasMore").asBoolean()).isFalse();
        assertThatProblem(api.get("/api/v1/products/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PRODUCT_NOT_FOUND");
    }

    @Test
    void cat13UpdatingTheTaxCategoryRepublishesEveryVariantSnapshot() {
        JsonNode soya = api.create(SOYA);
        String path = "/api/v1/products/" + soya.path("id").asString();
        String etag = etag(api.get(path));
        UUID tax = UUID.randomUUID();

        assertThatProblem(api.patch(path, "{\"name\": \"Soya\"}", null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        MvcTestResult updated =
                api.patch(path, "{\"name\": \"Soya chunks\", \"taxCategoryId\": \"" + tax + "\"}", etag);

        assertThat(updated).hasStatusOk();
        assertThat(json(updated).path("name").asString()).isEqualTo("Soya chunks");
        assertThat(json(updated).path("taxCategoryId").asString()).isEqualTo(tax.toString());
        assertThat(events.stream(ProductUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(1);
        assertThat(events.stream(VariantUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(4)
                .allSatisfy(e -> assertThat(e.taxCategoryId()).isEqualTo(tax));
        assertThatProblem(api.patch(path, "{\"name\": \"Stale\"}", etag))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
    }

    @Test
    void cat11DeactivatingAProductDeactivatesItsVariantsAndReactivatingKeepsThem() {
        JsonNode soya = api.create(SOYA);
        String path = "/api/v1/products/" + soya.path("id").asString();

        MvcTestResult deactivated = api.action(path + "/deactivate");
        MvcTestResult again = api.action(path + "/deactivate");

        assertThat(json(deactivated).path("status").asString()).isEqualTo("INACTIVE");
        assertThat(json(again).path("status").asString()).isEqualTo("INACTIVE");
        assertThat(events.stream(VariantDeactivated.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(4);
        assertThat(json(api.get("/api/v1/variants?productId=" + soya.path("id").asString() + "&status=INACTIVE"))
                        .path("items"))
                .hasSize(4);
        assertThat(json(api.action(path + "/reactivate")).path("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(json(api.action(path + "/reactivate")).path("status").asString())
                .isEqualTo("ACTIVE");
        assertThat(json(api.get("/api/v1/variants?productId=" + soya.path("id").asString() + "&sellable=true"))
                        .path("items"))
                .as("variants stay inactive")
                .isEmpty();
    }

    @Test
    void cat14AChangedConversionResizesTheCanAndRepublishesItsOpenDefinition() {
        JsonNode oil = api.create(OIL);
        String path = "/api/v1/products/" + oil.path("id").asString();

        MvcTestResult replaced = api.put(
                path + "/conversions",
                "{\"conversions\": [{\"fromUnit\": \"kg\", \"toUnit\": \"ml\", \"factor\": \"1087.5\","
                        + " \"source\": \"MEASURED_YIELD\"}]}",
                etag(api.get(path)));

        assertThat(replaced).hasStatusOk();
        assertThat(json(replaced).path("conversions").get(0).path("factor").asString())
                .isEqualTo("1087.5");
        JsonNode variant =
                json(api.get(path + "?include=units")).path("variants").get(0);
        assertThat(unit(variant, "can").path("qtyInBase").asString()).isEqualTo("19575");
        JsonNode open = json(api.get("/api/v1/transformation-definitions?kind=OPEN"))
                .path("items")
                .get(0);
        assertThat(open.path("defVersion").asInt())
                .as("a new published version")
                .isEqualTo(2);
        assertThat(open.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(open.path("lines").get(0).path("qtyBase").asString()).isEqualTo("19575");
        assertThat(open.path("lines").get(1).path("qtyBase").asString()).isEqualTo("19575");
        assertThat(events.stream(VariantUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.packagingUnits())
                        .anySatisfy(u -> assertThat(u.qtyInBase()).isEqualByComparingTo("19575")));
        assertThat(events.stream(TransformationDefinitionPublished.class)
                        .filter(e -> e.tenantId().equals(api.tenant))
                        .map(TransformationDefinitionPublished::version))
                .containsExactly(1, 2);
        assertThatProblem(api.put(path + "/conversions", "{\"conversions\": []}", etag(api.get(path))))
                .hasStatus(422)
                .hasCode("UNIT_NOT_CONVERTIBLE");
    }

    private static String manyValues() {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < 30; i++) {
            values.append(i == 0 ? "" : ",")
                    .append("{\"code\": \"V")
                    .append(i)
                    .append("\", \"value\": \"V")
                    .append(i)
                    .append("\"}");
        }
        return "{\"code\": \"MANY\", \"name\": \"Many\", \"template\": \"VARIANT_MATRIX\", \"attributes\": ["
                + "{\"code\": \"A\", \"name\": \"A\", \"values\": [" + values + "]},"
                + "{\"code\": \"B\", \"name\": \"B\", \"values\": [" + values + "]}]}";
    }
}
