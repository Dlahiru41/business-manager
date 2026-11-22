package com.bmp.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.tenant.domain.port.LocationRepository;
import com.bmp.tenant.domain.port.TenantRepository;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Tenant A's data is invisible to tenant B through the API, JPA and native SQL (06-persistence-and-data.md §10). */
@SpringBootTest(classes = TenantTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class TenantIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TenantRepository tenants;

    @Autowired
    LocationRepository locations;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T in(TenantId tenant, Supplier<T> work) {
        return TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
    }

    @Test
    void tenantSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(List.of("tenant"), List.of("tenant.setting_history"));
    }

    @Test
    void anotherTenantSeesNothingThroughJpaOrNativeSql() {
        TenantApi api = new TenantApi(mvc);
        TenantId a = api.createTenant("Isolation A " + UUID.randomUUID());
        TenantId b = api.createTenant("Isolation B " + UUID.randomUUID());

        assertThat(in(b, () -> tenants.findById(a.value()))).isEmpty();
        assertThat(in(b, () -> locations.findPage(Instant.EPOCH, new UUID(0, 0), 100)))
                .as("only B's own main shop")
                .hasSize(1);
        assertThat(in(
                        b,
                        () -> jdbc.sql("SELECT count(*) FROM tenant.location WHERE tenant_id = :a")
                                .param("a", a.value())
                                .query(Long.class)
                                .single()))
                .isZero();
        assertThat(in(
                        b,
                        () -> jdbc.sql("SELECT count(*) FROM tenant.tenant")
                                .query(Long.class)
                                .single()))
                .isEqualTo(1);
        assertThat(in(
                        b,
                        () -> jdbc.sql("SELECT count(*) FROM tenant.setting WHERE tenant_id = :a")
                                .param("a", a.value())
                                .query(Long.class)
                                .single()))
                .isZero();
    }
}
