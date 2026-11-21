package com.bmp.web;

import static com.bmp.test.web.ProblemAssert.assertThatProblem;
import static org.assertj.core.api.Assertions.assertThat;

import com.bmp.shared.context.TenantContext;
import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.bmp.test.security.JwtTestTokens;
import com.bmp.web.security.TenantStatusProvider.TenantStatus;
import com.bmp.web.testapp.WebSupportTestApplication;
import com.bmp.web.testapp.WebSupportTestApplication.TestDevices;
import com.bmp.web.testapp.WebSupportTestApplication.TestTenants;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/** T00-06: 401 without a valid token, 403 without permission, tenant resolution rules (08-security.md). */
@SpringBootTest(
        classes = WebSupportTestApplication.class,
        properties = "bmp.web.cors-allowed-origins=https://pos.example.lk")
@AutoConfigureMockMvc
@ActiveProfiles("security-api")
class SecurityApiTest {

    private static final String PATH = "/api/v1/test-secured";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTenants tenants;

    @Autowired
    TestDevices devices;

    private final TenantId tenant = TenantId.of(UUID.randomUUID());

    private MvcTestResult get(String path, String token) {
        return mvc.get().uri(path).header("Authorization", "Bearer " + token).exchange();
    }

    @Test
    void shouldRequireABearerToken() {
        MvcTestResult result = mvc.get().uri(PATH).exchange();

        assertThatProblem(result).hasStatus(401).hasCode("UNAUTHENTICATED").hasStandardMembers();
        assertThat(result).hasHeader("WWW-Authenticate", "Bearer");
    }

    @Test
    void shouldRejectInvalidTokensWith401() {
        String[] invalid = {
            "not-a-jwt",
            JwtTestTokens.builder()
                    .tenant(tenant)
                    .roles("OWNER")
                    .expiredSince(Duration.ofMinutes(5))
                    .build(),
            JwtTestTokens.builder()
                    .tenant(tenant)
                    .roles("OWNER")
                    .audience("other-api")
                    .build(),
            JwtTestTokens.builder()
                    .tenant(tenant)
                    .roles("OWNER")
                    .issuer("https://evil.example")
                    .build(),
            JwtTestTokens.builder()
                    .tenant(tenant)
                    .roles("OWNER")
                    .signedWithForeignKey()
                    .build()
        };
        for (String token : invalid) {
            assertThatProblem(get(PATH, token))
                    .hasStatus(401)
                    .hasCode("UNAUTHENTICATED")
                    .hasNoInternals();
        }
    }

    @Test
    void shouldReturn403WithoutThePermission() {
        assertThatProblem(get(PATH, JwtTestTokens.forRole(tenant, SystemRole.CASHIER)))
                .hasStatus(403)
                .hasCode("ACCESS_DENIED");
        assertThat(get(PATH + "/sales", JwtTestTokens.forRole(tenant, SystemRole.CASHIER)))
                .hasStatusOk();
    }

    @Test
    void shouldBindTheTokensTenantAndUser() {
        UserId user = UserId.of(UUID.randomUUID());
        String token =
                JwtTestTokens.builder().user(user).tenant(tenant).roles("OWNER").build();

        assertThat(get(PATH, token))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.tenant", t -> assertThat(t).isEqualTo(tenant.toString()))
                .hasPathSatisfying("$.user", u -> assertThat(u).isEqualTo(user.toString()));
        assertThat(TenantContext.current()).as("no leak after the request").isEmpty();
    }

    @Test
    void ten04SuspendedTenantsAreReadOnly() {
        tenants.set(tenant, TenantStatus.SUSPENDED);
        String token = JwtTestTokens.forRole(tenant, SystemRole.OWNER);

        assertThat(get(PATH, token)).hasStatusOk();
        assertThatProblem(mvc.post()
                        .uri(PATH)
                        .header("Authorization", "Bearer " + token)
                        .exchange())
                .hasStatus(403)
                .hasCode("TENANT_SUSPENDED");
    }

    @Test
    void shouldRejectUnknownOrClosedTenantsAndTokensWithoutTenant() {
        tenants.set(tenant, TenantStatus.CLOSED);

        assertThatProblem(get(PATH, JwtTestTokens.forRole(tenant, SystemRole.OWNER)))
                .hasStatus(403)
                .hasCode("TENANT_NOT_ACTIVE");
        assertThatProblem(get(PATH, JwtTestTokens.builder().roles("OWNER").build()))
                .hasStatus(403)
                .hasCode("TENANT_NOT_ACTIVE");
    }

    @Test
    void iam03RevokedDevicesAreRejected() {
        DeviceId device = DeviceId.of(UUID.randomUUID());
        String token = JwtTestTokens.builder()
                .tenant(tenant)
                .device(device)
                .roles("CASHIER")
                .build();
        assertThat(get(PATH + "/sales", token)).hasStatusOk();

        devices.revoke(device);

        assertThatProblem(get(PATH + "/sales", token)).hasStatus(403).hasCode("DEVICE_REVOKED");
    }

    @Test
    void platformAdminHasPlatformPermissionsButTenantOnesOnlyWhenActingForATenant() {
        String admin = JwtTestTokens.platformAdmin();

        assertThat(get(PATH + "/platform", admin))
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.tenantBound", b -> assertThat(b).isEqualTo(false));
        assertThatProblem(get(PATH, admin)).hasStatus(403).hasCode("ACCESS_DENIED");
        assertThat(mvc.get()
                        .uri(PATH)
                        .header("Authorization", "Bearer " + admin)
                        .header("X-Tenant-Id", tenant.toString())
                        .exchange())
                .hasStatusOk()
                .bodyJson()
                .hasPathSatisfying("$.tenant", t -> assertThat(t).isEqualTo(tenant.toString()));
    }

    @Test
    void tenantUsersMayNotChooseATenantThroughTheHeader() {
        MvcTestResult result = mvc.get()
                .uri(PATH)
                .header("Authorization", "Bearer " + JwtTestTokens.forRole(tenant, SystemRole.OWNER))
                .header("X-Tenant-Id", UUID.randomUUID().toString())
                .exchange();

        assertThatProblem(result).hasStatus(403).hasCode("ACCESS_DENIED");
        assertThatProblem(get(PATH + "/platform", JwtTestTokens.forRole(tenant, SystemRole.OWNER)))
                .as("tenant owners have no platform permissions")
                .hasStatus(403);
    }

    @Test
    void shouldSendSecurityHeaders() {
        MvcTestResult result = get(PATH, JwtTestTokens.forRole(tenant, SystemRole.OWNER));

        assertThat(result).hasHeader("X-Content-Type-Options", "nosniff");
        assertThat(result).hasHeader("X-Frame-Options", "DENY");
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).contains("default-src 'none'");
    }

    @Test
    void corsShouldAllowOnlyConfiguredOrigins() {
        MvcTestResult allowed = mvc.options()
                .uri(PATH)
                .header("Origin", "https://pos.example.lk")
                .header("Access-Control-Request-Method", "POST")
                .exchange();
        MvcTestResult denied = mvc.options()
                .uri(PATH)
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange();

        assertThat(allowed).hasStatusOk().hasHeader("Access-Control-Allow-Origin", "https://pos.example.lk");
        assertThat(denied).hasStatus(403);
    }
}
