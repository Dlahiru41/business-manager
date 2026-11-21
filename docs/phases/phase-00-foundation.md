# Phase 00 — Foundation

## Goal
A running, secure, observable, multi-tenant modular monolith skeleton with all cross-cutting infrastructure in place, plus the `tenant` and `iam` modules. After this phase, adding a business module is only domain work.

## Read before starting
- `CLAUDE.md`
- All of `docs/standards/` (01–09)
- `docs/reference/domain-model.md` §1 and §4, `docs/reference/data-model.md` (tenant, iam, platform)

## Scope
`build-logic`, `libs/*`, `app`, `modules/tenant`, `modules/iam`, `modules/audit` (skeleton), `contracts/`, `deploy/local`, CI.

---

## Tasks

### T00-01 Repository and build scaffold
- Create the layout in `CLAUDE.md` §3. Gradle 9 wrapper, Kotlin DSL, `settings.gradle.kts` including all `libs/*`, `modules/*`, `app`.
- `gradle/libs.versions.toml`: look up and pin the latest **stable** versions of: Spring Boot 4.1.x (or 4.2.x if GA), Spring Modulith 2.x matching it, Spring Cloud 2025.1.x (for later), openapi-generator plugin, MapStruct, java-uuid-generator, ShedLock, Testcontainers 2.x, ArchUnit, swagger-request-validator, Error Prone + NullAway, JSpecify, Spotless, JaCoCo, PIT, CycloneDX, dependency-check, datasource-micrometer, Resilience4j. Record the chosen versions in an ADR (`ADR-011-pinned-versions.md`).
- Convention plugins in `build-logic/`:
  - `bmp.java-conventions`: Java 25 toolchain, `-Xlint:all -Werror -parameters`, Error Prone + NullAway (JSpecify mode, `com.bmp` annotated packages), Spotless (palantir-java-format), JaCoCo, `integrationTest` suite (JVM Test Suite), `java-test-fixtures`.
  - `bmp.spring-module`: applies java-conventions, Spring dependency management, MapStruct processor, openapi-generator task wired for `contracts/openapi/<module>.yaml` if present, PIT config for `com.bmp.<module>.domain..*`.
  - `bmp.spring-app`: Spring Boot plugin, `bootBuildImage` config, CycloneDX.
- **Acceptance:** `./gradlew build` succeeds on an empty skeleton; a deliberate formatting error fails `spotlessCheck`; a deliberate nullness bug fails compilation.

### T00-02 Shared kernel (`libs/shared-kernel`)
Implement with full unit tests:
- `Money`, `CurrencyCode` (ISO 4217 via `java.util.Currency`), `Quantity`, `UnitCode`, `Percentage`, `RoundingPolicy` (`LINE_HALF_UP_2` default), `Allocation.largestRemainder(Money total, List<BigDecimal> weights)`.
- Typed ids (`TenantId`, `UserId`, `DeviceId`, `LocationId`, ... add more per module later) + `IdGenerator` (UUIDv7).
- `TenantContext` (ScopedValue-based holder with `runWith(TenantId, Runnable/Callable)` and a ThreadLocal fallback bridge for filters), `ActorContext`.
- Exception hierarchy and `ErrorCode`/`ErrorCategory` from `04-error-handling.md`.
- `DomainEvent`, `EventMetadata`.
- `BusinessDate.of(Instant, ZoneId, cutoffHour)`.
- **Acceptance:** 100% branch coverage on Money/Quantity/Allocation; property-style tests showing allocation always sums to total.

### T00-03 Web support (`libs/web-support`)
- `RequestIdFilter`, `AccessLogFilter` (one line per request, path template), `MdcTaskDecorator`.
- `GlobalExceptionHandler` producing Problem Details exactly as in `04-error-handling.md` §3, with i18n titles (`MessageSource`, `en`/`si`/`ta` bundles; `si`/`ta` may start as English placeholders flagged in PROGRESS).
- Constraint-name → error-code registry (`ConstraintErrorRegistry`) that modules contribute to.
- `@Idempotent` + `IdempotencyInterceptor` + `IdempotencyStore` (JDBC, `platform.idempotency_record`) per `03-api-standards.md` §5, with cleanup job.
- ETag/If-Match helpers (`Etags.of(version)`, `IfMatch.require(request, currentVersion)`), `428`/`412` handling.
- Cursor pagination helpers (`CursorCodec`, `PageResponse<T>`).
- Jackson 3 configuration: BigDecimal as string for `Money`/`Quantity` serializers, `FAIL_ON_UNKNOWN_PROPERTIES=true` for requests, ISO dates.
- **Acceptance:** web-support tests with a dummy controller cover: problem format for each category, idempotent replay, key reuse 422, in-progress 409, 428 and 412.

