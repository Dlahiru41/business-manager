package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.EGGS;
import static com.bmp.catalog.CatalogApi.KAROLA;
import static com.bmp.catalog.CatalogApi.OIL;
import static com.bmp.catalog.CatalogApi.etag;
import static com.bmp.catalog.CatalogApi.json;
import static com.bmp.catalog.CatalogApi.unit;
import static com.bmp.catalog.CatalogApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

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

/** Variants, the matrix, packaging units, barcodes and attributes through the API (T01-04). */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class VariantApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    private CatalogApi api;

    @BeforeEach
    void setUp() {
        api = new CatalogApi(mvc, "Variants");
    }

    private static List<String> skus(JsonNode variants) {
        List<String> skus = new ArrayList<>();
        variants.forEach(v -> skus.add(v.path("sku").asString()));
        return skus;
    }

    @Test
    void cat05AddingAValueAndRegeneratingCreatesOnlyTheMissingCombinations() {
        JsonNode eggs = api.create(EGGS);
        String product = "/api/v1/products/" + eggs.path("id").asString();
        JsonNode size = json(api.get(product + "/attributes")).path("items").get(1);
        String sizePath = product + "/attributes/" + size.path("id").asString();

        MvcTestResult added =
                api.patch(sizePath, "{\"values\": [{\"code\": \"XXL\", \"value\": \"XXL\"}]}", etag(api.get(sizePath)));
        assertThat(json(added).path("values")).hasSize(6);
        MvcTestResult dryRun = api.post(product + "/variants:generate", "{\"dryRun\": true}");
        MvcTestResult generated = api.post(product + "/variants:generate", "{}");

        assertThat(json(dryRun).path("dryRun").asBoolean()).isTrue();
        assertThat(skus(json(dryRun).path("created")))
                .containsExactly("EGG-RED-XXL", "EGG-WHITE-XXL", "EGG-GOLDEN_YELLOW-XXL");
        assertThat(json(dryRun).path("created").get(0).has("id")).isFalse();
        assertThat(json(generated).path("created")).hasSize(3);
        assertThat(json(generated).path("unchanged").asInt()).isEqualTo(12);
        JsonNode xxl = json(api.get("/api/v1/variants:lookup?sku=egg-red-xxl")).path("variant");
        assertThat(unit(xxl, "tray").path("qtyInBase").asString())
                .as("units copied from a sibling")
                .isEqualTo("30");
        assertThat(json(api.get(product)).path("variantCount").asInt()).isEqualTo(15);
    }

    @Test
    void cat05NewlyExcludedCombinationsAreDeactivatedNotDeleted() {
        JsonNode eggs = api.create(EGGS);
        String product = "/api/v1/products/" + eggs.path("id").asString();

        MvcTestResult regenerated = api.post(product + "/variants:generate", """
                {"exclusions": [{"COLOUR": "GOLDEN_YELLOW", "SIZE": "XS"}, {"COLOUR": "GOLDEN_YELLOW", "SIZE": "S"},
                                {"COLOUR": "GOLDEN_YELLOW", "SIZE": "M"}, {"COLOUR": "WHITE", "SIZE": "XL"}]}
                """);

        assertThat(skus(json(regenerated).path("deactivated"))).containsExactly("EGG-WHITE-XL");
        assertThat(json(regenerated).path("unchanged").asInt()).isEqualTo(11);
        JsonNode whiteXl =
                json(api.get("/api/v1/variants:lookup?sku=EGG-WHITE-XL")).path("variant");
        assertThat(whiteXl.path("status").asString()).isEqualTo("INACTIVE");
        assertThat(json(api.get(product)).path("variantCount").asInt()).isEqualTo(12);
        assertThat(json(api.get(product)).path("exclusions")).hasSize(4);
    }

    @Test
    void shouldAddAVariantByHandAndRefuseDuplicatesAndExclusions() {
        JsonNode eggs = api.create(EGGS);
        String variants = "/api/v1/products/" + eggs.path("id").asString() + "/variants";

        assertThatProblem(api.post(variants, "{\"attributeValues\": {\"COLOUR\": \"RED\", \"SIZE\": \"L\"}}"))
                .hasStatus(409)
                .hasCode("VARIANT_COMBINATION_EXISTS");
        assertThatProblem(
                        api.post(variants, "{\"attributeValues\": {\"COLOUR\": \"GOLDEN_YELLOW\", \"SIZE\": \"XS\"}}"))
                .hasStatus(422)
                .hasCode("VARIANT_COMBINATION_EXCLUDED");
        assertThatProblem(api.post(variants, "{\"attributeValues\": {\"COLOUR\": \"RED\"}}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_ATTRIBUTE_VALUE");

        JsonNode karola = api.create(KAROLA);
        MvcTestResult grade = api.post(
                "/api/v1/products/" + karola.path("id").asString() + "/variants",
                "{\"name\": \"Karola grade A\", \"sku\": \"karola-a\"}");
        assertThat(grade).hasStatus(201);
        assertThat(json(grade).path("sku").asString()).isEqualTo("KAROLA-A");
        assertThat(json(grade).path("stockStates")).hasSize(3);
        assertThat(json(api.get("/api/v1/transformation-definitions?kind=REPACK"))
                        .path("items"))
                .as("the new grade gets its own repack definitions")
                .hasSize(4);
        assertThatProblem(api.post(
                        "/api/v1/products/" + karola.path("id").asString() + "/variants", "{\"sku\": \"KAROLA-A\"}"))
                .hasStatus(409)
                .hasCode("SKU_ALREADY_EXISTS");
    }

    @Test
    void cat06SkusMayBeOverriddenButStayUnique() {
        JsonNode eggs = api.create(EGGS);
        JsonNode redL = variant(eggs, "EGG-RED-L");
        String path = "/api/v1/products/" + eggs.path("id").asString() + "/variants/"
                + redL.path("id").asString();

        MvcTestResult renamed =
                api.patch(path, "{\"sku\": \"egg-r-l\", \"name\": \"Red large egg\"}", etag(api.get(path)));

        assertThat(renamed).hasStatusOk();
        assertThat(json(renamed).path("sku").asString()).isEqualTo("EGG-R-L");
        assertThat(events.stream(VariantUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.sku()).isEqualTo("EGG-R-L"));
        assertThatProblem(api.patch(path, "{\"sku\": \"EGG-RED-XL\"}", etag(api.get(path))))
                .hasStatus(409)
                .hasCode("SKU_ALREADY_EXISTS");
        assertThatProblem(api.patch(path, "{\"stockStates\": [\"OPEN\"]}", etag(api.get(path))))
                .hasStatus(422)
                .hasCode("INVALID_STOCK_STATE");
        assertThatProblem(api.get("/api/v1/products/" + UUID.randomUUID() + "/variants/"
                        + redL.path("id").asString()))
                .hasStatus(404)
                .hasCode("VARIANT_NOT_FOUND");
    }

    @Test
    void cat08ListsFilterSellableAndPurchasableVariants() {
        JsonNode oil = api.create(OIL);
        api.create(EGGS);
        JsonNode can = unit(variant(oil, "OIL"), "can");
        String oilId = variant(oil, "OIL").path("id").asString();
        String canPath = "/api/v1/variants/" + oilId + "/packaging-units/"
                + can.path("id").asString();

        api.patch(canPath, "{\"canBuy\": false}", etag(api.get(canPath)));

        assertThat(json(api.get("/api/v1/variants?purchasable=false")).path("items"))
                .extracting(v -> v.path("sku").asString())
                .containsExactly("OIL");
        assertThat(json(api.get("/api/v1/variants?sellable=true&limit=200")).path("items"))
                .hasSize(13);
        assertThat(json(api.get("/api/v1/variants?q=white")).path("items")).hasSize(5);
        List<String> paged = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(api.get("/api/v1/variants?limit=5" + (cursor != null ? "&cursor=" + cursor : "")));
            page.path("items").forEach(v -> paged.add(v.path("sku").asString()));
            cursor = page.path("page").path("hasMore").asBoolean()
                    ? page.path("page").path("nextCursor").asString()
                    : null;
        } while (cursor != null);
        assertThat(paged).hasSize(13).doesNotHaveDuplicates();
    }

    @Test
    void cat04Cat07Cat12PackagingUnitsAndBarcodes() {
        JsonNode oil = api.create(OIL);
        JsonNode eggs = api.create(EGGS);
        String oilId = variant(oil, "OIL").path("id").asString();
        String units = "/api/v1/variants/" + oilId + "/packaging-units";

        MvcTestResult added = api.post(units, """
                {"code": "bottle-500", "name": "Bottle 500 ml", "qtyInBase": "500", "canSell": true}
                """);
        assertThat(added).hasStatus(201);
        assertThat(json(added).path("stockState").asString())
                .as("template default for a sold unit")
                .isEqualTo("OPEN");
        assertThatProblem(api.post(units, "{\"code\": \"bottle-500\", \"name\": \"Again\", \"qtyInBase\": \"500\"}"))
                .hasStatus(409)
                .hasCode("PACKAGING_UNIT_CODE_TAKEN");
        assertThatProblem(api.post(units, "{\"code\": \"drop\", \"name\": \"Drop\", \"qtyInBase\": \"0\"}"))
                .hasStatus(422)
                .hasCode("INVALID_QTY_IN_BASE");
        assertThatProblem(api.post(
                        units, "{\"code\": \"x\", \"name\": \"X\", \"qtyInBase\": \"5\", \"stockState\": \"DEFAULT\"}"))
                .hasStatus(422)
                .hasCode("INVALID_STOCK_STATE");
        assertThatProblem(api.post(
                        units,
                        "{\"code\": \"y\", \"name\": \"Y\", \"definedAs\": {\"value\": \"1\", \"unit\": \"m\"}}"))
                .hasStatus(422)
                .hasCode("UNIT_NOT_CONVERTIBLE");

        String base = units + "/" + unit(variant(oil, "OIL"), "ml").path("id").asString();
        assertThatProblem(api.patch(base, "{\"qtyInBase\": \"2\"}", etag(api.get(base))))
                .hasStatus(409)
                .hasCode("BASE_UNIT_IMMUTABLE");
        String bottle = units + "/" + json(added).path("id").asString();
        MvcTestResult resized =
                api.patch(bottle, "{\"definedAs\": {\"value\": \"0.5\", \"unit\": \"l\"}}", etag(api.get(bottle)));
        assertThat(json(resized).path("qtyInBase").asString()).isEqualTo("500");
        assertThat(json(resized).path("definedAs").path("unit").asString()).isEqualTo("l");
        assertThatProblem(api.get(units + "/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PACKAGING_UNIT_NOT_FOUND");

        String barcodes = "/api/v1/packaging-units/" + json(added).path("id").asString() + "/barcodes";
        MvcTestResult attached = api.post(barcodes, "{\"code\": \"4792173000028\"}");
        assertThat(attached).hasStatus(201);
        assertThat(json(attached).path("barcodes").toString()).isEqualTo("[\"4792173000028\"]");
        JsonNode found = json(api.get("/api/v1/variants:lookup?barcode=4792173000028"));
        assertThat(found.path("packagingUnitId").asString())
                .isEqualTo(json(added).path("id").asString());
        String tray = unit(variant(eggs, "EGG-RED-L"), "tray").path("id").asString();
        assertThatProblem(api.post("/api/v1/packaging-units/" + tray + "/barcodes", "{\"code\": \"4792173000028\"}"))
                .as("CAT-12 one barcode, one unit, across variants")
                .hasStatus(409)
                .hasCode("BARCODE_ALREADY_EXISTS");
        assertThat(api.delete(barcodes + "/4792173000028")).hasStatus(204);
        assertThatProblem(api.delete(barcodes + "/4792173000028"))
                .hasStatus(404)
                .hasCode("BARCODE_NOT_FOUND");
        assertThatProblem(api.get("/api/v1/variants:lookup?barcode=4792173000028"))
                .hasStatus(404)
                .hasCode("VARIANT_NOT_FOUND");
        assertThatProblem(api.post("/api/v1/packaging-units/" + UUID.randomUUID() + "/barcodes", "{\"code\": \"1\"}"))
                .hasStatus(404)
                .hasCode("PACKAGING_UNIT_NOT_FOUND");
        assertThat(json(api.get(units)).path("items")).hasSize(7);
    }

    @Test
    void shouldLookUpByExactlyOneKey() {
        api.create(EGGS);

        assertThat(json(api.get("/api/v1/variants:lookup?sku=EGG-RED-L"))
                        .path("variant")
                        .path("sku")
                        .asString())
                .isEqualTo("EGG-RED-L");
        assertThatProblem(api.get("/api/v1/variants:lookup")).hasStatus(400).hasCode("INVALID_PARAMETER");
        assertThatProblem(api.get("/api/v1/variants:lookup?sku=A&barcode=1"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
        assertThatProblem(api.get("/api/v1/variants:lookup?sku=NOPE"))
                .hasStatus(404)
                .hasCode("VARIANT_NOT_FOUND");
    }

    @Test
    void cat11VariantsAreDeactivatedAndReactivatedNeverDeleted() {
        JsonNode eggs = api.create(EGGS);
        String id = variant(eggs, "EGG-RED-L").path("id").asString();

        assertThat(json(api.action("/api/v1/variants/" + id + "/deactivate"))
                        .path("status")
                        .asString())
                .isEqualTo("INACTIVE");
        assertThat(json(api.action("/api/v1/variants/" + id + "/deactivate"))
                        .path("sellable")
                        .asBoolean())
                .isFalse();
        assertThat(json(api.action("/api/v1/variants/" + id + "/reactivate"))
                        .path("status")
                        .asString())
                .isEqualTo("ACTIVE");
        api.action("/api/v1/products/" + eggs.path("id").asString() + "/deactivate");
        assertThatProblem(api.action("/api/v1/variants/" + id + "/reactivate"))
                .hasStatus(409)
                .hasCode("PRODUCT_INACTIVE");
        assertThatProblem(api.post("/api/v1/products/" + eggs.path("id").asString() + "/variants:generate", "{}"))
                .hasStatus(409)
                .hasCode("PRODUCT_INACTIVE");
        assertThatProblem(api.action("/api/v1/variants/" + UUID.randomUUID() + "/deactivate"))
                .hasStatus(404)
                .hasCode("VARIANT_NOT_FOUND");
    }

    @Test
    void attributeTypesAreFixedOnceVariantsExistButValuesCanBeAdded() {
        JsonNode eggs = api.create(EGGS);
        String attributes = "/api/v1/products/" + eggs.path("id").asString() + "/attributes";
        JsonNode colour = json(api.get(attributes)).path("items").get(0);
        String colourPath = attributes + "/" + colour.path("id").asString();

        assertThatProblem(api.post(
                        attributes, "{\"code\": \"GRADE\", \"name\": \"Grade\", \"values\": [{\"value\": \"A\"}]}"))
                .hasStatus(409)
                .hasCode("ATTRIBUTE_TYPES_LOCKED");
        assertThatProblem(api.patch(
                        colourPath,
                        "{\"values\": [{\"code\": \"RED\", \"value\": \"Crimson\"}]}",
                        etag(api.get(colourPath))))
                .hasStatus(409)
                .hasCode("ATTRIBUTE_VALUE_CODE_TAKEN");
        String red = colour.path("values").get(0).path("id").asString();
        MvcTestResult renamed = api.patch(
                colourPath,
                "{\"name\": \"Shell colour\", \"values\": [{\"id\": \"" + red + "\", \"value\": \"Brown\"}]}",
                etag(api.get(colourPath)));
        assertThat(json(renamed).path("name").asString()).isEqualTo("Shell colour");
        assertThat(json(renamed).path("values").get(0).path("value").asString()).isEqualTo("Brown");
        assertThat(events.stream(VariantUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .as("variant snapshots carry the value names")
                .hasSize(12);
        assertThatProblem(api.get(attributes + "/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("ATTRIBUTE_NOT_FOUND");
        assertThatProblem(api.patch(
                        colourPath,
                        "{\"values\": [{\"id\": \"" + UUID.randomUUID() + "\", \"value\": \"X\"}]}",
                        etag(api.get(colourPath))))
                .hasStatus(422)
                .hasCode("UNKNOWN_ATTRIBUTE_VALUE");

        JsonNode plain = api.create("{\"code\": \"SOAP\", \"name\": \"Soap\", \"template\": \"SIMPLE\"}");
        String plainAttributes = "/api/v1/products/" + plain.path("id").asString() + "/attributes";
        assertThatProblem(api.post(
                        plainAttributes,
                        "{\"code\": \"SCENT\", \"name\": \"Scent\", \"values\": [{\"value\": \"Rose\"}]}"))
                .as("the soap already has its single variant")
                .hasStatus(409)
                .hasCode("ATTRIBUTE_TYPES_LOCKED");

        JsonNode hats = api.create("""
                {"code": "HAT", "name": "Hat", "template": "VARIANT_MATRIX",
                 "attributes": [{"code": "SIZE", "name": "Size", "values": [{"value": "M"}]}],
                 "exclusions": [{"SIZE": "M"}]}
                """);
        assertThat(hats.path("variantCount").asInt()).isZero();
        String hatAttributes = "/api/v1/products/" + hats.path("id").asString() + "/attributes";
        String colourDraft = "{\"code\": \"COLOUR\", \"name\": \"Colour\", \"values\": [{\"value\": \"Blue\"}]}";
        assertThat(api.post(hatAttributes, colourDraft)).hasStatus(201);
        assertThatProblem(api.post(hatAttributes, colourDraft)).hasStatus(409).hasCode("ATTRIBUTE_CODE_TAKEN");
    }
}
