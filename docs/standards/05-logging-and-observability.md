# 05 — Logging and observability

Three signals, all correlated by `traceId`: **logs** (what happened), **metrics** (how much / how fast), **traces** (where time went). Plus a separate **audit trail** (who changed what), which is data, not logging.

## 1. Logging stack

- API: **SLF4J** only (`private static final Logger log = LoggerFactory.getLogger(X.class);`). Never `System.out`, `printStackTrace`, or `java.util.logging`.
- Backend: Logback (Spring Boot default).
- Format: Spring Boot **structured logging**, ECS JSON in every environment except `local` console:

```yaml
logging:
  structured:
    format:
      console: ecs          # 'local' profile overrides to plain text for readability
    ecs:
      service:
        name: bmp-platform
        environment: ${BMP_ENV:local}
  level:
    root: INFO
    com.bmp: INFO
    org.hibernate.SQL: WARN   # DEBUG only temporarily on local
```

## 2. Context in every log line (MDC)

| Key | Set by | Notes |
| --- | --- | --- |
| `traceId`, `spanId` | Micrometer Tracing (automatic) | Link logs ↔ traces |
| `requestId` | `RequestIdFilter` | From `X-Request-Id` or generated |
| `tenantId` | `TenantContextFilter` / event listener wrapper | UUID only, never tenant name |
| `userId` | security filter | Subject id, not username/email |
| `deviceId` | security filter (device tokens) | Till id |
| `module` | `ModuleMdcAspect` or listener wrapper | e.g. `inventory` |
| `eventId`, `eventType` | event listener wrapper | When processing an event |

- MDC must be propagated to async work: enable Micrometer context propagation (`spring.reactor.context-propagation`/`ContextSnapshot` for executors; virtual threads created by Spring inherit through `TaskDecorator`). Provide `MdcTaskDecorator` in `libs/web-support`.
- Always clear MDC in `finally` blocks of filters and listener wrappers.

## 3. Levels — when to use which

| Level | Use for | Examples |
| --- | --- | --- |
| `ERROR` | Something failed that needs a human; data or money may be affected. Always with stack trace. One per failure. | Unhandled exception (500), event failed after max retries, ledger imbalance detected |
| `WARN` | Unexpected but handled; may need attention if repeated. | Downstream retry exhausted with fallback, 403 access denied, drawer variance above threshold, negative stock created |
| `INFO` | Business events and lifecycle, at most a few per request. | `Bill completed`, `Goods receipt posted`, `Period closed`, app started, scheduled job summary |
| `DEBUG` | Diagnostic detail for developers; off in prod. | Price resolution steps, SQL parameter summaries, validation failures |
| `TRACE` | Do not use in committed code. | |

Rules:
- Use parameterized messages: `log.info("Bill completed billId={} total={} lines={}", billId, total, lineCount);` — never string concatenation.
- Prefer **key=value** pairs in messages, or structured arguments (`StructuredArguments.kv` is not needed with ECS; use SLF4J 2 fluent API `log.atInfo().addKeyValue("billId", id).log("Bill completed")`). Pick the fluent API for business events so fields are queryable.
- Log a business event **once**, in the application handler after the transaction commits (or in an after-commit listener), not in the controller and the repository.
- Exceptions: log once at the handling boundary (see `04-error-handling.md` §5). Pass the exception as the last argument so the stack trace is captured: `log.error("Event processing failed eventId={}", id, ex);`
- Do not log inside tight loops; aggregate ("Imported 500 rows, 3 rejected").
- Scheduled jobs log start (DEBUG) and a summary (INFO) with counts and duration.

## 4. What must never be logged

- Passwords, PINs, tokens (JWT, refresh, API keys, webhook secrets), `Authorization` headers, cookies.
- Full request/response bodies of POS and payment endpoints.
- Personal data: customer names, phone numbers, e-mails, addresses, NIC numbers. Log the **id** instead.
- Card data of any kind (we never receive it; if a field looks like a PAN, reject the request).

