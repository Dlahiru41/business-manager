package com.bmp.web.problem;

import com.bmp.persistence.tenant.TenantSql;
import com.bmp.shared.error.BmpException;
import com.bmp.shared.error.ErrorCategory;
import com.bmp.shared.error.ErrorCode;
import com.bmp.shared.error.FieldError;
import com.bmp.shared.error.PlatformErrorCode;
import com.bmp.shared.error.ValidationFailure;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * The only place exceptions become HTTP responses (04-error-handling.md §3). Every response is
 * {@code application/problem+json}; 5xx bodies are generic and details stay in the logs. Each failure is logged once,
 * here, at the level its category prescribes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String GENERIC_INTERNAL_DETAIL =
            "An unexpected error occurred. Quote requestId when contacting support.";
    private static final String RETRY_AFTER_DEPENDENCY_SECONDS = "5";
    private static final String RETRY_AFTER_IN_PROGRESS_SECONDS = "1";

    private final ProblemFactory problems;
    private final ConstraintErrorRegistry constraints;
    private final LocaleResolver locales;

    public GlobalExceptionHandler(
            ProblemFactory problems, ConstraintErrorRegistry constraints, LocaleResolver locales) {
        this.problems = problems;
        this.constraints = constraints;
        this.locales = locales;
    }

    @ExceptionHandler(BmpException.class)
    ResponseEntity<Object> handleBmp(BmpException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        boolean internal = code.category() == ErrorCategory.INTERNAL;
        List<FieldError> errors = ex instanceof ValidationFailure failure ? failure.fieldErrors() : List.of();
        String detail = internal ? GENERIC_INTERNAL_DETAIL : ex.explicitMessage();
        Map<String, Object> details = internal ? Map.of() : ex.details();
        logFailure(code, request, ex);
        return respond(code, HttpStatuses.of(code), detail, details, errors, request, new HttpHeaders());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        logFailure(PlatformErrorCode.VERSION_CONFLICT, request, ex);
        return respond(PlatformErrorCode.VERSION_CONFLICT, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        ErrorCode code = constraints.resolve(ex).orElse(PlatformErrorCode.INTERNAL_ERROR);
        logFailure(code, request, ex);
        if (code == PlatformErrorCode.INTERNAL_ERROR) {
            return respondInternal(request);
        }
        return respond(code, request);
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Object> handleDataAccess(DataAccessException ex, HttpServletRequest request) {
        boolean missingTenant = ConstraintErrorRegistry.sqlState(ex)
                .filter(TenantSql.TENANT_CONTEXT_MISSING_SQLSTATE::equals)
                .isPresent();
        ErrorCode code = missingTenant ? PlatformErrorCode.TENANT_CONTEXT_MISSING : PlatformErrorCode.INTERNAL_ERROR;
        logFailure(code, request, ex);
        return respondInternal(request, code);
    }

    /** {@code @PreAuthorize} denials raised inside controllers. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        logFailure(PlatformErrorCode.ACCESS_DENIED, request, null);
        return respond(PlatformErrorCode.ACCESS_DENIED, request);
    }

    @ExceptionHandler(AuthenticationException.class)
    ResponseEntity<Object> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        logFailure(PlatformErrorCode.UNAUTHENTICATED, request, null);
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        return respond(
                PlatformErrorCode.UNAUTHENTICATED,
                HttpStatuses.of(PlatformErrorCode.UNAUTHENTICATED),
                null,
                Map.of(),
                List.of(),
                request,
                headers);
    }

    @ExceptionHandler(CallNotPermittedException.class)
    ResponseEntity<Object> handleOpenCircuit(CallNotPermittedException ex, HttpServletRequest request) {
        logFailure(PlatformErrorCode.DEPENDENCY_UNAVAILABLE, request, ex);
        return respond(PlatformErrorCode.DEPENDENCY_UNAVAILABLE, request);
    }

    /**
     * A path or query parameter broke its constraint where the generated interfaces validate through a
     * {@code @Validated} proxy (Spring's method validation interceptor), e.g. a period that is not {@code YYYY-MM}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldError> errors = ex.getConstraintViolations().stream()
                .map(v -> new FieldError(
                        parameter(v.getPropertyPath()),
                        constraintCode(v.getConstraintDescriptor()
                                .getAnnotation()
                                .annotationType()
                                .getName()),
                        v.getMessage()))
                .sorted(java.util.Comparator.comparing(FieldError::field))
                .toList();
        return validation(PlatformErrorCode.INVALID_PARAMETER, errors, request);
    }

    /** {@code listJournals.period} becomes {@code period}. */
    private static String parameter(Path path) {
        String name = "parameter";
        for (Path.Node node : path) {
            if (node.getName() != null) {
                name = node.getName();
            }
        }
        return name;
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex, HttpServletRequest request) {
        logFailure(PlatformErrorCode.INTERNAL_ERROR, request, ex);
        return respondInternal(request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        ex.getBindingResult()
                .getFieldErrors()
                .forEach(e -> errors.add(new FieldError(e.getField(), constraintCode(e.getCode()), message(e))));
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(e -> errors.add(new FieldError(e.getObjectName(), constraintCode(e.getCode()), message(e))));
        return validation(PlatformErrorCode.VALIDATION_FAILED, errors, request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        boolean bodyInvalid = false;
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            bodyInvalid |= result.getMethodParameter().hasParameterAnnotation(RequestBody.class);
            String name = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                String[] codes = error.getCodes();
                String constraint = codes != null && codes.length > 0 ? codes[codes.length - 1] : null;
                errors.add(
                        new FieldError(name != null ? name : "parameter", constraintCode(constraint), message(error)));
            }
        }
        return validation(
                bodyInvalid ? PlatformErrorCode.VALIDATION_FAILED : PlatformErrorCode.INVALID_PARAMETER,
                errors,
                request);
    }

    @Override
    protected @Nullable ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldError> errors = new ArrayList<>();
        Throwable cause = ex.getCause();
        if (cause instanceof UnrecognizedPropertyException unknown) {
            errors.add(new FieldError(path(unknown), "UNKNOWN_PROPERTY", "is not a known property"));
        } else if (cause instanceof DatabindException databind
                && !databind.getPath().isEmpty()) {
            errors.add(new FieldError(path(databind), "INVALID_FORMAT", "has an invalid value"));
        }
        return validation(PlatformErrorCode.MALFORMED_REQUEST, errors, request);
    }

    /** Every other Spring MVC exception: keep Spring's status, attach a platform code. */
    @Override
    protected @Nullable ResponseEntity<Object> handleExceptionInternal(
            Exception ex, @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        ErrorCode code = codeFor(ex, statusCode);
        logFailure(code, servletRequest, ex);
        HttpHeaders responseHeaders = new HttpHeaders();
        if (ex instanceof HttpRequestMethodNotSupportedException && headers.getAllow() != null) {
            responseHeaders.setAllow(headers.getAllow());
        }
        if (statusCode.is5xxServerError()) {
            return respondInternal(servletRequest);
        }
        return respond(code, statusCode, null, Map.of(), List.of(), servletRequest, responseHeaders);
    }

    private static ErrorCode codeFor(Exception ex, HttpStatusCode status) {
        if (ex instanceof HttpMediaTypeNotSupportedException) {
            return PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE;
        }
        return switch (status.value()) {
            case 400 ->
                ex instanceof HttpMessageNotReadableException
                        ? PlatformErrorCode.MALFORMED_REQUEST
                        : PlatformErrorCode.INVALID_PARAMETER;
            case 404 -> PlatformErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> PlatformErrorCode.METHOD_NOT_ALLOWED;
            case 413 -> PlatformErrorCode.PAYLOAD_TOO_LARGE;
            case 415 -> PlatformErrorCode.UNSUPPORTED_MEDIA_TYPE;
            case 422 -> PlatformErrorCode.VALIDATION_FAILED;
            case 503 -> PlatformErrorCode.DEPENDENCY_UNAVAILABLE;
            default ->
                status.is4xxClientError() ? PlatformErrorCode.MALFORMED_REQUEST : PlatformErrorCode.INTERNAL_ERROR;
        };
    }

    private ResponseEntity<Object> validation(ErrorCode code, List<FieldError> errors, WebRequest request) {
        HttpServletRequest servletRequest = ((ServletWebRequest) request).getRequest();
        logFailure(code, servletRequest, null);
        return respond(code, HttpStatuses.of(code), null, Map.of(), errors, servletRequest, new HttpHeaders());
    }

    private ResponseEntity<Object> respond(ErrorCode code, HttpServletRequest request) {
        return respond(code, HttpStatuses.of(code), null, Map.of(), List.of(), request, new HttpHeaders());
    }

    private ResponseEntity<Object> respondInternal(HttpServletRequest request) {
        return respondInternal(request, PlatformErrorCode.INTERNAL_ERROR);
    }

    private ResponseEntity<Object> respondInternal(HttpServletRequest request, ErrorCode code) {
        return respond(
                code,
                HttpStatus.INTERNAL_SERVER_ERROR,
                GENERIC_INTERNAL_DETAIL,
                Map.of(),
                List.of(),
                request,
                new HttpHeaders());
    }

    private ResponseEntity<Object> respond(
            ErrorCode code,
            HttpStatusCode status,
            @Nullable String detail,
            Map<String, Object> details,
            List<FieldError> errors,
            HttpServletRequest request,
            HttpHeaders headers) {
        Locale locale = locales.resolveLocale(request);
        ProblemDetail problem = problems.create(code, status, detail, details, errors, request, locale);
        if (status.value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_DEPENDENCY_SECONDS);
        }
        if (code == PlatformErrorCode.REQUEST_IN_PROGRESS) {
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_IN_PROGRESS_SECONDS);
        }
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    private static void logFailure(ErrorCode code, HttpServletRequest request, @Nullable Throwable ex) {
        String message = "Request failed code={} method={} uri={}";
        Object[] args = {code.code(), request.getMethod(), request.getRequestURI()};
        switch (code.category()) {
            case VALIDATION, NOT_FOUND -> log.debug(message, args);
            case CONFLICT, PRECONDITION, RATE_LIMITED -> log.info(message, args);
            case FORBIDDEN -> log.warn(message, args);
            case DEPENDENCY ->
                log.warn(
                        message + " cause={}",
                        code.code(),
                        request.getMethod(),
                        request.getRequestURI(),
                        String.valueOf(ex),
                        ex);
            case INTERNAL -> log.error(message, code.code(), request.getMethod(), request.getRequestURI(), ex);
        }
    }

    private static String constraintCode(@Nullable String constraint) {
        if (constraint == null || constraint.isBlank()) {
            return "INVALID";
        }
        String simple = constraint.substring(constraint.lastIndexOf('.') + 1);
        return simple.replaceAll("(?<=[a-z0-9])(?=[A-Z])", "_").toUpperCase(Locale.ROOT);
    }

    private static String message(MessageSourceResolvable error) {
        String message = error.getDefaultMessage();
        return message != null ? message : "is invalid";
    }

    private static String path(DatabindException ex) {
        StringBuilder path = new StringBuilder();
        for (var reference : ex.getPath()) {
            if (reference.getIndex() >= 0) {
                path.append('[').append(reference.getIndex()).append(']');
            } else if (reference.getPropertyName() != null) {
                if (!path.isEmpty()) {
                    path.append('.');
                }
                path.append(reference.getPropertyName());
            }
        }
        return path.isEmpty() ? "body" : path.toString();
    }
}
