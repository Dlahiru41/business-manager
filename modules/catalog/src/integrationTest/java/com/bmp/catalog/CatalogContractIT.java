package com.bmp.catalog;

import static com.bmp.catalog.CatalogApi.EGGS;
import static com.bmp.catalog.CatalogApi.OIL;
import static com.bmp.catalog.CatalogApi.etag;
import static com.bmp.catalog.CatalogApi.variant;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.catalog.api.events.ProductCreated;
import com.bmp.catalog.api.events.ProductUpdated;
import com.bmp.catalog.api.events.TransformationDefinitionPublished;
import com.bmp.catalog.api.events.TransformationDefinitionRetired;
import com.bmp.catalog.api.events.VariantCreated;
import com.bmp.catalog.api.events.VariantDeactivated;
import com.bmp.catalog.api.events.VariantUpdated;
import com.bmp.shared.event.DomainEvent;
import com.bmp.shared.id.TenantId;
import com.bmp.test.events.EventContract;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The catalog keeps its contracts: every operation protected as x-permission says, every event as AsyncAPI says. */
@SpringBootTest(classes = CatalogTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class CatalogContractIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationContext context;

    @Autowired
    ApplicationEvents events;

    @Autowired
    JsonMapper json;

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        CatalogApi api = new CatalogApi(mvc, "Catalog security");
        String nobody =
                JwtTestTokens.builder().tenant(api.tenant).roles("NOBODY").build();

        ContractSecurity.assertEveryOperationIsProtected("catalog", mvc, context, nobody);
    }

    @Test
    void publishedEventsConformToTheAsyncApiContract() {
        CatalogApi api = new CatalogApi(mvc, "Catalog events");
        JsonNode oil = api.create(OIL);
        JsonNode eggs = api.create(EGGS);
        String product = "/api/v1/products/" + eggs.path("id").asString();
        api.patch(product, "{\"name\": \"Farm eggs\"}", etag(api.get(product)));
        String redL =
                "/api/v1/variants/" + variant(eggs, "EGG-RED-L").path("id").asString();
        api.action(redL + "/deactivate");
        api.action(redL + "/reactivate");
        String definition = "/api/v1/transformation-definitions/"
                + CatalogApi.json(api.get("/api/v1/transformation-definitions?variantId="
                                + variant(oil, "OIL").path("id").asString()))
                        .path("items")
                        .get(0)
                        .path("id")
                        .asString();
        api.action(definition + "/retire");

        Map<Class<? extends DomainEvent>, String> schemas = Map.of(
                ProductCreated.class, "ProductCreated",
                ProductUpdated.class, "ProductUpdated",
                VariantCreated.class, "VariantCreated",
                VariantUpdated.class, "VariantUpdated",
                VariantDeactivated.class, "VariantDeactivated",
                TransformationDefinitionPublished.class, "TransformationDefinitionPublished",
                TransformationDefinitionRetired.class, "TransformationDefinitionRetired");
        schemas.forEach((type, schema) -> {
            List<? extends DomainEvent> published = of(type, api.tenant);
            assertThat(published).as(schema).isNotEmpty();
            published.forEach(e -> EventContract.assertConforms(json.writeValueAsString(e), schema));
        });
    }

    private <T extends DomainEvent> List<T> of(Class<T> type, TenantId tenant) {
        return events.stream(type).filter(e -> e.tenantId().equals(tenant)).toList();
    }
}
