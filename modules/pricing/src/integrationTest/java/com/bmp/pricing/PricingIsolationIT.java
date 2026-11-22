package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.EGGS;
import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.json;
import static com.bmp.pricing.PricingApi.row;
import static com.bmp.pricing.PricingApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** Tenant A's pricing is invisible to tenant B through the API and SQL; RLS covers the schema (06 par. 10). */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PricingIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Test
    void pricingSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(List.of("pricing"), List.of("pricing.price_list_version"));
    }

    @Test
    void anotherTenantSeesNoneOfThePricing() {
        PricingApi a = new PricingApi(mvc, jdbc, transactions, "Pricing isolation A");
        PricingApi b = new PricingApi(mvc, jdbc, transactions, "Pricing isolation B");
        JsonNode redL = variant(a.product(EGGS), "EGG-RED-L");
        String retail = a.list("RETAIL");
        String prices = "/api/v1/price-lists/" + retail + "/prices";
        a.put(prices, "{\"prices\": [" + row(redL, "piece", "55.00") + "]}", etag(a.get(prices)));
        String rule = json(a.post("/api/v1/price-rules", """
                        {"code": "R", "name": "R", "kind": "MARKUP", "effect": {"type": "MARKUP_PERCENT", "value": "1"}}
                        """)).path("id").asString();
        String category = json(a.post("/api/v1/tax-categories", "{\"code\": \"VAT\", \"name\": \"VAT\"}"))
                .path("id")
                .asString();

        assertThatProblem(b.get("/api/v1/price-lists/" + retail)).hasStatus(404).hasCode("PRICE_LIST_NOT_FOUND");
        assertThatProblem(b.get(prices)).hasStatus(404);
        assertThatProblem(b.get("/api/v1/price-lists/" + retail + "/snapshot")).hasStatus(404);
        assertThatProblem(b.get("/api/v1/price-rules/" + rule)).hasStatus(404);
        assertThatProblem(b.get("/api/v1/tax-categories/" + category)).hasStatus(404);
        assertThat(json(b.get("/api/v1/price-rules")).path("items")).isEmpty();
        assertThat(json(b.get("/api/v1/tax-categories")).path("items")).isEmpty();
        assertThat(json(b.post(
                                "/api/v1/prices:resolve",
                                "{\"channel\": \"RETAIL\", \"lines\": [{\"variantId\": \""
                                        + redL.path("id").asString() + "\", \"packagingUnitId\": \""
                                        + PricingApi.unit(redL, "piece") + "\", \"qty\": \"1\"}]}"))
                        .path("lines")
                        .get(0)
                        .path("status")
                        .asString())
                .as("another tenant's variant is unknown here")
                .isEqualTo("NOT_SELLABLE");
        for (String table : List.of(
                "price_list", "price", "price_list_version", "price_rule", "tax_category", "variant_price_ref")) {
            assertThat(b.<Long>sql(j -> j.sql("SELECT count(*) FROM pricing." + table + " WHERE tenant_id = :a")
                            .param("a", a.tenant.value())
                            .query(Long.class)
                            .single()))
                    .as(table)
                    .isZero();
        }
        assertThat(b.<Long>sql(j -> j.sql("SELECT count(*) FROM pricing.price_list")
                        .query(Long.class)
                        .single()))
                .isEqualTo(2L);
        assertThat(UUID.fromString(b.list("RETAIL"))).isNotEqualTo(UUID.fromString(retail));
    }
}
