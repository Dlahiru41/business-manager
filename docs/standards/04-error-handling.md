# 04 — Error and exception handling

Goal: every failure is (1) reported to the client as a stable, machine-readable Problem Details response, (2) logged exactly once at the right level with full context, and (3) never leaves data half-written.

## 1. Exception hierarchy (`libs/shared-kernel`)

```java
public interface ErrorCode {
    String code();          // stable, UPPER_SNAKE_CASE, never renamed once released: "VARIANT_NOT_FOUND"
    ErrorCategory category();
    String titleKey();      // i18n message key for the human title
}

public enum ErrorCategory { VALIDATION, NOT_FOUND, CONFLICT, FORBIDDEN, PRECONDITION, RATE_LIMITED, DEPENDENCY, INTERNAL }

public abstract sealed class BmpException extends RuntimeException
        permits DomainException, IntegrationException, TechnicalException {
    private final ErrorCode errorCode;
    private final Map<String, Object> details;   // safe-to-expose context (ids, limits), never PII
    ...
}

public non-sealed class DomainException extends BmpException { }      // a business rule said no
public final class NotFoundException extends DomainException { }
public final class BusinessRuleViolation extends DomainException { }  // 409 or 422 depending on category
public final class ValidationFailure extends DomainException { List<FieldError> fieldErrors; }
public final class VersionConflict extends DomainException { long currentVersion; }
public final class IntegrationException extends BmpException { }    // downstream system failed (printer, Keycloak, Kafka)
public final class TechnicalException extends BmpException { }      // invariant broken, bug, misconfiguration
```

Each module defines its own `enum <Module>ErrorCode implements ErrorCode` in `domain/error`, e.g.:

```java
public enum InventoryErrorCode implements ErrorCode {
    STOCK_ITEM_NOT_FOUND(NOT_FOUND),
    INSUFFICIENT_STOCK(CONFLICT),                // only for operations that may be blocked (adjustments, transformations)
    TRANSFORMATION_DEFINITION_INACTIVE(CONFLICT),
    UNIT_NOT_CONVERTIBLE(VALIDATION),
    COUNT_ALREADY_POSTED(CONFLICT);
    ...
}
```

Every error code appears in the module's OpenAPI file (`x-error-codes` list per operation) and has an i18n title in `messages*.properties` (`en`, `si`, `ta`).

## 2. Category → HTTP mapping

| Category | HTTP | Log level |
| --- | --- | --- |
| VALIDATION | 422 (400 for malformed syntax) | `DEBUG` (no stack trace) |
| NOT_FOUND | 404 | `DEBUG` |
| CONFLICT | 409 | `INFO` (no stack trace) |
| FORBIDDEN | 403 | `WARN` (security-relevant; no stack trace) |
| PRECONDITION | 412 / 428 | `INFO` |
| RATE_LIMITED | 429 | `INFO` |
| DEPENDENCY | 503 (or 502) | `WARN` with cause chain, stack once |
| INTERNAL | 500 | `ERROR` with full stack trace |

## 3. Global handler (`libs/web-support`)

`GlobalExceptionHandler extends ResponseEntityExceptionHandler`, annotated `@RestControllerAdvice`, is the **only** place exceptions become HTTP responses.

It handles:
- `BmpException` subclasses → mapping above.
- Spring MVC exceptions (`MethodArgumentNotValidException`, `HandlerMethodValidationException`, `HttpMessageNotReadableException`, `MissingRequestHeaderException`, type mismatch) → 400/422 with field errors.
- `ObjectOptimisticLockingFailureException` → 412 `VERSION_CONFLICT`.
- `DataIntegrityViolationException` → map known unique constraints to a domain code via a constraint-name registry (e.g. `uk_variant_tenant_sku` → `SKU_ALREADY_EXISTS`, 409); unknown → 500.
- `AccessDeniedException` → 403; `AuthenticationException` → 401 (via the security entry point, same body format).
- `CallNotPermittedException` (circuit open) → 503.
- `Exception` (fallback) → 500 with generic text.

Response body (RFC 9457, `application/problem+json`):

