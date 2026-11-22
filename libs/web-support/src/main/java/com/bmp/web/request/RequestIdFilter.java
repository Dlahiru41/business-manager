package com.bmp.web.request;

import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.id.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts {@code X-Request-Id} when it is a valid UUID, otherwise generates a UUIDv7; puts it in the MDC, the request
 * attributes and the response header (03-api-standards.md §4).
 */
public class RequestIdFilter extends OncePerRequestFilter {

    private final IdGenerator ids;

    public RequestIdFilter(IdGenerator ids) {
        this.ids = ids;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = accepted(request.getHeader(RequestAttributes.REQUEST_ID_HEADER));
        request.setAttribute(RequestAttributes.REQUEST_ID, requestId);
        response.setHeader(RequestAttributes.REQUEST_ID_HEADER, requestId);
        MDC.put(MdcKeys.REQUEST_ID, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MdcKeys.REQUEST_ID);
        }
    }

    private String accepted(@Nullable String header) {
        if (header != null) {
            try {
                return UUID.fromString(header.trim()).toString();
            } catch (IllegalArgumentException ignored) {
                // Not a UUID: never echo untrusted input into logs; generate a fresh id instead.
            }
        }
        return ids.newUuid().toString();
    }
}
