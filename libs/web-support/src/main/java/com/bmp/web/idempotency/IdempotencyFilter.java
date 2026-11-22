package com.bmp.web.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Makes POST bodies re-readable (for the request hash) and buffers POST responses (to store them for replay). The
 * {@link IdempotencyInterceptor} does the actual idempotency work once the handler is known.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContentCachingResponseWrapper cachingResponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CachedBodyRequest(request), cachingResponse);
        } finally {
            cachingResponse.copyBodyToResponse();
        }
    }

    /** A request whose body is read once, lazily, and can then be read again. */
    public static final class CachedBodyRequest extends HttpServletRequestWrapper {

        private byte @Nullable [] body;

        CachedBodyRequest(HttpServletRequest request) {
            super(request);
        }

        /** The full body; reads it on first access. */
        public byte[] body() throws IOException {
            byte[] current = body;
            if (current == null) {
                current = super.getInputStream().readAllBytes();
                body = current;
            }
            return current;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ByteArrayInputStream in = new ByteArrayInputStream(body());
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported");
                }

                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding != null ? Charset.forName(encoding) : StandardCharsets.UTF_8;
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
