package com.bmp.web.security;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Token validation settings ({@code bmp.security.*}).
 *
 * @param issuerUri expected {@code iss}, e.g. {@code http://localhost:8180/realms/bmp}
 * @param jwkSetUri signing keys; defaults to Keycloak's {@code <issuer>/protocol/openid-connect/certs}
 * @param audience expected {@code aud}, {@code bmp-api}
 * @param platformAdminClients clients ({@code azp}) a platform administrator may use; none by default (ADR-016)
 */
@Validated
@ConfigurationProperties("bmp.security")
public record SecurityProperties(
        @NotBlank String issuerUri,
        @Nullable String jwkSetUri,
        @DefaultValue("bmp-api") @NotBlank String audience,
        @Nullable List<String> platformAdminClients) {

    public SecurityProperties {
        platformAdminClients = platformAdminClients == null ? List.of() : List.copyOf(platformAdminClients);
    }

    @Override
    public List<String> platformAdminClients() {
        return Objects.requireNonNull(platformAdminClients);
    }

    public String effectiveJwkSetUri() {
        return jwkSetUri != null && !jwkSetUri.isBlank()
                ? jwkSetUri
                : issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/certs";
    }
}
