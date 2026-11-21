package com.bmp.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class BmpJwtAuthenticationConverterTest {

    private static BmpPrincipal convert(Map<String, Object> claims) {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "RS256")
                .subject(UUID.randomUUID().toString())
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return ((BmpAuthentication) new BmpJwtAuthenticationConverter().convert(jwt)).bmpPrincipal();
    }

    @Test
    void tenantRolesComeOnlyFromTheRolesClaim() {
        BmpPrincipal principal = convert(Map.of(
                "tenant_id", UUID.randomUUID().toString(),
                "roles", List.of("CASHIER"),
                "realm_access", Map.of("roles", List.of("default-roles-bmp", "offline_access", "OWNER"))));

        assertThat(principal.roles()).containsExactly("CASHIER");
        assertThat(principal.isPlatformAdmin()).isFalse();
    }

    @Test
    void thePlatformRoleComesFromRealmRoles() {
        BmpPrincipal principal =
                convert(Map.of("realm_access", Map.of("roles", List.of("default-roles-bmp", "platform_admin"))));

        assertThat(principal.roles()).containsExactly("platform_admin");
        assertThat(principal.isPlatformAdmin()).isTrue();
        assertThat(principal.tenantRoles()).isEmpty();
    }
}
