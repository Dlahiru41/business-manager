package com.bmp.iam.config;

import com.bmp.shared.security.Sensitive;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings of the iam module ({@code bmp.iam.*}). The admin client secret comes from the environment
 * ({@code BMP_KEYCLOAK_ADMIN_CLIENT_SECRET}); startup fails without it.
 */
@ConfigurationProperties("bmp.iam")
@Validated
public record IamProperties(@Valid @NotNull Keycloak keycloak) {

    /**
     * The Keycloak Admin REST API used to manage accounts and device clients.
     *
     * @param baseUrl e.g. {@code http://localhost:8180}
     * @param clientId the service account client
     * @param apiAudience audience the API expects in access tokens
     * @param connectTimeout TCP connect timeout
     * @param readTimeout response timeout per call
     * @param inviteLifespan validity of the "set your password" e-mail link
     */
    @Sensitive
    public record Keycloak(
            @NotNull URI baseUrl,
            @DefaultValue("bmp") @NotBlank String realm,
            @DefaultValue("bmp-admin") @NotBlank String clientId,
            @NotBlank String clientSecret,
            @DefaultValue("bmp-api") @NotBlank String apiAudience,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout,
            @DefaultValue("24h") Duration inviteLifespan) {

        @Override
        public String toString() {
            return "Keycloak[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId
                    + ", clientSecret=***]";
        }
    }
}
