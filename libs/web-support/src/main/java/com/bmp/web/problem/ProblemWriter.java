package com.bmp.web.problem;

import com.bmp.shared.error.BmpException;
import com.bmp.shared.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a Problem Details response directly, for code running before the DispatcherServlet (filters, the security
 * entry point and access-denied handler) so every error has the same body format.
 */
public class ProblemWriter {

    private final ProblemFactory factory;
    private final JsonMapper json;
    private final LocaleResolver locales;

    public ProblemWriter(ProblemFactory factory, JsonMapper json, LocaleResolver locales) {
        this.factory = factory;
        this.json = json;
        this.locales = locales;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, BmpException exception)
            throws IOException {
        write(request, response, exception.errorCode(), exception.details());
    }

    public void write(
            HttpServletRequest request, HttpServletResponse response, ErrorCode code, Map<String, Object> details)
            throws IOException {
        HttpStatus status = HttpStatuses.of(code);
        ProblemDetail problem =
                factory.create(code, status, null, details, List.of(), request, locales.resolveLocale(request));
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), problem);
    }
}
