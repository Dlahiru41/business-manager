package com.bmp.web.request;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/**
 * One access log line per request (05-logging-and-observability.md §5): method, path <em>template</em> (never raw ids),
 * status, duration, response size and correlation ids. Requests slower than the threshold are logged at WARN.
 * Actuator endpoints are excluded.
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.bmp.web.access");
    private static final String UNMATCHED = "UNMATCHED";

    private final Clock clock;
    private final Duration slowThreshold;

    public AccessLogFilter(Clock clock, Duration slowThreshold) {
        this.clock = clock;
        this.slowThreshold = slowThreshold;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/actuator");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = clock.millis();
        CountingResponse counting = new CountingResponse(response);
        try {
            chain.doFilter(request, counting);
        } finally {
            write(request, counting, clock.millis() - start);
        }
    }

    private void write(HttpServletRequest request, CountingResponse response, long durationMs) {
        Level level = durationMs > slowThreshold.toMillis() ? Level.WARN : Level.INFO;
        String template = attribute(request, HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String path = template != null ? template : UNMATCHED;
        LoggingEventBuilder event = log.atLevel(level)
                .setMessage("{} {} -> {} in {} ms")
                .addArgument(request.getMethod())
                .addArgument(path)
                .addArgument(response.getStatus())
                .addArgument(durationMs)
                .addKeyValue("http.method", request.getMethod())
                .addKeyValue("http.path", path)
                .addKeyValue("http.status", response.getStatus())
                .addKeyValue("durationMs", durationMs)
                .addKeyValue("responseBytes", response.bytesWritten())
                .addKeyValue(
                        "idempotentReplay",
                        Boolean.TRUE.equals(request.getAttribute(RequestAttributes.IDEMPOTENT_REPLAY)));
        event = addIfPresent(event, "requestId", attribute(request, RequestAttributes.REQUEST_ID));
        event = addIfPresent(event, "tenantId", attribute(request, RequestAttributes.TENANT_ID));
        event = addIfPresent(event, "userId", attribute(request, RequestAttributes.USER_ID));
        event = addIfPresent(event, "deviceId", attribute(request, RequestAttributes.DEVICE_ID));
        event.log();
    }

    /**
     * Adds a correlation key unless the MDC already carries it: structured logging rejects duplicate keys and would
     * drop the whole line. Inner filters clear their MDC keys before this filter logs, hence the request attributes.
     */
    private static LoggingEventBuilder addIfPresent(LoggingEventBuilder event, String key, @Nullable String value) {
        return value != null && MDC.get(key) == null ? event.addKeyValue(key, value) : event;
    }

    private static @Nullable String attribute(HttpServletRequest request, String name) {
        Object value = request.getAttribute(name);
        return value != null ? value.toString() : null;
    }

    /** Counts body bytes without buffering them. */
    private static final class CountingResponse extends HttpServletResponseWrapper {

        private long bytes;
        private @Nullable ServletOutputStream stream;
        private @Nullable PrintWriter writer;

        CountingResponse(HttpServletResponse response) {
            super(response);
        }

        long bytesWritten() {
            if (writer != null) {
                writer.flush();
            }
            return bytes;
        }

        @Override
        public ServletOutputStream getOutputStream() throws IOException {
            if (stream == null) {
                ServletOutputStream delegate = super.getOutputStream();
                stream = new ServletOutputStream() {
                    @Override
                    public boolean isReady() {
                        return delegate.isReady();
                    }

                    @Override
                    public void setWriteListener(WriteListener listener) {
                        delegate.setWriteListener(listener);
                    }

                    @Override
                    public void write(int b) throws IOException {
                        delegate.write(b);
                        bytes++;
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        delegate.write(b, off, len);
                        bytes += len;
                    }

                    @Override
                    public void flush() throws IOException {
                        delegate.flush();
                    }
                };
            }
            return stream;
        }

        @Override
        public PrintWriter getWriter() throws IOException {
            if (writer == null) {
                String encoding = getCharacterEncoding();
                writer = new PrintWriter(
                        new OutputStreamWriter(
                                getOutputStream(),
                                encoding != null ? java.nio.charset.Charset.forName(encoding) : StandardCharsets.UTF_8),
                        false);
            }
            return writer;
        }

        @Override
        public void flushBuffer() throws IOException {
            if (writer != null) {
                writer.flush();
            }
            super.flushBuffer();
        }
    }
}
