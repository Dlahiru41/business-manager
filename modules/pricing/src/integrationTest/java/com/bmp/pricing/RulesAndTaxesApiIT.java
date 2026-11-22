package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.WithPostgres;
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

/** Price rules (PRC-06) and dated tax rates (PRC-07) through the API. */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class RulesAndTaxesApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private PricingApi api;

    @BeforeEach
    void setUp() {
        api = new PricingApi(mvc, jdbc, transactions, "Rules and taxes");
    }

    @Test
    void prc06RulesAreCreatedChangedAndDeactivated() {
        MvcTestResult created = api.post("/api/v1/price-rules", """
                {"code": "weekend", "name": "Weekend", "kind": "PROMOTION",
                 "effect": {"type": "PERCENT_OFF", "value": "10"}, "stackable": true,
                 "validFrom": "2026-11-21T00:00:00Z", "validTo": "2026-11-23T00:00:00Z"}
                """);

        assertThat(created).hasStatus(201);
        JsonNode rule = json(created);
        assertThat(rule.path("code").asString()).isEqualTo("WEEKEND");
        assertThat(rule.path("conditions").path("productIds")).isEmpty();
        assertThat(rule.path("effect").path("value").asString()).isEqualTo("10");
        String path = "/api/v1/price-rules/" + rule.path("id").asString();
        MvcTestResult changed = api.patch(
                path,
                "{\"effect\": {\"type\": \"AMOUNT_OFF\", \"value\": \"5\"}, \"priority\": 3, \"status\": \"INACTIVE\","
                        + " \"conditions\": {\"channel\": \"RETAIL\"}}",
                etag(api.get(path)));
        assertThat(json(changed).path("effect").path("type").asString()).isEqualTo("AMOUNT_OFF");
        assertThat(json(changed).path("status").asString()).isEqualTo("INACTIVE");
        assertThat(json(changed).path("conditions").path("channel").asString()).isEqualTo("RETAIL");
        assertThat(json(api.get("/api/v1/price-rules?status=INACTIVE")).path("items"))
                .hasSize(1);
        assertThat(json(api.get("/api/v1/price-rules?kind=MARKUP")).path("items"))
                .isEmpty();

        assertThatProblem(api.post("/api/v1/price-rules", """
                        {"code": "WEEKEND", "name": "Again", "kind": "PROMOTION", "effect": {"type": "PERCENT_OFF", "value": "1"}}
                        """)).hasStatus(409).hasCode("PRICE_RULE_CODE_TAKEN");
        assertThatProblem(api.post("/api/v1/price-rules", """
                        {"code": "HUGE", "name": "Huge", "kind": "PROMOTION", "effect": {"type": "PERCENT_OFF", "value": "150"}}
                        """)).hasStatus(422).hasCode("INVALID_RULE_EFFECT");
        assertThatProblem(api.post("/api/v1/price-rules", """
                        {"code": "BACK", "name": "Back", "kind": "MARKUP", "effect": {"type": "MARKUP_PERCENT", "value": "5"},
                         "validFrom": "2026-12-01T00:00:00Z", "validTo": "2026-11-01T00:00:00Z"}
                        """)).hasStatus(422).hasCode("INVALID_VALIDITY");
        assertThatProblem(api.get("/api/v1/price-rules/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PRICE_RULE_NOT_FOUND");
    }

    @Test
    void prc07RatesAreDatedAndNeverOverlapForOneCode() {
        MvcTestResult category = api.post("/api/v1/tax-categories", "{\"code\": \"vat_standard\", \"name\": \"VAT\"}");
        assertThat(category).hasStatus(201);
        String id = json(category).path("id").asString();
        assertThatProblem(api.post("/api/v1/tax-categories", "{\"code\": \"VAT_STANDARD\", \"name\": \"Again\"}"))
                .hasStatus(409)
                .hasCode("TAX_CATEGORY_CODE_TAKEN");
        String categoryPath = "/api/v1/tax-categories/" + id;
        assertThat(json(api.patch(categoryPath, "{\"name\": \"VAT standard\"}", etag(api.get(categoryPath))))
                        .path("name")
                        .asString())
                .isEqualTo("VAT standard");

        MvcTestResult old = api.post(
                "/api/v1/tax-rates",
                "{\"taxCategoryId\": \"" + id + "\", \"code\": \"VAT\", \"name\": \"VAT 15\", \"ratePct\": \"15\","
                        + " \"validFrom\": \"2025-01-01\", \"validTo\": \"2026-01-01\"}");
        MvcTestResult current = api.post(
                "/api/v1/tax-rates",
                "{\"taxCategoryId\": \"" + id + "\", \"code\": \"VAT\", \"name\": \"VAT 18\", \"ratePct\": \"18\","
                        + " \"validFrom\": \"2026-01-01\"}");

        assertThat(old).hasStatus(201);
        assertThat(current).hasStatus(201);
        assertThatProblem(api.post(
                        "/api/v1/tax-rates",
                        "{\"taxCategoryId\": \"" + id
                                + "\", \"code\": \"VAT\", \"name\": \"VAT 20\", \"ratePct\": \"20\","
                                + " \"validFrom\": \"2026-06-01\"}"))
                .hasStatus(409)
                .hasCode("TAX_RATE_OVERLAP");
        assertThatProblem(api.post(
                        "/api/v1/tax-rates",
                        "{\"taxCategoryId\": \"" + UUID.randomUUID() + "\", \"code\": \"VAT\", \"name\": \"x\","
                                + " \"ratePct\": \"18\", \"validFrom\": \"2026-01-01\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_TAX_CATEGORY");
        String ratePath = "/api/v1/tax-rates/" + json(current).path("id").asString();
        MvcTestResult ended = api.patch(ratePath, "{\"validTo\": \"2027-01-01\"}", etag(api.get(ratePath)));
        assertThat(json(ended).path("validTo").asString()).isEqualTo("2027-01-01");
        assertThatProblem(api.patch(ratePath, "{\"validTo\": \"2025-06-01\"}", etag(api.get(ratePath))))
                .hasStatus(422)
                .hasCode("INVALID_VALIDITY");
        String oldPath = "/api/v1/tax-rates/" + json(old).path("id").asString();
        assertThatProblem(api.patch(oldPath, "{\"validTo\": \"2026-03-01\"}", etag(api.get(oldPath))))
                .as("extending the old rate into the new one")
                .hasStatus(409)
                .hasCode("TAX_RATE_OVERLAP");
        assertThat(json(api.get("/api/v1/tax-rates?taxCategoryId=" + id)).path("items"))
                .hasSize(2);
        assertThat(json(api.get("/api/v1/tax-categories")).path("items")).hasSize(1);
        assertThatProblem(api.get("/api/v1/tax-rates/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("TAX_RATE_NOT_FOUND");
        assertThatProblem(api.get("/api/v1/tax-categories/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("TAX_CATEGORY_NOT_FOUND");
    }
}
