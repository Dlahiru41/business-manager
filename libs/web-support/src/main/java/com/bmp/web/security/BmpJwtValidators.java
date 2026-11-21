package com.bmp.web.security;

import java.time.Duration;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;

/** Token checks (08-security.md §1): issuer, audience {@code bmp-api}, expiry with at most 30 s clock skew. */
public final class BmpJwtValidators {

    public static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private BmpJwtValidators() {}

    public static OAuth2TokenValidator<Jwt> create(String issuer, String audience) {
        return new DelegatingOAuth2TokenValidator<>(
                new JwtTimestampValidator(CLOCK_SKEW),
                new JwtIssuerValidator(issuer),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD, aud -> aud != null && aud.contains(audience)));
    }
}
