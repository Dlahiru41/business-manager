package com.bmp.test.security;

import com.bmp.shared.id.DeviceId;
import com.bmp.shared.id.TenantId;
import com.bmp.shared.id.UserId;
import com.bmp.shared.security.SystemRole;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Signed access tokens shaped like Keycloak's ({@code tenant_id}, {@code roles}, {@code device_id}, audience
 * {@code bmp-api}), so API tests run without an identity provider: {@code JwtTestTokens.forRole(tenant,
 * SystemRole.CASHIER)}. Pair with {@link TestJwtConfiguration}, which installs the matching decoder with the same
 * issuer, audience and expiry checks as production.
 */
public final class JwtTestTokens {

    public static final String ISSUER = "https://issuer.test.bmp/realms/bmp";
    public static final String AUDIENCE = "bmp-api";

    private static final RSAKey SIGNING_KEY = generate("test-signing-key");
    private static final RSAKey FOREIGN_KEY = generate("foreign-key");

    private JwtTestTokens() {}

    /** A token for a user with one role in {@code tenant}. */
    public static String forRole(TenantId tenant, SystemRole role) {
        return builder().tenant(tenant).roles(role.code()).build();
    }

    /** A platform administrator token (no tenant). */
    public static String platformAdmin() {
        return builder().roles(SystemRole.PLATFORM_ADMIN.code()).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The decoder matching the test signing key, with production-equivalent validation. */
    public static JwtDecoder decoder() {
        try {
            NimbusJwtDecoder decoder =
                    NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    new JwtTimestampValidator(Duration.ofSeconds(30)),
                    new JwtIssuerValidator(ISSUER),
                    new JwtClaimValidator<List<String>>(
                            JwtClaimNames.AUD, aud -> aud != null && aud.contains(AUDIENCE))));
            return decoder;
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private static RSAKey generate(String keyId) {
        try {
            return new RSAKeyGenerator(2048).keyID(keyId).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Fluent builder for tokens with specific claims or deliberate defects. */
    public static final class Builder {

        private UUID subject = UUID.randomUUID();
        private @Nullable TenantId tenant;
        private @Nullable DeviceId device;
        private final List<String> roles = new ArrayList<>();
        private String issuer = ISSUER;
        private String audience = AUDIENCE;
        private Instant issuedAt = Instant.now();
        private Duration lifetime = Duration.ofMinutes(15);
        private RSAKey key = SIGNING_KEY;

        public Builder user(UserId user) {
            this.subject = user.value();
            return this;
        }

        public Builder tenant(TenantId tenant) {
            this.tenant = tenant;
            return this;
        }

        public Builder device(DeviceId device) {
            this.device = device;
            return this;
        }

        public Builder roles(String... codes) {
            roles.addAll(Arrays.asList(codes));
            return this;
        }

        public Builder issuer(String issuer) {
            this.issuer = issuer;
            return this;
        }

        public Builder audience(String audience) {
            this.audience = audience;
            return this;
        }

        public Builder expiredSince(Duration ago) {
            this.issuedAt = Instant.now().minus(lifetime).minus(ago);
            return this;
        }

        public Builder signedWithForeignKey() {
            this.key = FOREIGN_KEY;
            return this;
        }

        public String build() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .subject(subject.toString())
                    .issuer(issuer)
                    .audience(audience)
                    .issueTime(Date.from(issuedAt))
                    .expirationTime(Date.from(issuedAt.plus(lifetime)))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("roles", List.copyOf(roles));
            if (tenant != null) {
                claims.claim("tenant_id", tenant.value().toString());
            }
            if (device != null) {
                claims.claim("device_id", device.value().toString());
            }
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .keyID(key.getKeyID())
                            .build(),
                    claims.build());
            try {
                jwt.sign(new RSASSASigner(key));
            } catch (JOSEException e) {
                throw new IllegalStateException(e);
            }
            return jwt.serialize();
        }
    }
}
