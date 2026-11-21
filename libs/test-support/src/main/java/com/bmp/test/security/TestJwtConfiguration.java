package com.bmp.test.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Replaces the JWKS-based decoder with one that accepts {@link JwtTestTokens}. Import it in API tests:
 * {@code @Import(TestJwtConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestJwtConfiguration {

    @Bean
    JwtDecoder jwtDecoder() {
        return JwtTestTokens.decoder();
    }
}
