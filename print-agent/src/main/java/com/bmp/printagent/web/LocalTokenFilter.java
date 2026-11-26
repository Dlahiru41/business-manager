package com.bmp.printagent.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The local API answers only callers with the shared token ({@code Authorization: Bearer <agent.local-token>}),
 * compared in constant time. CORS preflights pass, so a browser till can ask first.
 */
class LocalTokenFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";
    private static final String UNAUTHORIZED = """
            {"type": "urn:bmp:print-agent:unauthenticated", "title": "Unauthorized", "status": 401, \
            "code": "UNAUTHENTICATED", "detail": "Send the agent's local token as a bearer token."}""";

    private final byte[] expected;

    LocalTokenFilter(String token) {
        this.expected = digest(token.strip());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (preflight(request) || authorized(request.getHeader(HttpHeaders.AUTHORIZATION))) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(UNAUTHORIZED);
    }

    private boolean authorized(@Nullable String header) {
        return header != null
                && header.startsWith(BEARER)
                && MessageDigest.isEqual(
                        digest(header.substring(BEARER.length()).strip()), expected);
    }

    private static boolean preflight(HttpServletRequest request) {
        return HttpMethod.OPTIONS.matches(request.getMethod())
                && request.getHeader(HttpHeaders.ORIGIN) != null
                && request.getHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD) != null;
    }

    private static byte[] digest(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}
