package com.bmp.audit;

import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.bmp.audit.api.AuditEntry;
import com.bmp.audit.api.AuditEntry.AuditSource;
import com.bmp.audit.api.AuditWriter;
import com.bmp.audit.application.AuditService;
import com.bmp.audit.domain.model.ChainVerification;
import com.bmp.audit.testapp.AuditTestApplication;
import com.bmp.sample.api.events.SampleRecorded;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.event.EventMetadata;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.BmpPostgres;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.test.web.ContractSecurity;
import com.bmp.test.web.OpenApiValidation;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The audit trail: API writes, domain events, the hash chain and tenant isolation. */
@SpringBootTest(classes = AuditTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class AuditIT {

    private static final OpenApiValidation CONTRACT = OpenApiValidation.forModule("audit");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    MockMvcTester mvc;

    @Autowired
    AuditWriter writer;

    @Autowired
    AuditService audit;

    @Autowired
    ApplicationEventPublisher events;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    ApplicationContext context;

    private TenantId tenant;
    private UserId owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        tenant = TenantId.of(UUID.randomUUID());
        owner = UserId.of(UUID.randomUUID());
        ownerToken = JwtTestTokens.builder()
                .tenant(tenant)
                .user(owner)
                .roles(SystemRole.OWNER.code())
                .build();
    }

    private MvcTestResult post(String path, String token, @Nullable String actingFor) {
        var request = mvc.post().uri(path).header("Authorization", "Bearer " + token);
        return (actingFor == null ? request : request.header("X-Tenant-Id", actingFor)).exchange();
    }

    private JsonNode records(String query, String token) {
        MvcTestResult result = mvc.get()
                .uri("/api/v1/audit-records" + query)
                .header("Authorization", "Bearer " + token)
                .exchange();
        assertThat(result).hasStatus(200);
        CONTRACT.assertConforms(result);
        return JSON.readTree(result.getResponse().getContentAsByteArray()).path("items");
    }

    private void inTenant(Runnable work) {
        TenantContext.runWith(
                tenant, () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> work.run()));
    }

    private static AuditEntry entry(String action) {
        return new AuditEntry(
                Instant.now(),
                AuditSource.SYSTEM,
                action,
                "jobs",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void recordsApiWritesWithResourceOutcomeAndActorButNotReads() {
        UUID thing = UUID.randomUUID();
        assertThat(post("/api/v1/things", ownerToken, null)).hasStatus(201);
        assertThat(post("/api/v1/things/" + thing + "/archive", ownerToken, null))
                .hasStatus(204);
        assertThatProblem(post("/api/v1/things/" + new UUID(1, 0) + "/archive", ownerToken, null))
                .hasStatus(422);
        assertThat(mvc.get().uri("/api/v1/things/" + thing).header("Authorization", "Bearer " + ownerToken))
                .hasStatus(200);

        JsonNode items = records("?resourceType=things", ownerToken);

        assertThat(items).hasSize(3);
        JsonNode refused = items.get(0);
        assertThat(refused.path("outcome").asInt()).isEqualTo(422);
        JsonNode archived = items.get(1);
        assertThat(archived.path("action").asString()).isEqualTo("POST /api/v1/things/{thingId}/archive");
        assertThat(archived.path("resourceId").asString()).isEqualTo(thing.toString());
        assertThat(archived.path("outcome").asInt()).isEqualTo(204);
        assertThat(archived.path("actorId").asString()).isEqualTo(owner.toString());
        assertThat(archived.path("source").asString()).isEqualTo("API");
        assertThat(archived.path("platformAdmin").asBoolean()).isFalse();
        assertThat(archived.path("requestId").asString()).isNotBlank();
        JsonNode created = items.get(2);
        assertThat(created.path("resourceId").asString()).isEqualTo("0193a6f1-0000-7000-8000-00000000c0de");
        assertThat(created.path("sequence").asLong()).isEqualTo(1);
        assertThat(records("?resourceType=things&resourceId=" + thing, ownerToken))
                .hasSize(1);
    }

    @Test
    void flagsAPlatformAdministratorActingForTheTenant() {
        String admin = JwtTestTokens.platformAdmin();

        assertThat(post("/api/v1/things", admin, tenant.toString())).hasStatus(201);

        JsonNode item = records("", ownerToken).get(0);
        assertThat(item.path("platformAdmin").asBoolean()).isTrue();

        UUID thing = UUID.randomUUID();
        assertThat(mvc.get()
                        .uri("/api/v1/things/" + thing)
                        .header("Authorization", "Bearer " + admin)
                        .header("X-Tenant-Id", tenant.toString()))
                .hasStatus(200);
        JsonNode read = records("?resourceId=" + thing, ownerToken).get(0);
        assertThat(read.path("action").asString()).isEqualTo("GET /api/v1/things/{thingId}");
        assertThat(read.path("platformAdmin").asBoolean())
                .as("reads for a tenant are audited (08 §2)")
                .isTrue();
    }

    @Test
    void recordsEveryDomainEventOnce() {
        SampleRecorded event = new SampleRecorded(
                UUID.randomUUID(),
                tenant,
                Instant.parse("2026-11-22T09:00:00Z"),
                UUID.randomUUID(),
                3,
                new EventMetadata(owner, null, "req-7", null),
                "hello");

        inTenant(() -> events.publishEvent(event));
        inTenant(() -> events.publishEvent(event));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() ->
                        assertThat(records("?resourceType=sample", ownerToken)).hasSize(1));
        JsonNode item =
                records("?resourceId=" + event.aggregateId(), ownerToken).get(0);
        assertThat(item.path("source").asString()).isEqualTo("EVENT");
        assertThat(item.path("action").asString()).isEqualTo("bmp.sample.sample-recorded.v1");
        assertThat(item.path("eventId").asString()).isEqualTo(event.eventId().toString());
        assertThat(item.path("actorId").asString()).isEqualTo(owner.toString());
        assertThat(item.path("requestId").asString()).isEqualTo("req-7");
        assertThat(item.path("afterHash").asString()).hasSize(64);
    }

    @Test
    void detectsATamperedRecord() throws Exception {
        inTenant(() -> List.of("first", "second", "third").forEach(action -> writer.record(entry(action))));
        assertThat(TenantContext.callWith(tenant, audit::verify))
                .isEqualTo(new ChainVerification.Result(true, 3, null));

        try (Connection superuser = BmpPostgres.superuserConnection();
                PreparedStatement tamper = superuser.prepareStatement(
                        "UPDATE audit.audit_record SET action = 'nothing to see' WHERE tenant_id = ? AND sequence = 2")) {
            tamper.setObject(1, tenant.value());
            assertThat(tamper.executeUpdate()).isEqualTo(1);
        }

        ChainVerification.Result result = TenantContext.callWith(tenant, audit::verify);
        assertThat(result.intact()).isFalse();
        assertThat(result.firstBrokenSequence()).isEqualTo(2L);
    }

    @Test
    void concurrentWritersKeepAGaplessChain() throws Exception {
        int writers = 20;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> done = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                String action = "job-" + i;
                done.add(executor.submit(() -> TenantContext.runWith(tenant, () -> writer.record(entry(action)))));
            }
            for (Future<?> future : done) {
                future.get();
            }
        }

        assertThat(TenantContext.callWith(tenant, audit::verify))
                .isEqualTo(new ChainVerification.Result(true, writers, null));
    }

    @Test
    void pagesFiltersAndIsolatesTenants() {
        inTenant(() -> List.of("a", "b", "c").forEach(action -> writer.record(entry(action))));
        TenantId other = TenantId.of(UUID.randomUUID());
        String otherOwner = JwtTestTokens.forRole(other, SystemRole.OWNER);

        MvcTestResult first = mvc.get()
                .uri("/api/v1/audit-records?limit=2")
                .header("Authorization", "Bearer " + ownerToken)
                .exchange();
        JsonNode page = JSON.readTree(first.getResponse().getContentAsByteArray());
        String cursor = page.path("page").path("nextCursor").asString();

        assertThat(page.path("items")).hasSize(2);
        assertThat(records("?limit=2&cursor=" + cursor, ownerToken)).hasSize(1);
        assertThat(records("?to=2000-01-01T00:00:00Z", ownerToken)).isEmpty();
        assertThat(records("?from=2000-01-01T00:00:00Z", ownerToken)).hasSize(3);
        assertThat(records("", otherOwner)).isEmpty();
        assertThatProblem(mvc.get()
                        .uri("/api/v1/audit-records?cursor=bm9wZQ")
                        .header("Authorization", "Bearer " + ownerToken)
                        .exchange())
                .hasStatus(400)
                .hasCode("INVALID_PARAMETER");
        MvcTestResult cashier = mvc.get()
                .uri("/api/v1/audit-records")
                .header("Authorization", "Bearer " + JwtTestTokens.forRole(tenant, SystemRole.CASHIER))
                .exchange();
        assertThatProblem(cashier).hasStatus(403).hasCode("ACCESS_DENIED");
        CONTRACT.assertConforms(cashier);
    }

    @Test
    void theLedgerIsAppendOnlyAndTenantScoped() throws Exception {
        RlsCoverage.assertCovered(List.of("audit"), List.of("audit.audit_record"));
        assertThat(Objects.requireNonNull(TenantContext.callWith(tenant, audit::verify))
                        .checked())
                .isZero();
    }

    @Test
    void everyOperationIsProtectedAsTheContractSays() {
        String nobody = JwtTestTokens.builder().tenant(tenant).roles("NOBODY").build();

        ContractSecurity.assertEveryOperationIsProtected("audit", mvc, context, nobody);
    }
}
