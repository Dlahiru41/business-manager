package com.bmp.iam;

import static com.bmp.iam.IamApi.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bmp.iam.domain.port.RoleRepository;
import com.bmp.iam.domain.port.UserRepository;
import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.TenantId;
import com.bmp.test.persistence.RlsCoverage;
import com.bmp.test.persistence.WithPostgres;
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

/** Tenant A's users, roles and grants are invisible to tenant B through JPA and native SQL (06 §10). */
@SpringBootTest(classes = IamTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class IamIsolationIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    UserRepository users;

    @Autowired
    RoleRepository roles;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T in(TenantId tenant, Supplier<T> work) {
        return TenantContext.callWith(
                tenant, () -> new TransactionTemplate(transactionManager).execute(s -> work.get()));
    }

    @Test
    void iamSchemaShouldBeFullyCoveredByRowLevelSecurity() throws Exception {
        RlsCoverage.assertCovered(List.of("iam"), List.of());
    }

    @Test
    void anotherTenantSeesNothingThroughJpaOrNativeSql() {
        IamApi api = new IamApi(mvc, jdbc, transactionManager);
        TenantId a = api.createTenant("Iam isolation A " + UUID.randomUUID());
        TenantId b = api.createTenant("Iam isolation B " + UUID.randomUUID());
        UUID user =
                id(api.createUser(a, "iso." + UUID.randomUUID().toString().substring(0, 8), "[{\"code\": \"OWNER\"}]"));

        assertThat(in(b, () -> users.findById(user))).isEmpty();
        assertThat(in(b, () -> roles.findAllOrdered())).hasSize(6);
        for (String table : List.of("app_user", "role", "role_permission", "user_role")) {
            assertThat(in(
                            b,
                            () -> jdbc.sql("SELECT count(*) FROM iam." + table + " WHERE tenant_id = :a")
                                    .param("a", a.value())
                                    .query(Long.class)
                                    .single()))
                    .as(table)
                    .isZero();
        }
    }

    @Test
    void collectionRowsTakeTheTransactionTenantAndFailWithoutOne() {
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager)
                        .executeWithoutResult(s -> jdbc.sql(
                                        "INSERT INTO iam.role_permission (role_id, permission) VALUES (:id, 'sales:read')")
                                .param("id", UUID.randomUUID())
                                .update()))
                .hasStackTraceContaining("TENANT_CONTEXT_MISSING");
    }
}
