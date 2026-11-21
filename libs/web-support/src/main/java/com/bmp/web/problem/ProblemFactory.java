package com.bmp.web.problem;

import com.bmp.shared.context.MdcKeys;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.FieldError;
import com.bmp.web.request.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * Builds the platform's RFC 9457 body (04-error-handling.md §3): standard members plus {@code code},
 * {@code requestId}, {@code traceId}, {@code timestamp}, safe {@code details} and field {@code errors}.
 */
public class ProblemFactory {

    private final URI baseUri;
    private final ProblemMessages messages;
    private final Clock clock;

    public ProblemFactory(URI baseUri, ProblemMessages messages, Clock clock) {
        this.baseUri = baseUri;
        this.messages = messages;
        this.clock = clock;
    }

    /**
     * Creates a problem.
     *
     * @param detail human-readable detail; when {@code null} the localized template (or the title) is used
     */
    public ProblemDetail create(
            ErrorCode code,
            HttpStatusCode status,
            @Nullable String detail,
            Map<String, Object> details,
            List<FieldError> errors,
            HttpServletRequest request,
            Locale locale) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(typeOf(code));
        String title = messages.title(code, locale);
        problem.setTitle(title);
        String localized = messages.detail(code, details, locale);
        problem.setDetail(detail != null ? detail : localized != null ? localized : title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code.code());
        Object requestId = request.getAttribute(RequestAttributes.REQUEST_ID);
        if (requestId != null) {
            problem.setProperty("requestId", requestId.toString());
        }
        String traceId = MDC.get(MdcKeys.TRACE_ID);
        if (traceId != null) {
            problem.setProperty("traceId", traceId);
        }
        problem.setProperty("timestamp", clock.instant().toString());
        if (!details.isEmpty()) {
            problem.setProperty("details", stringify(details));
        }
        if (!errors.isEmpty()) {
            problem.setProperty(
                    "errors", errors.stream().map(ProblemFactory::toMap).toList());
        }
        return problem;
    }

    /** {@code https://errors.bmp.example/inventory/insufficient-stock}. */
    public URI typeOf(ErrorCode code) {
        String base = baseUri.toString().replaceAll("/+$", "");
        String slug = code.code().toLowerCase(Locale.ROOT).replace('_', '-');
        return URI.create(base + "/" + code.module() + "/" + slug);
    }

    private static Map<String, Object> stringify(Map<String, Object> details) {
        Map<String, Object> safe = new LinkedHashMap<>();
        details.forEach((key, value) ->
                safe.put(key, value instanceof Number || value instanceof Boolean ? value : value.toString()));
        return safe;
    }

    private static Map<String, String> toMap(FieldError error) {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("field", error.field());
        map.put("code", error.code());
        map.put("message", error.message());
        return map;
    }
}
