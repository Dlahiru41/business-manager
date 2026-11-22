package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.EGGS;
import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.json;
import static com.bmp.pricing.PricingApi.row;
import static com.bmp.pricing.PricingApi.variant;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.pricing.api.events.PriceListChanged;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.time.MutableClock;
import java.time.Duration;
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
import tools.jackson.databind.JsonNode;

/** Price lists, bulk price upserts, versions and snapshots (PRC-01..PRC-03, PRC-08, PRC-09). */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class PriceApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    ApplicationEvents events;

    @Autowired
    MutableClock clock;

    private PricingApi api;
    private JsonNode redL;
    private String prices;

    @BeforeEach
    void setUp() {
        api = new PricingApi(mvc, jdbc, transactions, "Prices");
        redL = variant(api.product(EGGS), "EGG-RED-L");
        prices = "/api/v1/price-lists/" + api.list("WHOLESALE") + "/prices";
    }

    private MvcTestResult upsert(String rows) {
        return api.put(prices, "{\"note\": \"placeholders\", \"prices\": [" + rows + "]}", etag(api.get(prices)));
    }

    @Test
    void prc01EveryTenantStartsWithRetailAndWholesaleLists() {
        JsonNode lists = json(api.get("/api/v1/price-lists")).path("items");

        assertThat(lists).extracting(l -> l.path("code").asString()).containsExactly("RETAIL", "WHOLESALE");
        assertThat(lists).allSatisfy(l -> {
            assertThat(l.path("currency").asString()).isEqualTo("LKR");
            assertThat(l.path("taxInclusive").asBoolean()).isTrue();
            assertThat(l.path("currentVersion").asInt()).isZero();
        });
        assertThat(json(api.get("/api/v1/price-lists?channel=WHOLESALE")).path("items"))
                .hasSize(1);
    }

    @Test
    void prc01ListsAreForAGroupOrACustomerAndTheirCodesAreUnique() {
        MvcTestResult hotels = api.post(
                "/api/v1/price-lists",
                "{\"code\": \"hotels\", \"name\": \"Hotels\", \"channel\": \"WHOLESALE\", \"customerGroupId\": \""
                        + UUID.randomUUID() + "\", \"priority\": 50, \"taxInclusive\": false}");

        assertThat(hotels).hasStatus(201);
        assertThat(json(hotels).path("code").asString()).isEqualTo("HOTELS");
        assertThat(json(hotels).path("taxInclusive").asBoolean()).isFalse();
        assertThatProblem(api.post(
                        "/api/v1/price-lists", "{\"code\": \"HOTELS\", \"name\": \"x\", \"channel\": \"ANY\"}"))
                .hasStatus(409)
                .hasCode("PRICE_LIST_CODE_TAKEN");
        assertThatProblem(api.post(
                        "/api/v1/price-lists",
                        "{\"code\": \"BOTH\", \"name\": \"x\", \"channel\": \"ANY\", \"customerGroupId\": \""
                                + UUID.randomUUID() + "\", \"customerId\": \"" + UUID.randomUUID() + "\"}"))
                .hasStatus(422)
                .hasCode("INVALID_PRICE_LIST_SCOPE");
        String path = "/api/v1/price-lists/" + json(hotels).path("id").asString();
        MvcTestResult renamed = api.patch(path, "{\"priority\": 40, \"status\": \"INACTIVE\"}", etag(api.get(path)));
        assertThat(json(renamed).path("priority").asInt()).isEqualTo(40);
        assertThat(json(renamed).path("status").asString()).isEqualTo("INACTIVE");
        assertThatProblem(api.patch(path, "{\"name\": \"Stale\"}", "W/\"0\""))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
        assertThatProblem(api.patch(path, "{\"name\": \"None\"}", null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        assertThatProblem(api.get("/api/v1/price-lists/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PRICE_LIST_NOT_FOUND");
    }

    @Test
    void prc02Prc03AnUpsertIsOneNewVersionWithItsChangeSummaryAndEvent() {
        MvcTestResult first = upsert(row(redL, "piece", "48.00") + "," + row(redL, "tray", "1440.00"));

        assertThat(first).hasStatusOk().hasHeader("ETag", "W/\"1\"");
        assertThat(json(first).path("version").asInt()).isEqualTo(1);
        assertThat(json(first).path("changes"))
                .extracting(c -> c.path("change").asString())
                .containsExactly("CREATED", "CREATED");
        clock.advance(Duration.ofHours(1));
        MvcTestResult second = upsert(row(redL, "piece", "49.00"));
        assertThat(json(second).path("changes"))
                .extracting(c -> c.path("change").asString())
                .containsExactly("ENDED", "CREATED");

        JsonNode current =
                json(api.get(prices + "?at=" + clock.instant() + "&unitId=" + PricingApi.unit(redL, "piece")));
        assertThat(current.path("items")).singleElement().satisfies(p -> {
            assertThat(p.path("amount").path("amount").asString()).isEqualTo("49.00");
            assertThat(p.path("listVersion").asInt()).isEqualTo(2);
        });
        assertThat(json(api.get(prices)).path("items")).hasSize(3);
        JsonNode versions = json(api.get(prices.replace("/prices", "/versions")));
        assertThat(versions.path("items"))
                .extracting(v -> v.path("version").asInt())
                .containsExactly(2, 1);
        assertThat(versions.path("items").get(0).has("changes")).isFalse();
        JsonNode one = json(api.get(prices.replace("/prices", "/versions/1")));
        assertThat(one.path("note").asString()).isEqualTo("placeholders");
        assertThat(one.path("changes")).hasSize(2);
        assertThatProblem(api.get(prices.replace("/prices", "/versions/9")))
                .hasStatus(404)
                .hasCode("PRICE_LIST_VERSION_NOT_FOUND");
        assertThat(json(api.get(prices.replace("/prices", "")))
                        .path("currentVersion")
                        .asInt())
                .isEqualTo(2);
        assertThat(events.stream(PriceListChanged.class)
                        .filter(e -> e.tenantId().equals(api.tenant)))
                .extracting(PriceListChanged::newVersion)
                .containsExactly(1, 2);
    }

    @Test
    void prc02OverlapsDuplicatesAndUnknownUnitsAreRefusedAndNothingChanges() {
        String future = "2027-01-01T00:00:00Z";
        String scheduled = "{\"variantId\": \"" + redL.path("id").asString() + "\", \"packagingUnitId\": \""
                + PricingApi.unit(redL, "piece") + "\", \"amount\": \"50.00\", \"validFrom\": \"" + future + "\"}";
        assertThat(upsert(scheduled)).hasStatusOk();

        assertThatProblem(upsert(row(redL, "piece", "48.00"))).hasStatus(409).hasCode("PRICE_OVERLAP");
        assertThatProblem(upsert(row(redL, "tray", "1.00") + "," + row(redL, "tray", "2.00")))
                .hasStatus(422)
                .hasCode("DUPLICATE_PRICE_ROWS");
        assertThatProblem(upsert("{\"variantId\": \"" + UUID.randomUUID() + "\", \"packagingUnitId\": \""
                        + UUID.randomUUID() + "\", \"amount\": \"1\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_VARIANT");
        assertThatProblem(upsert("{\"variantId\": \"" + redL.path("id").asString() + "\", \"packagingUnitId\": \""
                        + UUID.randomUUID() + "\", \"amount\": \"1\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_PACKAGING_UNIT");
        assertThatProblem(upsert("{\"variantId\": \"" + redL.path("id").asString() + "\", \"packagingUnitId\": \""
                        + PricingApi.unit(redL, "tray") + "\", \"amount\": \"1\", \"validFrom\": \"" + future
                        + "\", \"validTo\": \"" + future + "\"}"))
                .hasStatus(422)
                .hasCode("INVALID_VALIDITY");
        assertThatProblem(api.put(prices, "{\"prices\": [" + row(redL, "tray", "1") + "]}", "W/\"0\""))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
        assertThatProblem(api.put(prices, "{\"prices\": [" + row(redL, "tray", "1") + "]}", null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        assertThat(json(api.get(prices.replace("/prices", "")))
                        .path("currentVersion")
                        .asInt())
                .as("refused upserts take no version")
                .isEqualTo(1);
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 1001; i++) {
            many.append(i == 0 ? "" : ",").append(row(redL, "piece", String.valueOf(i)));
        }
        assertThatProblem(api.client.put(prices, api.owner, "{\"prices\": [" + many + "]}", "W/\"1\""))
                .as("at most 1,000 rows")
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
    }

    @Test
    void anUpsertIsReplayedForTheSameIdempotencyKey() {
        String key = UUID.randomUUID().toString();
        String body = "{\"prices\": [" + row(redL, "piece", "48.00") + "]}";

        MvcTestResult first = api.client.put(prices, api.owner, body, "W/\"0\"", key);
        MvcTestResult replay = api.client.put(prices, api.owner, body, "W/\"0\"", key);

        assertThat(first).hasStatusOk();
        assertThat(replay).hasStatusOk().hasHeader("Idempotent-Replayed", "true");
        assertThat(json(api.get(prices.replace("/prices", "")))
                        .path("currentVersion")
                        .asInt())
                .isEqualTo(1);
    }

    @Test
    void prc09SnapshotsHoldEveryLivePriceThenOnlyChangesAndAnswer304WhenUnchanged() {
        upsert(row(redL, "piece", "48.00") + "," + row(redL, "tray", "1440.00"));
        String snapshot = prices.replace("/prices", "/snapshot");

        MvcTestResult full = api.get(snapshot);
        assertThat(full).hasHeader("ETag", "W/\"1\"");
        assertThat(json(full).path("full").asBoolean()).isTrue();
        assertThat(json(full).path("prices")).hasSize(2);
        assertThat(api.client.getIfNoneMatch(snapshot, api.owner, "W/\"1\"")).hasStatus(304);

        clock.advance(Duration.ofMinutes(5));
        upsert(row(redL, "piece", "49.00"));
        JsonNode delta = json(api.get(snapshot + "?sinceVersion=1"));
        assertThat(delta.path("full").asBoolean()).isFalse();
        assertThat(delta.path("version").asInt()).isEqualTo(2);
        assertThat(delta.path("prices")).as("the ended price and its successor").hasSize(2);
        assertThat(json(api.get(snapshot)).path("prices"))
                .as("ended prices leave the full snapshot")
                .hasSize(2);
    }

    @Test
    void prc08ATenantMayLetOnlyOwnersChangePrices() {
        String manager = api.token(SystemRole.MANAGER);
        String body = "{\"prices\": [" + row(redL, "piece", "48.00") + "]}";
        assertThat(api.client.put(prices, manager, body, "W/\"0\"")).hasStatusOk();

        MvcTestResult settings = api.client.get("/api/v1/settings/pricing", api.owner);
        assertThat(api.client.patch(
                        "/api/v1/settings/pricing",
                        api.owner,
                        "{\"values\": {\"priceChangesRequireOwner\": true}}",
                        etag(settings)))
                .hasStatusOk();

        assertThatProblem(api.client.put(prices, manager, body, "W/\"1\""))
                .hasStatus(403)
                .hasCode("PRICE_CHANGE_RESTRICTED");
        assertThatProblem(api.client.post(
                        "/api/v1/price-lists",
                        manager,
                        "{\"code\": \"STAFF\", \"name\": \"Staff\", \"channel\": \"ANY\"}"))
                .hasStatus(403)
                .hasCode("PRICE_CHANGE_RESTRICTED");
        assertThat(api.client.put(prices, api.owner, "{\"prices\": [" + row(redL, "piece", "49.00") + "]}", "W/\"1\""))
                .hasStatusOk();
        assertThatProblem(api.client.put(prices, api.token(SystemRole.CASHIER), body, "W/\"2\""))
                .as("cashiers lack pricing:write in any case")
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
    }
}