```json
{
  "type": "https://errors.bmp.example/inventory/insufficient-stock",
  "title": "Not enough stock",
  "status": 409,
  "detail": "Requested 20000 ml of Cooking Oil (OPEN) but 19400 ml is available",
  "instance": "/api/v1/stock/transformations",
  "code": "INSUFFICIENT_STOCK",
  "requestId": "0193a6f1-...",
  "traceId": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-11-21T09:42:13Z",
  "details": { "variantId": "...", "requested": "20000", "available": "19400" },
  "errors": [ { "field": "lines[0].qty", "code": "MUST_BE_POSITIVE", "message": "must be greater than 0" } ]
}
```

- `type` = `https://errors.<domain>/<module>/<kebab-code>`; documented in `contracts/openapi/common.yaml`.
- `detail` is human-readable, localized, and **never** contains stack traces, SQL, class names or PII.
- For 500: `title: "Internal error"`, `detail: "An unexpected error occurred. Quote requestId when contacting support."`.

## 4. Rules for throwing

- Throw the most specific `DomainException` with an `ErrorCode` and safe `details`. Never throw `RuntimeException`, `IllegalStateException` or `IllegalArgumentException` for business rules (they become 500s).
- `IllegalArgumentException` / `NullPointerException` are acceptable only inside value-object constructors for programmer errors; validation at the edge must stop bad input before it gets there.
- Validate in layers:
  1. **Syntax** (Bean Validation on generated DTOs): required, formats, sizes, patterns.
  2. **Semantics** (application layer): references exist, unit is sellable, customer has credit.
  3. **Invariants** (aggregate): state transitions, totals, non-negative where required.
- Collect all field errors in one `ValidationFailure` rather than failing on the first.
- Do not use exceptions for normal control flow (e.g. "price not found" during resolution returns `Optional`; the *caller* decides whether that is an error).

## 5. Rules for catching

- Catch only what you can handle. Otherwise let it propagate to the global handler / listener infrastructure.
- **Never swallow**: no empty catch blocks, no `catch (Exception e) { log.error(...); }` that continues as if nothing happened.
- When wrapping, keep the cause: `throw new IntegrationException(PRINTER_UNREACHABLE, Map.of("agentId", id), e);`
- **Log or throw, not both.** The layer that finally handles the exception logs it; intermediate layers only wrap/rethrow. The global handler and listener error handler do the logging.
- `InterruptedException`: restore the flag (`Thread.currentThread().interrupt()`) and rethrow as `TechnicalException`.
- Use try-with-resources for every closeable.

## 6. Transactions and failures

- A `DomainException` thrown inside an `@Transactional` handler rolls back the whole transaction (runtime exception). Do not catch it inside the transaction to "continue".
- Events published via `ApplicationEventPublisher` inside the transaction are only delivered (and recorded in the publication registry) if the transaction commits.
- Never call external systems (printer, HTTP, mail) inside a database transaction. Publish an event and do it in an after-commit listener.

## 7. Errors in event listeners

- Listeners are `@ApplicationModuleListener` (async, new transaction, after commit). If a listener throws, Spring Modulith leaves the publication **incomplete**; it will be retried.
- Configure: `spring.modulith.events.republish-outstanding-events-on-restart=true` and a scheduled job that resubmits incomplete publications older than 1 minute, with exponential back-off, max 10 attempts, then marks them failed and raises an alert (metric `bmp.events.failed` + ERROR log).
- Listeners must be **idempotent** (inbox table, see `07-events-and-messaging.md`) so retries are safe.
- Distinguish: `DomainException` in a listener usually means data the listener cannot accept (e.g. unknown variant) → do not retry forever; record in `failed_event` table with the problem and alert. Transient failures (`TransientDataAccessException`, `IntegrationException`) → retry.

## 8. Outbound calls (print agent, Keycloak admin, SMS gateway, later service-to-service)

- Timeouts always set (connect 2 s, read 5 s unless the phase says otherwise).
- Retry only idempotent operations, with exponential back-off + jitter, max 3 attempts (Spring `@Retryable` or Resilience4j `Retry`).
- Circuit breaker (Resilience4j) per downstream; fallback behaviour documented (e.g. print job stays queued).
- Map downstream errors to `IntegrationException` with a module error code.

## 9. Testing errors

For every error code there is at least one test asserting: HTTP status, `code`, `type`, field errors (if any), that nothing was persisted (for rollbacks), and that no stack trace or SQL appears in the body. `libs/test-support` provides `ProblemAssert.assertThatProblem(response).hasStatus(409).hasCode("INSUFFICIENT_STOCK")`.
