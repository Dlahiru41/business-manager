package com.bmp.pricing;

import static com.bmp.pricing.PricingApi.EGGS;
import static com.bmp.pricing.PricingApi.etag;
import static com.bmp.pricing.PricingApi.row;
import static com.bmp.pricing.PricingApi.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.pricing.api.events.PriceListChanged;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.time.MutableClock;
import com.bmp.test.web.ContractSecurity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Pricing keeps its contracts: every operation protected as x-permission says; PriceListChanged as AsyncAPI says. */
@SpringBootTest(classes = PricingTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class PricingContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    MutableClock clock;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        PricingApi api = new PricingApi(mvc, jdbc, transactions, "Pricing security");
        String nobody =
                JwtTestTokens.builder().tenant(api.tenant).roles("NOBODY").build();

        ContractSecurity.assertEveryOperationIsProtected("pricing", mvc, context, nobody);
    }

    @Test
    void priceListChangedConformsToTheAsyncApiContract() {
        PricingApi api = new PricingApi(mvc, jdbc, transactions, "Pricing events");
        JsonNode redL = variant(api.product(EGGS), "EGG-RED-L");
        String prices = "/api/v1/price-lists/" + api.list("RETAIL") + "/prices";
        api.put(
                prices,
                "{\"prices\": [" + row(redL, "piece", "55.00") + "," + row(redL, "tray", "1600.00") + "]}",
                etag(api.get(prices)));
        clock.advance(Duration.ofMinutes(1));
        api.client.put(prices, api.owner, "{\"prices\": [" + row(redL, "piece", "56.00") + "]}", "W/\"1\"");

        List<PriceListChanged> published = events.stream(PriceListChanged.class)
                .filter(e -> e.tenantId().equals(api.tenant))
                .toList();

        assertThat(published).hasSize(2);
        assertThat(published.get(1).prices()).extracting(p -> p.change()).containsExactly("ENDED", "CREATED");
        published.forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), "PriceListChanged"));
    }
}
