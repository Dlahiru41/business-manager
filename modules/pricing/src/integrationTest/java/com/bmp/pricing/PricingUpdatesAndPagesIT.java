package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.EGGS;
import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.json;
import static com.bmp.pricing.PricingApi.row;
import static com.bmp.pricing.PricingApi.unit;
import static com.bmp.pricing.PricingApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.time.MutableClock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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

/** Partial updates leave the other fields alone; lists page with cursors and filter as documented. */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class PricingUpdatesAndPagesIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    MutableClock clock;

    private PricingApi api;

    @BeforeEach
    void setUp() {
        api = new PricingApi(mvc, jdbc, transactions, "Updates");
    }

    private List<String> codes(String path) {
        List<String> codes = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(api.get(
                    path + (path.contains("?") ? "&" : "?") + "limit=1" + (cursor != null ? "&cursor=" + cursor : "")));
            page.path("items").forEach(i -> codes.add(i.path("code").asString()));
            cursor = page.path("page").path("hasMore").asBoolean()
                    ? page.path("page").path("nextCursor").asString()
                    : null;
        } while (cursor != null);
        return codes;
    }

    @Test
    void priceListsPageByPriorityAndKeepUnchangedFields() {
        MvcTestResult staff =
                api.post("/api/v1/price-lists", "{\"code\": \"STAFF\", \"name\": \"Staff\", \"channel\": \"ANY\"}");
        String path = "/api/v1/price-lists/" + json(staff).path("id").asString();

        assertThat(json(staff).path("priority").asInt()).isEqualTo(100);
        assertThat(json(staff).path("taxInclusive").asBoolean())
                .as("tenant default")
                .isTrue();
        assertThat(json(api.patch(path, "{\"name\": \"Staff prices\"}", etag(api.get(path))))
                        .path("priority")
                        .asInt())
                .isEqualTo(100);
        assertThat(json(api.patch(path, "{\"priority\": 1}", etag(api.get(path))))
                        .path("name")
                        .asString())
                .isEqualTo("Staff prices");
        api.patch(path, "{\"status\": \"INACTIVE\"}", etag(api.get(path)));
        assertThat(json(api.patch(path, "{\"status\": \"ACTIVE\"}", etag(api.get(path))))
                        .path("status")
                        .asString())
                .isEqualTo("ACTIVE");
        assertThat(codes("/api/v1/price-lists")).containsExactly("STAFF", "RETAIL", "WHOLESALE");
        assertThat(codes("/api/v1/price-lists?status=ACTIVE&channel=ANY")).containsExactly("STAFF");
        assertThatProblem(api.get("/api/v1/price-lists?cursor=bm90LWEtY3Vyc29y"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void rulesChangeFieldByFieldAndPageByPriority() {
        String path = "/api/v1/price-rules/"
                + json(api.post("/api/v1/price-rules", """
                        {"code": "A", "name": "A", "kind": "PROMOTION", "effect": {"type": "PERCENT_OFF", "value": "10"},
                         "priority": 5}
                        """)).path("id").asString();
        api.post("/api/v1/price-rules", """
                {"code": "B", "name": "B", "kind": "MARKUP", "effect": {"type": "MARKUP_PERCENT", "value": "1"}, "priority": 1}
                """);

        for (String change : List.of(
                "{\"name\": \"Ten off\"}",
                "{\"priority\": 7}",
                "{\"stackable\": true}",
                "{\"validFrom\": \"2026-01-01T00:00:00Z\"}",
                "{\"validTo\": \"2027-01-01T00:00:00Z\"}",
                "{\"conditions\": {\"minQtyBase\": \"12\"}}",
                "{\"effect\": {\"type\": \"FIXED_PRICE\", \"value\": \"40\"}}",
                "{\"status\": \"INACTIVE\"}",
                "{\"status\": \"ACTIVE\"}")) {
            assertThat(api.patch(path, change, etag(api.get(path)))).as(change).hasStatusOk();
        }

        JsonNode rule = json(api.get(path));
        assertThat(rule.path("name").asString()).isEqualTo("Ten off");
        assertThat(rule.path("priority").asInt()).isEqualTo(7);
        assertThat(rule.path("stackable").asBoolean()).isTrue();
        assertThat(rule.path("validFrom").asString()).startsWith("2026-01-01");
        assertThat(rule.path("validTo").asString()).startsWith("2027-01-01");
        assertThat(rule.path("conditions").path("minQtyBase").asString()).isEqualTo("12");
        assertThat(rule.path("effect").path("type").asString()).isEqualTo("FIXED_PRICE");
        assertThat(rule.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(codes("/api/v1/price-rules")).containsExactly("B", "A");
        assertThat(codes("/api/v1/price-rules?kind=PROMOTION&status=ACTIVE")).containsExactly("A");
    }

    @Test
    void taxesChangeFieldByFieldAndPageByCode() {
        String first = json(api.post("/api/v1/tax-categories", "{\"code\": \"B_VAT\", \"name\": \"VAT\"}"))
                .path("id")
                .asString();
        api.post("/api/v1/tax-categories", "{\"code\": \"A_EXEMPT\", \"name\": \"Exempt\"}");
        String category = "/api/v1/tax-categories/" + first;

        api.patch(category, "{\"status\": \"INACTIVE\"}", etag(api.get(category)));
        JsonNode reactivated = json(api.patch(category, "{\"status\": \"ACTIVE\"}", etag(api.get(category))));
        assertThat(reactivated.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(reactivated.path("name").asString()).isEqualTo("VAT");
        String rate = "/api/v1/tax-rates/"
                + json(api.post(
                                "/api/v1/tax-rates",
                                "{\"taxCategoryId\": \"" + first
                                        + "\", \"code\": \"VAT\", \"name\": \"VAT\", \"ratePct\": \"18\","
                                        + " \"validFrom\": \"2026-01-01\", \"compound\": false}"))
                        .path("id")
                        .asString();
        assertThat(json(api.patch(rate, "{\"name\": \"Value added tax\"}", etag(api.get(rate))))
                        .has("validTo"))
                .isFalse();
        assertThat(codes("/api/v1/tax-categories")).containsExactly("A_EXEMPT", "B_VAT");
        JsonNode rates = json(api.get("/api/v1/tax-rates?limit=1"));
        assertThat(rates.path("items")).hasSize(1);
    }

    @Test
    void versionsPageNewestFirstAndRowsMayNameAMinimumQuantityAndTheMomentToPriceAt() {
        JsonNode redL = variant(api.product(EGGS), "EGG-RED-L");
        String prices = "/api/v1/price-lists/" + api.list("WHOLESALE") + "/prices";
        for (String amount : List.of("48.00", "47.00", "46.00")) {
            clock.advance(Duration.ofMinutes(1));
            api.put(prices, "{\"prices\": [" + row(redL, "piece", amount) + "]}", etag(api.get(prices)));
        }
        api.put(
                prices,
                "{\"prices\": [{\"variantId\": \"" + redL.path("id").asString() + "\", \"packagingUnitId\": \""
                        + unit(redL, "piece") + "\", \"minQty\": \"300\", \"amount\": \"44.00\"}]}",
                etag(api.get(prices)));

        List<Integer> versions = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(api.get(
                    prices.replace("/prices", "/versions") + "?limit=3" + (cursor != null ? "&cursor=" + cursor : "")));
            page.path("items").forEach(v -> versions.add(v.path("version").asInt()));
            cursor = page.path("page").path("hasMore").asBoolean()
                    ? page.path("page").path("nextCursor").asString()
                    : null;
        } while (cursor != null);
        assertThat(versions).containsExactly(4, 3, 2, 1);
        JsonNode bulk = json(api.post("/api/v1/prices:resolve", """
                {"channel": "WHOLESALE", "at": "%s", "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "300"}]}
                """.formatted(
                        clock.instant(), redL.path("id").asString(), unit(redL, "piece"))));
        assertThat(bulk.path("lines").get(0).path("unitPrice").path("amount").asString())
                .isEqualTo("44.00");
        JsonNode earlier = json(api.post("/api/v1/prices:resolve", """
                {"channel": "WHOLESALE", "at": "%s", "lines": [{"variantId": "%s", "packagingUnitId": "%s", "qty": "1"}]}
                """.formatted(
                clock.instant().minus(Duration.ofSeconds(30)), redL.path("id").asString(), unit(redL, "piece"))));
        assertThat(earlier.path("lines").get(0).path("unitPrice").path("amount").asString())
                .as("the price valid half a minute earlier")
                .isEqualTo("47.00");
    }

    @Test
    void platformAdministratorsMayChangePricesWhenOnlyOwnersMay() {
        MvcTestResult settings = api.client.get("/api/v1/settings/pricing", api.owner);
        api.client.patch(
                "/api/v1/settings/pricing",
                api.owner,
                "{\"values\": {\"priceChangesRequireOwner\": true}}",
                etag(settings));
        MvcTestResult bySupport = mvc.post()
                .uri("/api/v1/price-lists")
                .header("Authorization", "Bearer " + JwtTestTokens.platformAdmin())
                .header("X-Tenant-Id", api.tenant.toString())
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType("application/json")
                .content("{\"code\": \"ADMIN\", \"name\": \"Set up by support\", \"channel\": \"ANY\"}")
                .exchange();

        assertThat(bySupport).hasStatus(201);
    }
}
