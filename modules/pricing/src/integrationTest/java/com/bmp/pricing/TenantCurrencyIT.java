package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.EGGS;
import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.json;
import static com.bmp.pricing.PricingApi.row;
import static com.bmp.pricing.PricingApi.unit;
import static com.bmp.pricing.PricingApi.variant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.test.persistence.WithPostgres;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;

/** A tenant currency corrected before business starts (TEN-02): price lists and resolution stay consistent. */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class TenantCurrencyIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    private static Map<String, String> currencies(PricingApi api) {
        Map<String, String> currencies = new HashMap<>();
        json(api.get("/api/v1/price-lists"))
                .path("items")
                .forEach(l -> currencies.put(
                        l.path("code").asString(), l.path("currency").asString()));
        return currencies;
    }

    @Test
    void neverPricedListsFollowTheNewCurrencyAndListsWithPricesStopPricingInsteadOfFailing() {
        PricingApi api = new PricingApi(mvc, jdbc, transactions, "Currency");
        JsonNode redL = variant(api.product(EGGS), "EGG-RED-L");
        String wholesale = "/api/v1/price-lists/" + api.list("WHOLESALE") + "/prices";
        assertThat(api.put(wholesale, "{\"prices\": [" + row(redL, "piece", "48.00") + "]}", etag(api.get(wholesale))))
                .hasStatusOk();
        MvcTestResult general = api.client.get("/api/v1/settings/general", api.owner);

        assertThat(api.client.patch(
                        "/api/v1/settings/general", api.owner, "{\"values\": {\"currency\": \"USD\"}}", etag(general)))
                .hasStatusOk();

        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(currencies(api)).containsEntry("RETAIL", "USD"));
        assertThat(currencies(api)).as("its prices are in LKR").containsEntry("WHOLESALE", "LKR");
        MvcTestResult resolved = api.post(
                "/api/v1/prices:resolve",
                "{\"channel\": \"WHOLESALE\", \"lines\": [{\"variantId\": \""
                        + redL.path("id").asString() + "\", \"packagingUnitId\": \"" + unit(redL, "piece")
                        + "\", \"qty\": \"1\"}]}");
        assertThat(resolved).hasStatusOk();
        assertThat(json(resolved).path("currency").asString()).isEqualTo("USD");
        assertThat(json(resolved).path("lines").get(0).path("status").asString())
                .isEqualTo("PRICE_NOT_FOUND");
    }
}
