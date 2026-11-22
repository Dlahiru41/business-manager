package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.EGGS;
import static com.bmp.catalog.CatalogApi.etag;
import static com.bmp.catalog.CatalogApi.json;
import static com.bmp.catalog.CatalogApi.unit;
import static com.bmp.catalog.CatalogApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.api.events.TransformationDefinitionRetired;
import com.bmp.test.persistence.WithPostgres;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Transformation definitions: drafts, immutable published versions, retirement (CAT-10, CAT-11). */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class DefinitionApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private CatalogApi api;
    private String egg;
    private String tray;

    @BeforeEach
    void setUp() {
        api = new CatalogApi(mvc, "Definitions");
        JsonNode eggs = api.create(EGGS);
        JsonNode redL = variant(eggs, "EGG-RED-L");
        egg = redL.path("id").asString();
        tray = unit(redL, "tray").path("id").asString();
    }

    private String breakTray(String code) {
        return """
                {"code": "%s", "name": "Break tray", "kind": "BREAK",
                 "lines": [{"direction": "IN", "variantId": "%s", "packagingUnitId": "%s"},
                           {"direction": "OUT", "variantId": "%s", "qtyBase": "30"}]}
                """.formatted(code, egg, tray, egg);
    }

    @Test
    void cat10PublishingFreezesAVersionAndEditingStartsTheNextDraft() {
        MvcTestResult created = api.post("/api/v1/transformation-definitions", breakTray("egg-break-tray"));
        assertThat(created).hasStatus(201);
        JsonNode draft = json(created);
        String path = "/api/v1/transformation-definitions/" + draft.path("id").asString();
        assertThat(draft.path("code").asString()).isEqualTo("EGG-BREAK-TRAY");
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("defVersion").asInt()).isZero();
        assertThat(draft.path("lines").get(0).path("qtyBase").asString())
                .as("sized by the tray")
                .isEqualTo("30");
        assertThat(draft.path("lines").get(0).path("stockState").asString()).isEqualTo("DEFAULT");
        assertThat(draft.path("lines").get(1).path("costSharePct").asString()).isEqualTo("100");

        JsonNode published = json(api.action(path + "/publish"));
        assertThat(published.path("status").asString()).isEqualTo("PUBLISHED");
        assertThat(published.path("defVersion").asInt()).isEqualTo(1);
        assertThatProblem(api.action(path + "/publish")).hasStatus(409).hasCode("DEFINITION_ALREADY_PUBLISHED");

        MvcTestResult edited = api.patch(path, "{\"expectedYieldPct\": \"98.5\"}", etag(api.get(path)));
        assertThat(json(edited).path("status").asString()).isEqualTo("DRAFT");
        assertThat(json(edited).path("defVersion").asInt()).isEqualTo(1);
        assertThat(json(api.action(path + "/publish")).path("defVersion").asInt())
                .isEqualTo(2);
        assertThat(versionsLogged(draft.path("id").asString()))
                .as("frozen versions")
                .isEqualTo(2);
    }

    @Test
    void cat11RetiredDefinitionsCanNoLongerChange() {
        String path = "/api/v1/transformation-definitions/"
                + json(api.post("/api/v1/transformation-definitions", breakTray("RETIRE-ME")))
                        .path("id")
                        .asString();

        assertThat(json(api.action(path + "/retire")).path("status").asString()).isEqualTo("RETIRED");
        assertThat(json(api.action(path + "/retire")).path("status").asString()).isEqualTo("RETIRED");
        assertThat(events.stream(TransformationDefinitionRetired.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(1);
        assertThatProblem(api.patch(path, "{\"name\": \"Again\"}", etag(api.get(path))))
                .hasStatus(409)
                .hasCode("DEFINITION_RETIRED");
        assertThatProblem(api.action(path + "/publish")).hasStatus(409).hasCode("DEFINITION_RETIRED");
    }

    @Test
    void cat10RefusesInvalidLines() {
        String definitions = "/api/v1/transformation-definitions";
        assertThatProblem(api.post(definitions, """
                        {"code": "ONLY-IN", "name": "x", "kind": "BREAK",
                         "lines": [{"direction": "IN", "variantId": "%s", "qtyBase": "30"}]}
                        """.formatted(egg)))
                .hasStatus(422)
                .hasCode("DEFINITION_LINES_INVALID");
        assertThatProblem(api.post(definitions, """
                        {"code": "SHARES", "name": "x", "kind": "BREAK",
                         "lines": [{"direction": "IN", "variantId": "%s", "qtyBase": "30"},
                                   {"direction": "OUT", "variantId": "%s", "qtyBase": "20", "costSharePct": "60"},
                                   {"direction": "OUT", "variantId": "%s", "qtyBase": "10", "costSharePct": "30"}]}
                        """.formatted(egg, egg, egg)))
                .hasStatus(422)
                .hasCode("COST_SHARE_INVALID");
        assertThatProblem(api.post(definitions, """
                        {"code": "GHOST", "name": "x", "kind": "BREAK",
                         "lines": [{"direction": "IN", "variantId": "%s", "qtyBase": "30"},
                                   {"direction": "OUT", "variantId": "%s", "qtyBase": "30"}]}
                        """.formatted(UUID.randomUUID(), egg)))
                .hasStatus(422)
                .hasCode("UNKNOWN_VARIANT");
        assertThatProblem(api.post(definitions, """
                        {"code": "STATE", "name": "x", "kind": "BREAK",
                         "lines": [{"direction": "IN", "variantId": "%s", "qtyBase": "30", "stockState": "OPEN"},
                                   {"direction": "OUT", "variantId": "%s", "qtyBase": "30"}]}
                        """.formatted(egg, egg)))
                .hasStatus(422)
                .hasCode("INVALID_STOCK_STATE");
        assertThatProblem(api.post(definitions, """
                        {"code": "ZERO", "name": "x", "kind": "BREAK",
                         "lines": [{"direction": "IN", "variantId": "%s", "qtyBase": "0"},
                                   {"direction": "OUT", "variantId": "%s", "qtyBase": "30"}]}
                        """.formatted(egg, egg)))
                .hasStatus(422)
                .hasCode("INVALID_QTY_IN_BASE");
        assertThatProblem(api.post(definitions, """
                        {"code": "NOQTY", "name": "x", "kind": "BREAK",
                         "lines": [{"direction": "IN", "variantId": "%s"},
                                   {"direction": "OUT", "variantId": "%s", "qtyBase": "30"}]}
                        """.formatted(egg, egg)))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThat(api.post(definitions, breakTray("TWICE"))).hasStatus(201);
        assertThatProblem(api.post(definitions, breakTray("twice")))
                .hasStatus(409)
                .hasCode("DEFINITION_CODE_TAKEN");
        assertThatProblem(api.get(definitions + "/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("TRANSFORMATION_DEFINITION_NOT_FOUND");
        assertThat(json(api.get(definitions + "?status=DRAFT")).path("items")).hasSize(1);
    }

    private long versionsLogged(String definitionId) {
        Long count = com.bmp.shared.context.TenantContext.callWith(
                api.tenant,
                () -> new TransactionTemplate(transactionManager)
                        .execute(s -> jdbc.sql(
                                        "SELECT count(*) FROM catalog.transformation_definition_version WHERE definition_id = :id")
                                .param("id", UUID.fromString(definitionId))
                                .query(Long.class)
                                .single()));
        return count != null ? count : 0;
    }
}
