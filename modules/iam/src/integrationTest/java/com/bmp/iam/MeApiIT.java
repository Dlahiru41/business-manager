package com.bmp.iam;

import static com.bmp.iam.IamApi.CONTRACT;
import static com.bmp.iam.IamApi.id;
import static com.bmp.iam.IamApi.json;
import static com.bmp.iam.IamApi.token;
import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.persistence.WithPostgres;
import com.bmp.test.security.JwtTestTokens;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;

/** The caller's identity and effective permissions, always taken from the token. */
@SpringBootTest(classes = IamTestApplication.class)
@AutoConfigureMockMvc
@WithPostgres
class MeApiIT {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Test
    void describesAUserWithAProfile() {
        IamApi api = new IamApi(mvc, jdbc, transactionManager);
        TenantId tenant = api.createTenant("Me " + UUID.randomUUID());
        UUID userId = id(api.createUser(
                tenant, "me." + UUID.randomUUID().toString().substring(0, 8), "[{\"code\": \"CASHIER\"}]"));
        String cashier = token(tenant, UserId.of(userId), "CASHIER");

        MvcTestResult me = api.get("/api/v1/me", cashier);
        MvcTestResult permissions = api.get("/api/v1/me/permissions", cashier);

        assertThat(me).hasStatus(200);
        CONTRACT.assertConforms(me);
        assertThat(json(me).path("userId").asString()).isEqualTo(userId.toString());
        assertThat(json(me).path("tenantId").asString()).isEqualTo(tenant.toString());
        assertThat(json(me).path("displayName").asString()).startsWith("me.");
        assertThat(json(me).path("platformAdmin").asBoolean()).isFalse();
        assertThat(permissions).hasStatus(200);
        CONTRACT.assertConforms(permissions);
        List<String> grants = new ArrayList<>();
        json(permissions).path("permissions").forEach(node -> grants.add(node.asString()));
        assertThat(grants).containsExactlyInAnyOrderElementsOf(SystemRole.CASHIER.defaultPermissions());
    }

    @Test
    void describesAPlatformAdministratorWithoutTenant() {
        IamApi api = new IamApi(mvc, jdbc, transactionManager);

        MvcTestResult me = api.get("/api/v1/me", JwtTestTokens.platformAdmin());
        MvcTestResult permissions = api.get("/api/v1/me/permissions", JwtTestTokens.platformAdmin());

        assertThat(me).hasStatus(200);
        CONTRACT.assertConforms(me);
        assertThat(json(me).path("platformAdmin").asBoolean()).isTrue();
        assertThat(json(me).has("tenantId")).isFalse();
        assertThat(json(permissions).path("permissions").get(0).asString()).isEqualTo("platform:*");
    }

    @Test
    void requiresAToken() {
        MvcTestResult anonymous = new IamApi(mvc, jdbc, transactionManager).get("/api/v1/me", null);

        assertThatProblem(anonymous).hasStatus(401);
        CONTRACT.assertConforms(anonymous);
    }
}
