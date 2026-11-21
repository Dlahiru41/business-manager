package com.bmp.tenant;

import static com.bmp.tenant.TenantApi.CONTRACT;
import static com.bmp.tenant.TenantApi.etag;
import static com.bmp.tenant.TenantApi.json;
import static com.bmp.tenant.TenantApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.tenant.api.events.LocationCreated;
import com.bmp.test.persistence.WithPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class LocationApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    private TenantApi api;
    private TenantId tenant;
    private String owner;

    @BeforeEach
    void setUp() {
        api = new TenantApi(mvc);
        tenant = api.createTenant("Locations " + UUID.randomUUID());
        owner = token(tenant, SystemRole.OWNER);
    }

    private MvcTestResult create(String name) {
        return api.post(
                "/api/v1/locations",
                owner,
                "{\"name\": \"" + name
                        + "\", \"type\": \"STORE\", \"address\": {\"line1\": \"12 Main St\", \"city\": \"Kandy\", \"country\": \"LK\"}}");
    }

    @Test
    void shouldCreateReadAndRenameALocation() {
        MvcTestResult created = create("Store room");
        assertThat(created).hasStatus(201).hasHeader("ETag", "W/\"0\"");
        CONTRACT.assertConforms(created);
        String id = json(created).path("id").asString();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/locations/" + id);
        assertThat(json(created).path("address").path("city").asString()).isEqualTo("Kandy");
        assertThat(events.stream(LocationCreated.class)
                        .filter(e -> e.locationId().toString().equals(id)))
                .hasSize(1);

        MvcTestResult read = api.get("/api/v1/locations/" + id, owner);
        CONTRACT.assertConforms(read);
        MvcTestResult renamed = api.patch(
                "/api/v1/locations/" + id, owner, "{\"name\": \"Back store\", \"type\": \"WAREHOUSE\"}", etag(read));

        assertThat(renamed).hasStatusOk().hasHeader("ETag", "W/\"1\"");
        CONTRACT.assertConforms(renamed);
        assertThat(json(renamed).path("name").asString()).isEqualTo("Back store");
        assertThat(json(renamed).path("type").asString()).isEqualTo("WAREHOUSE");
        assertThat(json(renamed).path("address").path("city").asString())
                .as("address kept")
                .isEqualTo("Kandy");
    }

    @Test
    void shouldPageThroughLocationsWithCursors() {
        create("Store A");
        create("Store B");
        create("Store C");

        List<String> names = new ArrayList<>();
        String cursor = null;
        int pages = 0;
        do {
            MvcTestResult page =
                    api.get("/api/v1/locations?limit=2" + (cursor != null ? "&cursor=" + cursor : ""), owner);
            CONTRACT.assertConforms(page);
            json(page).path("items").forEach(item -> names.add(item.path("name").asString()));
            cursor = json(page).path("page").path("hasMore").asBoolean()
                    ? json(page).path("page").path("nextCursor").asString()
                    : null;
            pages++;
        } while (cursor != null);

        assertThat(names).containsExactly("Main shop", "Store A", "Store B", "Store C");
        assertThat(pages).isEqualTo(2);
        assertThatProblem(api.get("/api/v1/locations?cursor=bm90LWEtY3Vyc29y", owner))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void shouldRejectDuplicateNamesCaseInsensitively() {
        assertThat(create("Store room")).hasStatus(201);

        assertThatProblem(create("STORE ROOM"))
                .hasStatus(409)
                .hasCode("LOCATION_NAME_TAKEN")
                .hasNoInternals();
    }

    @Test
    void ten01TheLastActiveLocationCannotBeDeactivated() {
        MvcTestResult list = api.get("/api/v1/locations", owner);
        String mainShop = json(list).path("items").get(0).path("id").asString();
        MvcTestResult main = api.get("/api/v1/locations/" + mainShop, owner);

        assertThatProblem(api.patch("/api/v1/locations/" + mainShop, owner, "{\"status\": \"INACTIVE\"}", etag(main)))
                .hasStatus(409)
                .hasCode("LAST_ACTIVE_LOCATION");

        MvcTestResult store = create("Store room");
        MvcTestResult deactivated =
                api.patch("/api/v1/locations/" + mainShop, owner, "{\"status\": \"INACTIVE\"}", etag(main));
        assertThat(deactivated).hasStatusOk();
        assertThat(json(deactivated).path("status").asString()).isEqualTo("INACTIVE");
        String storeId = json(store).path("id").asString();
        assertThatProblem(api.patch("/api/v1/locations/" + storeId, owner, "{\"status\": \"INACTIVE\"}", etag(store)))
                .as("now the store room is the last active one")
                .hasStatus(409)
                .hasCode("LAST_ACTIVE_LOCATION");
        assertThat(api.patch("/api/v1/locations/" + mainShop, owner, "{\"status\": \"ACTIVE\"}", etag(deactivated)))
                .hasStatusOk();
    }

    @Test
    void shouldEnforcePreconditionsPermissionsAndTenantIsolation() {
        String id = json(create("Store room")).path("id").asString();
        TenantId other = api.createTenant("Other " + UUID.randomUUID());

        assertThatProblem(api.patch("/api/v1/locations/" + id, owner, "{\"name\": \"x\"}", null))
                .hasStatus(428);
        assertThatProblem(api.patch("/api/v1/locations/" + id, owner, "{\"name\": \"x\"}", "W/\"9\""))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
        assertThatProblem(api.get("/api/v1/locations/" + id, token(other, SystemRole.OWNER)))
                .as("another tenant never sees it (IDOR)")
                .hasStatus(404)
                .hasCode("LOCATION_NOT_FOUND");
        assertThatProblem(api.get("/api/v1/locations/" + UUID.randomUUID(), owner))
                .hasStatus(404);
        assertThatProblem(api.post(
                        "/api/v1/locations",
                        token(tenant, SystemRole.CASHIER),
                        "{\"name\": \"x\", \"type\": \"SHOP\"}"))
                .hasStatus(403);
        assertThatProblem(api.get("/api/v1/locations", token(tenant, SystemRole.CASHIER)))
                .hasStatus(403);
        assertThatProblem(api.post("/api/v1/locations", owner, "{\"name\": \"\", \"type\": \"SHOP\"}"))
                .hasStatus(422)
                .hasFieldError("name", "SIZE");
        assertThatProblem(api.post("/api/v1/locations", owner, "{\"name\": \"x\", \"type\": \"MOON\"}"))
                .hasStatus(400)
                .hasCode("MALFORMED_REQUEST");
    }
}