Enforce with:
- A Logback `MaskingConverter`/ECS customizer that masks patterns (JWT `eyJ...`, 13–19 digit sequences, e-mail, phone `+94...`).
- An ArchUnit rule forbidding logging of objects of types annotated `@Sensitive` (put `@Sensitive` on `Customer`, `Contact`, credentials types), and `toString()` of those types returns masked values.

## 5. HTTP access logging

- One access log line per request at INFO via a `OncePerRequestFilter` (not Tomcat access logs): method, path template (not raw path with ids), status, duration ms, response size, requestId, tenantId, userId, deviceId, idempotent replay flag.
- Health and metrics endpoints excluded.
- Slow requests (> 1 s) logged at WARN with the same fields.

## 6. Metrics (Micrometer → Prometheus)

Expose `/actuator/prometheus` (secured, internal port `management.server.port=8081`).

Technical (mostly automatic): `http.server.requests` (with `uri` template tag, never raw ids), JVM, HikariCP, Hibernate (statistics in non-prod), executor, virtual-thread pinning events (JFR), Spring Modulith event publication counts.

Business metrics (define in each module with `MeterRegistry`; tag with `tenant` only if tenant count is small — default: no tenant tag; use logs/reports for per-tenant views):

| Metric | Type | Module |
| --- | --- | --- |
| `bmp.bills.completed` (tags: channel) | counter | sales |
| `bmp.bills.amount` (tags: channel) | distribution summary | sales |
| `bmp.sync.uploads` (tags: result) | counter | sync |
| `bmp.sync.lag.seconds` | gauge per device (bounded) / summary | sync |
| `bmp.stock.movements` (tags: type) | counter | inventory |
| `bmp.stock.negative.items` | gauge | inventory |
| `bmp.journals.posted` | counter | finance |
| `bmp.ledger.imbalance` | counter (must stay 0) | finance |
| `bmp.events.failed` (tags: listener) | counter | all |
| `bmp.print.jobs` (tags: result) | counter | documents |
| `bmp.idempotency.replays` | counter | web-support |

Use `@Observed` (Micrometer Observation API) on application handlers to get a timer **and** a span with one annotation: `@Observed(name = "bmp.inventory.receive-goods")`.

## 7. Tracing (OpenTelemetry)

- Micrometer Tracing with the OpenTelemetry bridge and OTLP exporter (`management.otlp.tracing.endpoint`), or Spring Boot's OpenTelemetry starter if present in the pinned version.
- Sampling: 100% local/test, 10% prod by default plus always-sample errors (tail sampling in the collector).
- Spans for: incoming HTTP, JDBC (via `datasource-micrometer`), outgoing HTTP, event publication and each listener (`@Observed`), scheduled jobs.
- Propagate `traceparent` into event metadata (`DomainEvent` carries `traceparent`) and restore it in listeners so a bill's whole journey (sales → inventory → finance) is one trace.
- Add span attributes: `bmp.tenant_id`, `bmp.bill_id`, etc. (ids only).

## 8. Health, readiness, info

- `/actuator/health/liveness` and `/readiness` (Kubernetes probes). Readiness includes DB and, in phase 06, Kafka.
- Custom health indicators: event publication backlog (DOWN if incomplete publications > threshold for > 5 min), Flyway migration state.
- `/actuator/info` with git commit, build version, Java version.

## 9. Audit trail (not logs)

- Every write and permission-sensitive read is recorded by the `audit` module as **data** (`audit_record`), from domain events and a web interceptor: actor, device, tenant, action, resource type/id, before/after hash, IP, user agent, requestId, timestamp, previous record hash (hash chain).
- Audit records are append-only and retained per tenant retention settings. Logs are retained 30 days; audit is retained years.

## 10. Local observability

`deploy/local/compose.yaml` runs `grafana/otel-lgtm`; the app exports OTLP to it. Provide a starter Grafana dashboard JSON in `deploy/local/grafana/` for HTTP RED metrics, event backlog and business counters.

## 11. Tests for logging

- Unit test the masking converter with real-looking secrets and PII.
- An integration test asserts a completed bill emits exactly one INFO `Bill completed` line with `tenantId`, `billId`, `traceId` and no customer name (use `OutputCaptureExtension`).
