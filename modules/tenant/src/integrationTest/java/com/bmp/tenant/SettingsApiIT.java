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
import com.bmp.tenant.api.events.SettingsChanged;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
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
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
@RecordApplicationEvents
class SettingsApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    ApplicationEvents events;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private TenantApi api;
    private TenantId tenant;
    private String owner;

    @BeforeEach
    void setUp() {
        api = new TenantApi(mvc);
        tenant = api.createTenant("Settings " + UUID.randomUUID());
        owner = token(tenant, SystemRole.OWNER);
    }

    @Test
    void shouldServeEveryGroupWithDefaults() {
        MvcTestResult all = api.get("/api/v1/settings", owner);
        CONTRACT.assertConforms(all);
        assertThat(json(all).path("groups").size()).isEqualTo(8);

        MvcTestResult sales = api.get("/api/v1/settings/sales", owner);
        CONTRACT.assertConforms(sales);
        assertThat(sales).hasHeader("ETag", "W/\"0\"");
        assertThat(json(sales).path("values").path("maxCashierDiscountPct").asString())
                .isEqualTo("5.00");
        assertThat(json(sales).path("values").path("returnWindowDays").asInt()).isEqualTo(7);
        assertThat(json(sales).path("values").path("voidRequiresManager").asBoolean())
                .isTrue();
        MvcTestResult general = api.get("/api/v1/settings/general", owner);
        assertThat(general).hasHeader("ETag", "W/\"1\"");
        assertThat(json(general).path("values").path("currency").asString()).isEqualTo("LKR");
        assertThatProblem(api.get("/api/v1/settings/unknown", owner))
                .hasStatus(404)
                .hasCode("SETTING_GROUP_NOT_FOUND");
    }

    @Test
    void ten03ChangesAreVersionedRecordedAndPublished() {
        MvcTestResult sales = api.get("/api/v1/settings/sales", owner);

        MvcTestResult updated = api.patch(
                "/api/v1/settings/sales",
                owner,
                "{\"values\": {\"maxCashierDiscountPct\": \"7.50\", \"returnWindowDays\": 14}}",
                etag(sales));

        assertThat(updated).hasStatusOk().hasHeader("ETag", "W/\"1\"");
        CONTRACT.assertConforms(updated);
        assertThat(json(updated).path("values").path("returnWindowDays").asInt())
                .isEqualTo(14);
        assertThat(json(updated).path("values").path("voidRequiresManager").asBoolean())
                .as("untouched")
                .isTrue();
        assertThat(events.stream(SettingsChanged.class).filter(e -> e.tenantId().equals(tenant)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.group()).isEqualTo("sales");
                    assertThat(e.aggregateVersion()).isEqualTo(1);
                    assertThat(e.changes())
                            .extracting(SettingsChanged.Change::key)
                            .containsExactly("maxCashierDiscountPct", "returnWindowDays");
                });
        assertThat(historyRows("sales")).isEqualTo(2);

        MvcTestResult unchanged =
                api.patch("/api/v1/settings/sales", owner, "{\"values\": {\"returnWindowDays\": 14}}", etag(updated));
        assertThat(unchanged).as("no change, no new version").hasHeader("ETag", "W/\"1\"");
        assertThatProblem(api.patch(
                        "/api/v1/settings/sales", owner, "{\"values\": {\"returnWindowDays\": 1}}", "W/\"0\""))
                .hasStatus(412)
                .hasDetail("currentVersion", "1");
        assertThatProblem(api.patch("/api/v1/settings/sales", owner, "{\"values\": {\"returnWindowDays\": 1}}", null))
                .hasStatus(428);
    }

    @Test
    void ten03UnknownKeysAndInvalidValuesAreRejected() {
        String version = etag(api.get("/api/v1/settings/sales", owner));

        assertThatProblem(api.patch("/api/v1/settings/sales", owner, "{\"values\": {\"maxDiscount\": \"1\"}}", version))
                .hasStatus(422)
                .hasCode("UNKNOWN_SETTING")
                .hasFieldError("maxDiscount", "UNKNOWN_SETTING");
        assertThatProblem(api.patch(
                        "/api/v1/settings/sales",
                        owner,
                        "{\"values\": {\"returnWindowDays\": \"seven\", \"maxCashierDiscountPct\": \"150\"}}",
                        version))
                .hasStatus(422)
                .hasCode("INVALID_SETTING_VALUE")
                .hasFieldError("returnWindowDays", "TYPE")
                .hasFieldError("maxCashierDiscountPct", "PATTERN");
        assertThatProblem(api.patch(
                        "/api/v1/settings/inventory",
                        owner,
                        "{\"values\": {\"defaultLocationId\": \"" + UUID.randomUUID() + "\"}}",
                        etag(api.get("/api/v1/settings/inventory", owner))))
                .hasStatus(422)
                .hasFieldError("defaultLocationId", "UNKNOWN_LOCATION");
        assertThatProblem(api.patch(
                        "/api/v1/settings/general",
                        owner,
                        "{\"values\": {\"currency\": \"ABC\", \"timeZone\": \"Mars/Base\"}}",
                        etag(api.get("/api/v1/settings/general", owner))))
                .hasStatus(422)
                .hasFieldError("currency", "UNKNOWN_CURRENCY")
                .hasFieldError("timeZone", "UNKNOWN_TIME_ZONE");
        assertThatProblem(api.patch(
                        "/api/v1/settings/sales",
                        token(tenant, SystemRole.MANAGER),
                        "{\"values\": {\"returnWindowDays\": 3}}",
                        version))
                .hasStatus(403);
    }

    @Test
    void ten02CurrencyAndTimeZoneLockOnceBusinessDocumentsMayExist() {
        MvcTestResult general = api.get("/api/v1/settings/general", owner);
        MvcTestResult early = api.patch(
                "/api/v1/settings/general",
                owner,
                "{\"values\": {\"currency\": \"USD\", \"timeZone\": \"Asia/Kolkata\"}}",
                etag(general));
        assertThat(early).as("a new tenant may still fix its currency").hasStatusOk();
        assertThat(json(api.get("/api/v1/tenant", owner)).path("currency").asString())
                .as("mirrored")
                .isEqualTo("USD");

        clock.advance(Duration.ofDays(2));
        assertThatProblem(api.patch(
                        "/api/v1/settings/general", owner, "{\"values\": {\"currency\": \"LKR\"}}", etag(early)))
                .hasStatus(409)
                .hasCode("SETTING_LOCKED")
                .hasDetail("key", "currency");
        assertThat(api.patch(
                        "/api/v1/settings/general",
                        owner,
                        "{\"values\": {\"languages\": [\"en\", \"si\"]}}",
                        etag(early)))
                .as("other general settings stay editable")
                .hasStatusOk();

        MvcTestResult byPlatform = mvc.patch()
                .uri("/api/v1/settings/general")
                .header("Authorization", "Bearer " + JwtTestTokens.platformAdmin())
                .header("X-Tenant-Id", tenant.toString())
                .header("If-Match", "W/\"3\"")
                .contentType("application/json")
                .content("{\"values\": {\"currency\": \"LKR\"}}")
                .exchange();
        assertThat(byPlatform).as("platform:tenants may override (TEN-02)").hasStatusOk();
        assertThat(json(api.get("/api/v1/tenant", owner)).path("currency").asString())
                .isEqualTo("LKR");
    }

    private long historyRows(String group) {
        Long rows = TenantContext.callWith(
                tenant,
                () -> new TransactionTemplate(transactionManager)
                        .execute(status -> jdbc.sql(
                                        "SELECT count(*) FROM tenant.setting_history WHERE group_name = :group")
                                .param("group", group)
                                .query(Long.class)
                                .single()));
        return rows == null ? 0 : rows;
    }
}
