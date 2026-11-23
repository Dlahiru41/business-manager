package com.bmp.party;

import static com.bmp.party.PartyApi.checked;
import static com.bmp.party.PartyApi.etag;
import static com.bmp.party.PartyApi.json;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.party.api.PartyQueries;
import com.bmp.party.api.dto.PartyInfo;
import com.bmp.party.api.events.PartyCreated;
import com.bmp.party.api.events.PartyUpdated;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.PartyId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.WithPostgres;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
import tools.jackson.databind.JsonNode;

/** Parties and customer groups through the API (PTY-01..PTY-04). */
@SpringBootTest(classes = PartyTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class PartyApiIT {

    static final String SUPPLIER = """
            {"type": "SUPPLIER", "displayName": "Kegalle Poultry Farm", "phone": "077 123 4567",
             "email": "orders@kegallepoultry.lk", "taxId": "134567890-7000",
             "address": {"line1": "12 Main Street", "city": "Kegalle", "country": "LK"}}
            """;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    PartyQueries queries;

    private PartyApi api;

    @BeforeEach
    void setUp() {
        api = new PartyApi(mvc, "Parties");
    }

    @Test
    void pty01ASupplierIsStoredWithItsPhoneInE164AndPublished() {
        MvcTestResult created = api.post("/api/v1/parties", SUPPLIER);

        assertThat(created).hasStatus(201);
        JsonNode party = json(created);
        assertThat(party.path("phone").asString()).isEqualTo("+94771234567");
        assertThat(party.path("type").asString()).isEqualTo("SUPPLIER");
        assertThat(party.path("address").path("city").asString()).isEqualTo("Kegalle");
        assertThat(party.path("creditPolicy").path("creditAllowed").asBoolean()).isFalse();
        assertThat(etag(created)).isEqualTo("W/\"0\"");
        assertThat(json(api.get("/api/v1/parties/" + party.path("id").asString()))
                        .path("displayName")
                        .asString())
                .isEqualTo("Kegalle Poultry Farm");
        assertThat(events.stream(PartyCreated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.type()).isEqualTo("SUPPLIER");
                    assertThat(e.currency()).isEqualTo("LKR");
                    assertThat(e.toString()).doesNotContain("+9477", "orders@");
                });
        assertThatProblem(api.client.post(
                        "/api/v1/parties",
                        api.owner,
                        "{\"type\": \"CUSTOMER\", \"displayName\": \"X\", \"phone\": \"12-ab\"}"))
                .hasStatus(422)
                .hasCode("VALIDATION_FAILED");
        assertThatProblem(api.post(
                        "/api/v1/parties",
                        "{\"type\": \"CUSTOMER\", \"displayName\": \"X\", \"phone\": \"+0771234567\"}"))
                .hasStatus(422)
                .hasCode("INVALID_PHONE");
        assertThatProblem(api.get("/api/v1/parties/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("PARTY_NOT_FOUND");
    }

    @Test
    void pty01CashiersAddCustomersButNotSuppliers() {
        String cashier = api.token(SystemRole.CASHIER);

        MvcTestResult customer =
                api.post("/api/v1/parties", cashier, "{\"type\": \"CUSTOMER\", \"displayName\": \"Hotel Lake View\"}");

        assertThat(customer).hasStatus(201);
        assertThatProblem(api.post("/api/v1/parties", cashier, SUPPLIER))
                .hasStatus(403)
                .hasCode("PARTY_PERMISSION_REQUIRED");
        assertThatProblem(checked(api.client.get("/api/v1/parties", api.token(SystemRole.STOREKEEPER))))
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
    }

    @Test
    void pty02ADuplicatePhoneNeedsForceAndPartyWrite() {
        api.party(SUPPLIER);
        String sameNumber = "{\"type\": \"CUSTOMER\", \"displayName\": \"Nimal\", \"phone\": \"+94 77 123 4567\"}";

        assertThatProblem(api.post("/api/v1/parties", sameNumber))
                .hasStatus(409)
                .hasCode("DUPLICATE_PARTY_PHONE");
        assertThatProblem(checked(
                        api.client.post("/api/v1/parties?force=true", api.token(SystemRole.CASHIER), sameNumber)))
                .hasStatus(403)
                .hasCode("PARTY_PERMISSION_REQUIRED");
        MvcTestResult forced = api.post("/api/v1/parties?force=true", sameNumber);
        assertThat(forced).hasStatus(201);

        String other = "/api/v1/parties/" + json(forced).path("id").asString();
        assertThatProblem(api.patch(other, "{\"phone\": \"0771234567\"}", etag(api.get(other))))
                .as("its own number again is no duplicate of itself, but still of the supplier")
                .hasStatus(409)
                .hasCode("DUPLICATE_PARTY_PHONE");
        assertThat(api.patch(other + "?force=true", "{\"phone\": \"0771234567\"}", etag(api.get(other))))
                .hasStatusOk();
    }

    @Test
    void pty01PartiesChangeAndListNewestFirstWithFilters() {
        JsonNode group = json(api.post("/api/v1/customer-groups", "{\"code\": \"hotels\", \"name\": \"Hotels\"}"));
        String groupId = group.path("id").asString();
        JsonNode supplier = api.party(SUPPLIER);
        JsonNode hotel =
                api.party("{\"type\": \"CUSTOMER\", \"displayName\": \"Hotel Lake View\", \"customerGroupId\": \""
                        + groupId + "\"}");
        String path = "/api/v1/parties/" + supplier.path("id").asString();

        MvcTestResult changed = api.patch(path, """
                {"type": "BOTH", "displayName": "Kegalle Farm", "email": "sales@kegalle.lk", "taxId": "1",
                 "address": {"city": "Colombo"}, "customerGroupId": "%s", "status": "INACTIVE"}
                """.formatted(groupId), etag(api.get(path)));

        assertThat(changed).hasStatusOk();
        assertThat(json(changed).path("type").asString()).isEqualTo("BOTH");
        assertThat(json(changed).path("status").asString()).isEqualTo("INACTIVE");
        assertThat(json(changed).path("address").path("city").asString()).isEqualTo("Colombo");
        assertThat(api.patch(path, "{\"status\": \"ACTIVE\"}", etag(api.get(path))))
                .hasStatusOk();
        assertThat(events.stream(PartyUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(2);
        assertThatProblem(api.patch(path, "{\"displayName\": \"Stale\"}", "W/\"0\""))
                .hasStatus(412)
                .hasCode("VERSION_CONFLICT");
        assertThatProblem(api.patch(path, "{\"displayName\": \"Missing\"}", null))
                .hasStatus(428)
                .hasCode("PRECONDITION_REQUIRED");
        assertThatProblem(api.patch(path, "{\"customerGroupId\": \"" + UUID.randomUUID() + "\"}", etag(api.get(path))))
                .hasStatus(422)
                .hasCode("UNKNOWN_CUSTOMER_GROUP");
        assertThatProblem(api.post(
                        "/api/v1/parties",
                        "{\"type\": \"CUSTOMER\", \"displayName\": \"Y\", \"customerGroupId\": \"" + UUID.randomUUID()
                                + "\"}"))
                .hasStatus(422)
                .hasCode("UNKNOWN_CUSTOMER_GROUP");

        List<String> names = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(api.get("/api/v1/parties?limit=1" + (cursor != null ? "&cursor=" + cursor : "")));
            page.path("items").forEach(p -> names.add(p.path("displayName").asString()));
            cursor = page.path("page").path("hasMore").asBoolean()
                    ? page.path("page").path("nextCursor").asString()
                    : null;
        } while (cursor != null);
        assertThat(names).containsExactly("Hotel Lake View", "Kegalle Farm");
        assertThat(json(api.get("/api/v1/parties?type=CUSTOMER&status=ACTIVE&customerGroupId=" + groupId))
                        .path("items"))
                .singleElement()
                .satisfies(p -> assertThat(p.path("id").asString())
                        .isEqualTo(hotel.path("id").asString()));
        assertThatProblem(api.get("/api/v1/parties?cursor=bm90LWEtdXVpZA"))
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
    }

    @Test
    void pty03OnlyCreditPermissionChangesCreditTerms() {
        JsonNode hotel = api.party("{\"type\": \"CUSTOMER\", \"displayName\": \"Hotel Lake View\"}");
        String path = "/api/v1/parties/" + hotel.path("id").asString();
        String terms = "{\"creditAllowed\": true, \"creditLimit\": \"50000.00\", \"paymentTermsDays\": 14}";

        MvcTestResult set = api.put(path + "/credit-policy", terms, etag(api.get(path)));

        assertThat(set).hasStatusOk();
        assertThat(json(set).path("creditPolicy").path("creditLimit").asString())
                .isEqualTo("50000");
        assertThat(json(set).path("creditPolicy").path("paymentTermsDays").asInt())
                .isEqualTo(14);
        assertThatProblem(checked(api.client.put(
                        path + "/credit-policy", api.token(SystemRole.MANAGER), terms, etag(api.get(path)))))
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
        assertThat(events.stream(PartyUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .singleElement()
                .satisfies(e -> assertThat(e.creditPolicy().creditAllowed()).isTrue());
        PartyInfo info = TenantContext.callWith(
                        api.tenant,
                        () -> queries.findParty(
                                PartyId.of(UUID.fromString(hotel.path("id").asString()))))
                .orElseThrow();
        assertThat(info.isCustomer()).isTrue();
        assertThat(info.isSupplier()).isFalse();
        assertThat(info.creditLimit()).isEqualByComparingTo("50000");
        assertThat(info.paymentTermsDays()).isEqualTo(14);
        assertThat(info.active()).isTrue();
        assertThat(TenantContext.callWith(api.tenant, () -> queries.findParty(PartyId.of(UUID.randomUUID()))))
                .isEqualTo(Optional.empty());
    }

    @Test
    void pty04AnonymisingReplacesPersonalDataOnceAndFreezesTheParty() {
        String path = "/api/v1/parties/" + api.party(SUPPLIER).path("id").asString();
        String key = UUID.randomUUID().toString();

        MvcTestResult anonymised = checked(api.client.post(path + "/anonymise", api.owner, "", key));
        MvcTestResult replay = checked(api.client.post(path + "/anonymise", api.owner, "", key));
        MvcTestResult again = checked(api.client.post(path + "/anonymise", api.owner, ""));

        assertThat(anonymised).hasStatusOk();
        JsonNode party = json(anonymised);
        assertThat(party.path("displayName").asString()).isEqualTo("Anonymised party");
        assertThat(party.has("phone")).isFalse();
        assertThat(party.has("email")).isFalse();
        assertThat(party.has("address")).isFalse();
        assertThat(party.path("anonymised").asBoolean()).isTrue();
        assertThat(party.path("status").asString()).isEqualTo("INACTIVE");
        assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        assertThat(json(again).path("version").asLong())
                .as("nothing changed")
                .isEqualTo(party.path("version").asLong());
        assertThat(events.stream(PartyUpdated.class).filter(e -> e.tenantId().equals(api.tenant)))
                .hasSize(1);
        assertThatProblem(api.patch(path, "{\"displayName\": \"Back\"}", etag(api.get(path))))
                .hasStatus(409)
                .hasCode("PARTY_ANONYMISED");
        assertThatProblem(checked(api.client.post(path + "/anonymise", api.token(SystemRole.MANAGER), "")))
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
    }

    @Test
    void searchFindsActivePartiesByWordPrefixOrPhone() {
        api.party(SUPPLIER);
        api.party("{\"type\": \"CUSTOMER\", \"displayName\": \"Poultry Corner Shop\", \"phone\": \"0812345678\"}");
        api.party("{\"type\": \"BOTH\", \"displayName\": \"Lake View 50_50\", \"phone\": \"0779999999\"}");
        api.party("{\"type\": \"CUSTOMER\", \"displayName\": \"Store 500\"}");

        assertThat(names("/api/v1/parties:search?q=poul"))
                .as("a name starting with the search comes first")
                .containsExactly("Poultry Corner Shop", "Kegalle Poultry Farm");
        assertThat(names("/api/v1/parties:search?q=poul&type=SUPPLIER")).containsExactly("Kegalle Poultry Farm");
        assertThat(names("/api/v1/parties:search?q=0771")).containsExactly("Kegalle Poultry Farm");
        assertThat(names("/api/v1/parties:search?q=081")).containsExactly("Poultry Corner Shop");
        assertThat(names("/api/v1/parties:search?q=50_"))
                .as("LIKE wildcards in the search are literal")
                .containsExactly("Lake View 50_50");
        assertThat(names("/api/v1/parties:search?q=lake&type=BOTH")).containsExactly("Lake View 50_50");
        assertThat(names("/api/v1/parties:search?q=lake&type=CUSTOMER&limit=1")).hasSize(1);
        assertThat(names("/api/v1/parties:search?q=nobody")).isEmpty();
    }

    @Test
    void customerGroupsAreCreatedChangedAndPagedByCode() {
        MvcTestResult created =
                api.post("/api/v1/customer-groups", "{\"code\": \"retailers\", \"name\": \"Retailers\"}");
        api.post("/api/v1/customer-groups", "{\"code\": \"hotels\", \"name\": \"Hotels\"}");
        String path = "/api/v1/customer-groups/" + json(created).path("id").asString();

        assertThat(json(created).path("code").asString()).isEqualTo("RETAILERS");
        assertThatProblem(api.post("/api/v1/customer-groups", "{\"code\": \"HOTELS\", \"name\": \"Again\"}"))
                .hasStatus(409)
                .hasCode("CUSTOMER_GROUP_CODE_TAKEN");
        assertThat(json(api.patch(path, "{\"name\": \"Shops\"}", etag(api.get(path))))
                        .path("name")
                        .asString())
                .isEqualTo("Shops");
        assertThat(json(api.patch(path, "{\"status\": \"INACTIVE\"}", etag(api.get(path))))
                        .path("status")
                        .asString())
                .isEqualTo("INACTIVE");
        assertThat(json(api.patch(path, "{\"status\": \"ACTIVE\"}", etag(api.get(path))))
                        .path("status")
                        .asString())
                .isEqualTo("ACTIVE");
        JsonNode first = json(api.get("/api/v1/customer-groups?limit=1"));
        assertThat(first.path("items").get(0).path("code").asString()).isEqualTo("HOTELS");
        JsonNode second = json(api.get("/api/v1/customer-groups?limit=1&cursor="
                + first.path("page").path("nextCursor").asString()));
        assertThat(second.path("items").get(0).path("code").asString()).isEqualTo("RETAILERS");
        assertThat(second.path("page").path("hasMore").asBoolean()).isFalse();
        assertThatProblem(api.get("/api/v1/customer-groups/" + UUID.randomUUID()))
                .hasStatus(404)
                .hasCode("CUSTOMER_GROUP_NOT_FOUND");
        String inactive = json(api.post("/api/v1/customer-groups", "{\"code\": \"old\", \"name\": \"Old\"}"))
                .path("id")
                .asString();
        String oldPath = "/api/v1/customer-groups/" + inactive;
        api.patch(oldPath, "{\"status\": \"INACTIVE\"}", etag(api.get(oldPath)));
        assertThatProblem(api.post(
                        "/api/v1/parties",
                        "{\"type\": \"CUSTOMER\", \"displayName\": \"Z\", \"customerGroupId\": \"" + inactive + "\"}"))
                .as("inactive groups take no new members")
                .hasStatus(422)
                .hasCode("UNKNOWN_CUSTOMER_GROUP");
    }

    private List<String> names(String path) {
        List<String> names = new ArrayList<>();
        json(api.get(path))
                .path("items")
                .forEach(p -> names.add(p.path("displayName").asString()));
        return names;
    }
}
