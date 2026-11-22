package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.etag;
import static com.bmp.catalog.CatalogApi.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.WithPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/** Templates, units of measure and categories (CAT-01, CAT-09). */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class ReferenceDataApiIT {

    @Autowired
    MockMvcTester mvc;

    private CatalogApi api;

    @BeforeEach
    void setUp() {
        api = new CatalogApi(mvc, "Reference data");
    }

    @Test
    void cat09ListsEveryTemplateWithItsDefaults() {
        JsonNode templates = json(api.get("/api/v1/product-templates")).path("items");

        assertThat(templates).hasSize(7);
        assertThat(templates)
                .filteredOn(t -> t.path("code").asString().equals("BULK_BREAK"))
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.path("defaultStockStates").toString()).isEqualTo("[\"SEALED\",\"OPEN\"]");
                    assertThat(t.path("autoTransformations").toString()).isEqualTo("[\"OPEN\"]");
                });
    }

    @Test
    void cat01StandardUnitsComeFirstAndTenantsAddTheirOwn() {
        MvcTestResult created = api.post(
                "/api/v1/units",
                "{\"code\": \"bushel\", \"name\": \"Bushel\", \"dimension\": \"VOLUME\", \"toSiFactor\": \"35.2391\"}");

        assertThat(created).hasStatus(201);
        assertThat(json(created).path("standard").asBoolean()).isFalse();
        List<String> codes = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(api.get("/api/v1/units?limit=4" + (cursor != null ? "&cursor=" + cursor : "")));
            page.path("items").forEach(u -> codes.add(u.path("code").asString()));
            cursor = page.path("page").path("hasMore").asBoolean()
                    ? page.path("page").path("nextCursor").asString()
                    : null;
        } while (cursor != null);
        assertThat(codes).containsExactly("cm", "dozen", "g", "kg", "l", "m", "ml", "pack", "piece", "bushel");
        assertThat(json(api.get("/api/v1/units?dimension=MASS")).path("items")).hasSize(2);
        assertThatProblem(api.post("/api/v1/units", "{\"code\": \"KG\", \"name\": \"Kilo\", \"dimension\": \"MASS\"}"))
                .hasStatus(409)
                .hasCode("UNIT_CODE_TAKEN");
        assertThatProblem(api.post(
                        "/api/v1/units", "{\"code\": \"bushel\", \"name\": \"Again\", \"dimension\": \"VOLUME\"}"))
                .hasStatus(409)
                .hasCode("UNIT_CODE_TAKEN");
        assertThatProblem(api.client.post(
                        "/api/v1/units",
                        api.token(SystemRole.CASHIER),
                        "{\"code\": \"bale\", \"name\": \"Bale\", \"dimension\": \"COUNT\"}"))
                .as("cashiers read the catalog, they do not change it")
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
        assertThat(api.client.get("/api/v1/units", api.token(SystemRole.CASHIER)))
                .hasStatusOk();
    }

    @Test
    void categoriesFormATreeWithUniqueNamesPerLevel() {
        JsonNode food = json(api.post("/api/v1/categories", "{\"name\": \"Food\"}"));
        String foodId = food.path("id").asString();
        JsonNode dry =
                json(api.post("/api/v1/categories", "{\"name\": \"Dry goods\", \"parentId\": \"" + foodId + "\"}"));
        String dryPath = "/api/v1/categories/" + dry.path("id").asString();
        JsonNode fish = json(api.post(
                "/api/v1/categories",
                "{\"name\": \"Dried fish\", \"parentId\": \"" + dry.path("id").asString() + "\"}"));

        assertThat(fish.path("ancestorIds").toString())
                .contains(foodId, dry.path("id").asString());
        assertThatProblem(api.post("/api/v1/categories", "{\"name\": \"FOOD\"}"))
                .hasStatus(409)
                .hasCode("CATEGORY_NAME_TAKEN");
        assertThatProblem(api.post(
                        "/api/v1/categories", "{\"name\": \"X\", \"parentId\": \"" + UUID.randomUUID() + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_CATEGORY");
        String foodPath = "/api/v1/categories/" + foodId;
        assertThatProblem(api.patch(
                        foodPath, "{\"parentId\": \"" + fish.path("id").asString() + "\"}", etag(api.get(foodPath))))
                .hasStatus(422)
                .hasCode("CATEGORY_CYCLE");

        MvcTestResult moved =
                api.patch(dryPath, "{\"moveToRoot\": true, \"name\": \"Dry food\"}", etag(api.get(dryPath)));
        assertThat(json(moved).path("ancestorIds")).isEmpty();
        JsonNode fishAfter =
                json(api.get("/api/v1/categories/" + fish.path("id").asString()));
        assertThat(fishAfter.path("ancestorIds").toString())
                .doesNotContain(foodId)
                .contains(dry.path("id").asString());
        assertThat(json(api.get("/api/v1/categories?parentId=" + dry.path("id").asString()))
                        .path("items"))
                .hasSize(1);

        String parent = fish.path("id").asString();
        for (int level = 3; level <= 5; level++) {
            parent = json(api.post(
                            "/api/v1/categories",
                            "{\"name\": \"Level " + level + "\", \"parentId\": \"" + parent + "\"}"))
                    .path("id")
                    .asString();
        }
        assertThatProblem(api.post("/api/v1/categories", "{\"name\": \"Too deep\", \"parentId\": \"" + parent + "\"}"))
                .hasStatus(422)
                .hasCode("CATEGORY_TOO_DEEP");
        MvcTestResult deactivated = api.patch(foodPath, "{\"status\": \"INACTIVE\"}", etag(api.get(foodPath)));
        assertThat(json(deactivated).path("status").asString()).isEqualTo("INACTIVE");
        assertThatProblem(api.get("/api/v1/categories/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("CATEGORY_NOT_FOUND");
    }
}
