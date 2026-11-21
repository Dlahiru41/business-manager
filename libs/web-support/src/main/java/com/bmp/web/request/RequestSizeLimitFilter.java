package com.bmp.web.request;

import com.bmp.shared.error.BusinessRuleViolation;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.web.problem.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects request bodies above the configured limit with 413 {@code PAYLOAD_TOO_LARGE} (08-security.md §4). Declared
 * lengths are checked up front; chunked bodies are counted while they are read.
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    private final long maxBytes;
    private final ProblemWriter problems;

    public RequestSizeLimitFilter(long maxBytes, ProblemWriter problems) {
        this.maxBytes = maxBytes;
        this.problems = problems;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            problems.write(request, response, tooLarge());
            return;
        }
        chain.doFilter(new LimitedRequest(request, maxBytes), response);
    }

    private BusinessRuleViolation tooLarge() {
        return new BusinessRuleViolation(PlatformErrorCode.PAYLOAD_TOO_LARGE, Map.of("maxBytes", maxBytes));
    }

    private final class LimitedRequest extends HttpServletRequestWrapper {

        private final ServletInputStream stream;

        LimitedRequest(HttpServletRequest request, long limit) {
            super(request);
            this.stream = new ServletInputStream() {
                private long read;
                private @Nullable ServletInputStream delegate;

                private ServletInputStream delegate() throws IOException {
                    ServletInputStream current = delegate;
                    if (current == null) {
                        current = LimitedRequest.super.getInputStream();
                        delegate = current;
                    }
                    return current;
                }

                @Override
                public int read() throws IOException {
                    int b = delegate().read();
                    if (b >= 0) {
                        count(1);
                    }
                    return b;
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    int n = delegate().read(b, off, len);
                    if (n > 0) {
                        count(n);
                    }
                    return n;
                }

                private void count(int n) {
                    read += n;
                    if (read > limit) {
                        throw tooLarge();
                    }
                }

                @Override
                public boolean isFinished() {
                    ServletInputStream current = delegate;
                    return current != null && current.isFinished();
                }

                @Override
                public boolean isReady() {
                    ServletInputStream current = delegate;
                    return current == null || current.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Async reads are not supported");
                }
            };
        }

        @Override
        public ServletInputStream getInputStream() {
            return stream;
        }
    }
}