### T00-04 Persistence support (`libs/persistence-support`)
- `TenantAwareDataSource` / transaction hook setting `app.tenant_id` per transaction (`06-persistence-and-data.md` §4); `CurrentTenantIdentifierResolver` for Hibernate `@TenantId`.
- Per-module Flyway configuration (`ModuleFlywayConfigurer`), migrator vs app DB roles, schema `platform` migrations (idempotency, inbox, failed_event, shedlock, operation, Modulith `event_publication`).
- Auditing base (`@CreatedDate` etc. via `Clock`), `JdbcClient` helpers, `InboxGuard` (insert-or-skip for listeners).
- ShedLock configuration.
- `RlsCoverageIT` utility usable by every module.
- **Acceptance:** an IT proves that with `bmp_app` role, a query without tenant context throws `TENANT_CONTEXT_MISSING` and a query with tenant A cannot see tenant B rows even through native SQL.

### T00-05 Observability baseline
- Structured ECS logging config, local plain-text profile, masking converter (`05-logging-and-observability.md` §4) with tests.
- Micrometer Prometheus registry, Observation + tracing with OTLP export, `@Observed` enabled, datasource-micrometer for JDBC spans.
- Actuator on port 8081, liveness/readiness groups, `EventBacklogHealthIndicator`.
- Event listener wrapper `EventContext.run(event, ...)` restoring tenant, MDC and trace parent.
- **Acceptance:** a request through the app produces one access log line and a trace visible in the local LGTM stack; logs include `traceId`, `tenantId`, `requestId`.

### T00-06 Security
- Resource server config, JWT → `BmpPrincipal` (userId, tenantId, roles, deviceId) converter, `TenantContextFilter`, `PermissionEvaluator` backed by `iam` (role → permissions, cached), method security, CORS from config, security headers, management port isolation.
- Keycloak realm export `deploy/local/keycloak/realm-bmp.json`: realm `bmp`, client `bmp-api` (bearer-only/audience), client `bmp-dev-cli` (for `.http` testing, PKCE), roles from `08-security.md` §3, two tenants (`pilot-shop`, `second-shop`) with one user per role each, protocol mappers for `tenant_id` and roles.
- `libs/test-support`: `JwtTestTokens.forRole(tenant, Role.CASHIER)` producing signed test tokens (test JWKS) so API tests do not need Keycloak; plus one Keycloak Testcontainers IT for the real flow.
- ArchUnit rule: public controller methods must have `@PreAuthorize`.
- **Acceptance:** 401/403 tests pass; a cross-tenant request returns 404.

### T00-07 Tenant module
Endpoints (`contracts/openapi/tenant.yaml`):

| Method | Path | Permission | Notes |
| --- | --- | --- | --- |
| POST | `/api/v1/tenants` | `platform:tenants` | Platform admin creates a tenant (self-service sign-up comes in phase 05) |
| GET / PATCH | `/api/v1/tenant` | `tenant:read` / `tenant:write` | Current tenant profile |
| GET / POST | `/api/v1/locations` | `tenant:read` / `tenant:write` | |
| GET / PATCH | `/api/v1/locations/{id}` | | |
| GET | `/api/v1/settings` | `tenant:read` | All groups |
| GET / PATCH | `/api/v1/settings/{group}` | `tenant:read` / `tenant:settings` | Validated against a per-group JSON schema; defaults from `domain-model.md` §4 |
| GET / POST / PATCH | `/api/v1/receipt-templates[/{id}]` | `tenant:write` | |

