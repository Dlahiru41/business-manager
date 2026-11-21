package com.bmp.web.security;

import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.problem.ProblemWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** 401 and 403 from the security filter chain in the same Problem Details format as every other error. */
public class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemSecurityHandlers.class);

    private final ProblemWriter problems;

    public ProblemSecurityHandlers(ProblemWriter problems) {
        this.problems = problems;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException {
        // The reason (expired, bad signature, ...) stays out of the response; DEBUG only, it is not actionable.
        log.debug("Unauthenticated request uri={} reason={}", request.getRequestURI(), authException.getMessage());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        problems.write(request, response, PlatformErrorCode.UNAUTHENTICATED, Map.of());
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException {
        log.warn("Access denied method={} uri={}", request.getMethod(), request.getRequestURI());
        problems.write(request, response, PlatformErrorCode.ACCESS_DENIED, Map.of());
    }
}
