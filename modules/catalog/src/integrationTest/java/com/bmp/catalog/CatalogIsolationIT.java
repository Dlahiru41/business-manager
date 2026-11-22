package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.EGGS;
import static com.bmp.catalog.CatalogApi.OIL;
import static com.bmp.catalog.CatalogApi.json;
import static com.bmp.catalog.CatalogApi.unit;
import static com.bmp.catalog.CatalogApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.WithPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;

/** Tenant A's catalog is invisible to tenant B through the API: 404, empty lists, no lookups (06 par. 10). */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class CatalogIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Test
    void anotherTenantSeesNoneOfTheCatalog() {
        CatalogApi a = new CatalogApi(mvc, "Isolation A");
        CatalogApi b = new CatalogApi(mvc, "Isolation B");
        JsonNode oil = a.create(OIL);
        a.create(EGGS);
        JsonNode variant = variant(oil, "OIL");
        String variantId = variant.path("id").asString();
        String canId = unit(variant, "can").path("id").asString();
        a.post("/api/v1/packaging-units/" + canId + "/barcodes", "{\"code\": \"4792173000042\"}");
        String definitionId = json(a.get("/api/v1/transformation-definitions"))
                .path("items")
                .get(0)
                .path("id")
                .asString();
        String category = json(a.post("/api/v1/categories", "{\"name\": \"Oils\"}"))
                .path("id")
                .asString();

        assertThatProblem(b.get("/api/v1/products/" + oil.path("id").asString()))
                .hasStatus(404)
                .hasCode("PRODUCT_NOT_FOUND");
        assertThatProblem(b.get("/api/v1/products/" + oil.path("id").asString() + "/variants/" + variantId))
                .hasStatus(404);
        assertThatProblem(b.get("/api/v1/variants/" + variantId + "/packaging-units"))
                .hasStatus(404);
        assertThatProblem(b.get("/api/v1/variants:lookup?sku=OIL")).hasStatus(404);
        assertThatProblem(b.get("/api/v1/variants:lookup?barcode=4792173000042"))
                .hasStatus(404);
        assertThatProblem(b.get("/api/v1/transformation-definitions/" + definitionId))
                .hasStatus(404);
        assertThatProblem(b.get("/api/v1/categories/" + category)).hasStatus(404);
        assertThatProblem(b.action("/api/v1/variants/" + variantId + "/deactivate"))
                .hasStatus(404);
        assertThat(json(b.get("/api/v1/products")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/variants")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/transformation-definitions")).path("items"))
                .isEmpty();
        assertThat(json(b.get("/api/v1/catalog/snapshot")).path("variants")).isEmpty();
        JsonNode otherOil = b.create(OIL);
        String otherCan = unit(variant(otherOil, "OIL"), "can").path("id").asString();
        assertThat(otherOil.path("code").asString())
                .as("codes and SKUs are unique per tenant only")
                .isEqualTo("OIL");
        assertThat(b.post("/api/v1/packaging-units/" + otherCan + "/barcodes", "{\"code\": \"4792173000042\"}"))
                .as("barcodes are unique per tenant only (CAT-12)")
                .hasStatus(201);
    }
}
