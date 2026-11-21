package com.bmp.web.security;

import jakarta.validation.constraints.NotBlank;
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
 */
@Validated
@ConfigurationProperties("bmp.security")
public record SecurityProperties(
        @NotBlank String issuerUri,
        @Nullable String jwkSetUri,
        @DefaultValue("bmp-api") @NotBlank String audience) {

    public String effectiveJwkSetUri() {
        return jwkSetUri != null && !jwkSetUri.isBlank()
                ? jwkSetUri
                : issuerUri.replaceAll("/+$", "") + "/protocol/openid-connect/certs";
    }
}
