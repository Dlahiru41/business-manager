package com.bmp.tenant;

import static com.bmp.tenant.TenantApi.CONTRACT;
import static com.bmp.tenant.TenantApi.etag;
import static com.bmp.tenant.TenantApi.json;
import static com.bmp.tenant.TenantApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.security.SystemRole;
import com.bmp.tenant.api.TenantQueries;
import com.bmp.tenant.api.dto.ReceiptTemplateInfo;
import com.bmp.test.persistence.WithPostgres;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class ReceiptTemplateApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TenantQueries queries;

    private TenantApi api;
    private TenantId tenant;
    private String owner;

    @BeforeEach
    void setUp() {
        api = new TenantApi(mvc);
        tenant = api.createTenant("Receipts " + UUID.randomUUID());
        owner = token(tenant, SystemRole.OWNER);
    }

    private MvcTestResult create(String name, boolean isDefault) {
        return api.post(
                "/api/v1/receipt-templates",
                owner,
                "{\"name\": \"" + name + "\", \"paperWidthMm\": 80, \"language\": \"en\", \"isDefault\": " + isDefault
                        + ", \"header\": {\"lines\": [\"Perera Stores\"]}, \"fields\": {\"showCashier\": true}}");
    }

    @Test
    void theDefaultTemplateIsReadByOtherModulesWithItsVersionAndJson() {
        assertThat(TenantContext.callWith(tenant, () -> queries.defaultReceiptTemplate()))
                .as("none yet")
                .isEmpty();
        create("Not default", false);
        assertThat(TenantContext.callWith(tenant, () -> queries.defaultReceiptTemplate()))
                .isEmpty();
        String id = json(create("Counter", true)).path("id").asString();

        ReceiptTemplateInfo template = TenantContext.callWith(tenant, () -> queries.defaultReceiptTemplate())
                .orElseThrow();

        assertThat(template.id()).hasToString(id);
        assertThat(template.paperWidthMm()).isEqualTo(80);
        assertThat(template.language()).isEqualTo("en");
        assertThat(template.header()).contains("Perera Stores");
        assertThat(template.fields()).contains("showCashier");
        assertThat(template.footer()).isEqualTo("{}");
        assertThat(template.version()).isNotNegative();
    }

    @Test
    void shouldKeepASingleDefaultTemplate() {
        MvcTestResult first = create("80 mm", true);
        assertThat(first).hasStatus(201);
        CONTRACT.assertConforms(first);
        assertThat(json(first).path("header").path("lines").get(0).asString()).isEqualTo("Perera Stores");
        assertThat(json(first).path("footer").size())
                .as("defaults to an empty object")
                .isZero();

        MvcTestResult second = create("58 mm", true);
        assertThat(json(second).path("isDefault").asBoolean()).isTrue();
        String firstId = json(first).path("id").asString();
        MvcTestResult firstNow = api.get("/api/v1/receipt-templates/" + firstId, owner);
        CONTRACT.assertConforms(firstNow);
        assertThat(json(firstNow).path("isDefault").asBoolean())
                .as("previous default unset")
                .isFalse();

        MvcTestResult makeDefault = api.patch(
                "/api/v1/receipt-templates/" + firstId,
                owner,
                "{\"isDefault\": true, \"paperWidthMm\": 58}",
                etag(firstNow));
        assertThat(makeDefault).hasStatusOk();
        CONTRACT.assertConforms(makeDefault);
        assertThat(json(makeDefault).path("paperWidthMm").asInt()).isEqualTo(58);
        MvcTestResult list = api.get("/api/v1/receipt-templates", owner);
        CONTRACT.assertConforms(list);
        assertThat(json(list).path("items").findValuesAsString("name")).containsExactly("80 mm", "58 mm");
        long defaults = 0;
        for (var item : json(list).path("items")) {
            defaults += item.path("isDefault").asBoolean() ? 1 : 0;
        }
        assertThat(defaults).isEqualTo(1);
        assertThat(api.patch(
                        "/api/v1/receipt-templates/" + firstId, owner, "{\"isDefault\": false}", etag(makeDefault)))
                .hasStatusOk();
    }

    @Test
    void shouldRejectDuplicateNamesUnknownTemplatesAndMissingPermission() {
        create("80 mm", false);

        assertThatProblem(create("80 MM", false)).hasStatus(409).hasCode("RECEIPT_TEMPLATE_NAME_TAKEN");
        assertThatProblem(api.get("/api/v1/receipt-templates/" + UUID.randomUUID(), owner))
                .hasStatus(404)
                .hasCode("RECEIPT_TEMPLATE_NOT_FOUND");
        assertThatProblem(api.get("/api/v1/receipt-templates", token(tenant, SystemRole.CASHIER)))
                .hasStatus(403);
        assertThatProblem(api.post(
                        "/api/v1/receipt-templates",
                        owner,
                        "{\"name\": \"x\", \"paperWidthMm\": 72, \"language\": \"en\"}"))
                .hasStatus(400);
    }
}
