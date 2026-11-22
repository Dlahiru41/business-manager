package com.bmp.web.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

/** Roles come from where Keycloak puts them, and only from there (ADR-016). */
class BmpJwtAuthenticationConverterTest {

    private static final String ADMIN_CLIENT = "bmp-backoffice";
    private final BmpJwtAuthenticationConverter converter = new BmpJwtAuthenticationConverter(List.of(ADMIN_CLIENT));

    private BmpPrincipal convert(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return ((BmpAuthentication) converter.convert(jwt)).bmpPrincipal();
    }

    private static Map<String, Object> claims(Object... keyValues) {
        Map<String, Object> claims = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            claims.put((String) keyValues[i], keyValues[i + 1]);
        }
        return claims;
    }

    @Test
    void tenantRolesComeOnlyFromTheRolesClaim() {
        BmpPrincipal principal = convert(claims(
                "azp", "bmp-pos",
                "tenant_id", UUID.randomUUID().toString(),
                "roles", List.of("CASHIER"),
                "realm_access", Map.of("roles", List.of("default-roles-bmp", "offline_access", "OWNER"))));

        assertThat(principal.roles()).containsExactly("CASHIER");
        assertThat(principal.isPlatformAdmin()).isFalse();
    }

    @Test
    void thePlatformRoleComesFromRealmRolesThroughAnAllowedClientOnly() {
        Map<String, Object> realm = Map.of("roles", List.of("default-roles-bmp", "platform_admin"));

        BmpPrincipal admin = convert(claims("azp", ADMIN_CLIENT, "realm_access", realm));
        BmpPrincipal otherClient = convert(claims("azp", "some-client", "realm_access", realm));
        BmpPrincipal noClient = convert(claims("realm_access", realm));

        assertThat(admin.roles()).containsExactly("platform_admin");
        assertThat(admin.isPlatformAdmin()).isTrue();
        assertThat(admin.tenantRoles()).isEmpty();
        assertThat(otherClient.isPlatformAdmin()).isFalse();
        assertThat(noClient.isPlatformAdmin()).isFalse();
    }

    @Test
    void forgedPlatformAndDeviceRolesInTheFlatClaimAreIgnored() {
        BmpPrincipal forged =
                convert(claims("azp", ADMIN_CLIENT, "roles", List.of("platform_admin", "DEVICE", "OWNER")));

        assertThat(forged.roles()).containsExactly("OWNER");
        assertThat(forged.isPlatformAdmin()).isFalse();
    }

    @Test
    void deviceTokensComeFromTheirOwnClientAndHoldOnlyTheDeviceRole() {
        String device = UUID.randomUUID().toString();

        BmpPrincipal till = convert(claims(
                "azp", "device-" + device,
                "device_id", device,
                "tenant_id", UUID.randomUUID().toString(),
                "roles", List.of("DEVICE", "OWNER"),
                "realm_access", Map.of("roles", List.of("platform_admin"))));

        assertThat(till.roles()).containsExactly("DEVICE");
        assertThat(till.deviceId()).isNotNull();
        assertThatThrownBy(() -> convert(claims("azp", "bmp-pos", "device_id", device)))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> convert(claims("azp", "device-" + UUID.randomUUID(), "device_id", device)))
                .isInstanceOf(InvalidBearerTokenException.class);
        assertThatThrownBy(() -> convert(claims("azp", "device-" + device, "roles", List.of("OWNER"))))
                .isInstanceOf(InvalidBearerTokenException.class);
    }
}