- Document numbers (PO, GRN, credit note) must be allocated in the **same transaction** as the document, which cannot happen through another module. So each module owns its own `number_sequence` table, and `persistence-support` provides a reusable `NumberSequences` helper (`UPDATE ... RETURNING`). The tenant module only stores per-tenant prefixes and formats as settings. Record this in ADR-012. (Bill numbers are different: they are allocated on the device per register, see phase 03.)
- Events: `TenantCreated`, `SettingsChanged`, `LocationCreated`.
- Tenant status cache (Caffeine, 60 s) used by `TenantContextFilter`.
- Rules:
  - **TEN-01** A tenant has at least one location; creating a tenant creates a default location "Main shop".
  - **TEN-02** Currency and time zone cannot change once any business document exists. Tenant is the most upstream module, so declare the SPI `tenant.api.spi.BusinessActivityProbe` (`boolean hasBusinessDocuments(TenantId)`), with a default implementation that returns `true` once the tenant is older than 1 day. Sales replaces it in phase 03. Until then, only `platform:tenants` may change these fields.
  - **TEN-03** Settings changes are versioned and audited; unknown keys rejected (422 `UNKNOWN_SETTING`).
  - **TEN-04** Suspended tenants: all non-GET requests → 403 `TENANT_SUSPENDED`.

### T00-08 IAM module
Endpoints (`contracts/openapi/iam.yaml`):

| Method | Path | Permission |
| --- | --- | --- |
| GET / POST | `/api/v1/users` | `iam:users` |
| GET / PATCH | `/api/v1/users/{id}` | `iam:users` |
| PUT | `/api/v1/users/{id}/roles` | `iam:users` |
| GET / POST / PATCH | `/api/v1/roles[/{id}]` | `iam:roles` |
| GET | `/api/v1/me`, `/api/v1/me/permissions` | authenticated |
| POST | `/api/v1/devices` | `iam:devices` |
| POST | `/api/v1/devices/{id}/revoke` | `iam:devices` |

- User creation calls Keycloak Admin API through a `IdentityProviderGateway` port (adapter with timeouts, retries, circuit breaker; `IntegrationException` on failure). Never stores passwords.
- Seed system roles and permissions on `TenantCreated` (listener).
- Device registration creates a Keycloak client/credential for the device, returns the one-time secret, publishes `DeviceRegistered`.
- Rules: **IAM-01** last OWNER cannot be removed; **IAM-02** system roles cannot be deleted; **IAM-03** revoked devices are rejected immediately (token introspection cache ≤ 60 s or Keycloak session revocation).

### T00-09 Audit module (skeleton)
- `audit_record` table with hash chain; `AuditWriter` API; web interceptor recording writes (method, path template, resource id, actor, status); listener recording all `DomainEvent`s generically.
- `GET /api/v1/audit-records?resourceType=&resourceId=&from=&to=` (`audit:read`).
- **Acceptance:** hash chain verification test detects a tampered row.

### T00-10 Local environment and developer experience
- `deploy/local/compose.yaml`: postgres:18 (with init script creating roles `bmp_migrator`, `bmp_app`), keycloak (26.x, realm import), mailpit, grafana/otel-lgtm.
- Spring Boot Docker Compose support for `local` profile (optional), `.env.example`.
- `http/http-client.env.json` + `http/tenant.http`, `http/iam.http`.
- `README.md` at repo root: prerequisites, start, run, test, troubleshooting.

### T00-11 Architecture tests and documentation
- `ModularityTests` (verify + Documenter output to `build/spring-modulith-docs`).
- `ArchitectureTest` with the rules in `09-testing-and-quality.md` §5.
- Module READMEs for tenant, iam, audit.

### T00-12 CI pipeline
- `.github/workflows/ci.yml` implementing `09-testing-and-quality.md` §6 (image push only on `main`, using repository secrets placeholders).
- Spectral ruleset `contracts/.spectral.yaml` (operationId required, examples required, error responses required, `x-permission` required).

---

## Definition of Done
- [ ] `docker compose up` + `./gradlew :app:bootRun` starts cleanly; `/actuator/health` UP.
- [ ] Using `http/tenant.http` with a token from local Keycloak: create location, read/update settings, create user, register device.
- [ ] Tenant isolation, RLS coverage, idempotency, problem-details, security (401/403/404) tests green.
- [ ] Logs are JSON with trace/tenant/request ids; a trace is visible in local Grafana.
- [ ] All quality gates green in CI.
- [ ] ADR-011 (versions) and ADR-012 (number sequences per module) written; event publication style ADR written (see `07-events-and-messaging.md` §3).
- [ ] `docs/PROGRESS.md` phase summary written.
