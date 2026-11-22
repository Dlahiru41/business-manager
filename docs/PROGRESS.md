# Build progress

Agents update this file at the end of every task and every phase. Keep it short and factual.

## Status

| Phase | Name | Status | Branch | Closed on |
| --- | --- | --- | --- | --- |
| 00 | Foundation | done (CI run on GitHub pending) | phase/00-foundation | 2026-11-22 |
| 01 | Catalog and pricing | not started | | |
| 02 | Inventory and procurement | not started | | |
| 03 | Sales, payments, sync and printing | not started | | |
| 04 | Finance and reporting | not started | | |
| 05 | Multi-business onboarding | not started | | |
| 06 | Scale-out to microservices | not started | | |

Status values: `not started` · `in progress` · `blocked` · `done`.

## Current phase

- Phase: 00 — Foundation
- Current task: none (phase 00 merged into `main` on 2026-11-22)
- Next step (exact, so a new session can resume): add a GitHub remote and push `main`; when every job of `.github/workflows/ci.yml` is green, tick the last DoD item below. Then start phase 01 with `/run-phase 01`.

## Task checklist (current phase)

Phase 00:
- [x] T00-01 Repository and build scaffold
- [x] T00-02 Shared kernel (`libs/shared-kernel`)
- [x] T00-03 Web support (`libs/web-support`) — JDBC `IdempotencyStore` + cleanup job land with T00-04 (they need the tenant transaction hook and the platform-admin role)
- [x] T00-04 Persistence support (`libs/persistence-support`) — ADR-012, ADR-013
- [x] T00-05 Observability baseline — trace in local Grafana checked with the DoD (needs compose)
- [x] T00-06 Security — ADR-014; Keycloak Testcontainers IT and the cross-tenant 404 test need real endpoints and are added with T00-07/T00-08
- [x] T00-07 Tenant module — ADR-012, ADR-015; extra platform endpoints `POST /tenants/{id}/suspend|reactivate` (TEN-04 needs a way to suspend)
- [x] T00-08 IAM module — ADR-016 (tenant roles as the `bmp_roles` user attribute; Keycloak 26.8 needs `kc-admin-api` and a mapper allow-list); `@Idempotent(redact)` keeps device secrets out of replay storage; generator option `containerDefaultToNull` (PATCH can tell "absent" from "empty", fixes receipt-template PATCH clearing omitted JSON fields)
- [x] T00-09 Audit module (skeleton) — per-tenant SHA-256 hash chain with gapless sequence (advisory lock), API-write interceptor, generic `DomainEvent` listener, `GET /api/v1/audit-records`; tamper detection proven in `AuditIT`
- [x] T00-10 Local environment and developer experience — compose (PostgreSQL 18.7, Keycloak 26.8.2, Mailpit, otel-lgtm with the "BMP overview" dashboard), `local` profile seeds both test tenants and their users (`IamProvisioning`), `http/*.http`, root README; Spring Boot Docker Compose support not used (compose is started explicitly)
- [x] T00-11 Architecture tests and documentation — `ModularityTests` (docs in `app/build/spring-modulith-docs`), `ArchitectureTest` with `HexagonalRules`/`CodingRules`/`SecurityRules`; READMEs for tenant, iam, audit
- [x] T00-12 CI pipeline — `.github/workflows/ci.yml` (build + gates, Spectral, oasdiff breaking check on PRs, CycloneDX SBOM + Trivy, signed image push on `main`), `contracts/.spectral.yaml`; not yet run on GitHub (no remote configured)
- [ ] Definition of Done
  - [x] `docker compose up` + `./gradlew :app:bootRun` start cleanly; `/actuator/health` UP (on a machine with its own PostgreSQL on 5432: `BMP_POSTGRES_PORT=55432`, see README)
  - [x] With tokens from local Keycloak: location created, settings read and updated, user created, device registered (curl run of the `http/*.http` flows)
  - [x] Tenant isolation, RLS coverage, idempotency, problem details, security (401/403/404, per operation from the contracts) tests green
  - [x] Logs are ECS JSON with trace/tenant/request ids outside `local` (`ObservabilityIT`); traces visible in local Grafana
  - [ ] All quality gates green in CI — run locally (build, Spectral, Trivy); waiting for the first GitHub run. The owner chose to merge into `main` before it (2026-11-22).
  - [x] ADR-011, ADR-012 and the event publication style ADR (ADR-015) written
  - [x] Phase summary written; `code-reviewer` run, every BLOCKER (none) and MAJOR (8) fixed

## Open questions

_One line each: date, question, assumption taken, ADR link._

- 2026-11-21 — Settings sanity limits (returnWindowDays ≤ 365, offlineMaxHours ≤ 720, periodLockDays ≤ 31) and numbering prefixes (PO-, GRN-, CN-) are assumptions in the schemas; confirm with the client.
- 2026-11-21 — Default role permissions follow 08-security.md §3 literally, so only OWNER has `tenant:read`/`tenant:*`; MANAGER etc. may need `tenant:read` for settings — assumption: least privilege, tenants extend roles via the iam API (ADR-014).
- 2026-11-22 — Users without e-mail get their first password from an administrator in the Keycloak console (no API for it in phase 00); disabled users keep already-issued access tokens until they expire (≤ 15 min). Assumption: acceptable for the pilot (ADR-016).
- 2026-11-22 — Location scope of role grants is stored and returned but not yet enforced; enforcement belongs to the handlers of location-bound modules (inventory, sales) from phase 02.
- 2026-11-22 — Personal Data Protection Act legal review is pending (ADR-017): lawful basis, retention per category, IP addresses in the audit trail, hosting region, breach procedure, processor agreement. Assumption: conservative defaults of ADR-017.
- 2026-11-22 — Spring Boot Docker Compose support (optional in T00-10) is not used: the stack is started explicitly, which keeps `bootRun` independent of Docker and avoids starting Keycloak from the app.
- 2026-11-22 — Phase review items deferred (no rule broken today; each needs design or volume first): idempotency records stuck `IN_PROGRESS` after a crash need a lease and recovery; API audit records should move to the outbox (today: synchronous, counted by `bmp.audit.lost` when lost); reject tenant-user tokens without an ACTIVE iam profile (closes the 15-minute window of disabled users); an allow-list of user clients (`azp`) besides the platform one; Keycloak fine-grained admin permissions for `bmp-admin`; key order of `iam.role_permission`/`user_role` (tenant_id first) and the globally unique `idp_subject`/`idp_client_id`; GitHub Actions pinned by commit SHA and image signing by digest; error categories of `METHOD_NOT_ALLOWED`/`UNAUTHENTICATED`; tenant defaults (LKR, Asia/Colombo) as configuration.
- 2026-11-22 — Currencies without two decimals (JPY, KWD, ...) are refused at tenant creation and in settings until money rounds to each currency's minor units.
- 2026-11-22 — Production migrations run as a separate job with `bmp.persistence.migrate-on-startup=true` and the migrator credentials; the app itself defaults to false (06 §1). The job itself arrives with the Helm charts (phase 06).
- 2026-11-21 — Sinhala (`si`) and Tamil (`ta`) message bundles are English placeholders (`i18n/*/messages_si|ta.properties`); translations needed before go-live.

Known questions from the client (Requirements Understanding Document §9), to be configured, not coded:
- Measured yield: 750 ml bottles per 18 kg can of oil (sets the oil conversion factor).
- Eggs counted singly or by tray; tray size.
- Wholesale price by quantity threshold, by customer, or both.
- Soya meat brands and packet sizes; own packet sizes for loose soya and dried fish.
- Credit customers and limits.
- Payment methods accepted.
- Printer make, model, paper width, connection; sample bill.
- Taxes shown on the bill.
- Number of users, who may change prices, number of billing counters.

## Phase summaries

_Filled in when each phase closes: what was built, ADRs, limitations._

### Phase 00 — Foundation (2026-11-22)

**Built.** A Gradle 9 multi-project build (convention plugins, pinned catalog, Error Prone + NullAway, Spotless,
JaCoCo gates, PIT wiring, contract-first OpenAPI generation) for a Spring Boot 4.2 / Java 25 modular monolith:
- `libs/shared-kernel`: Money/Quantity/Allocation, typed UUIDv7 ids, Tenant/Actor contexts (ScopedValue), error
  hierarchy, domain event envelope, BusinessDate, permission patterns, `@Sensitive`.
- `libs/web-support`: Problem Details with stable codes and i18n, request ids, access log, size limits, idempotency
  (JDBC store, replay, redaction of one-time secrets), ETag/If-Match, keyset cursors, JWT resource server with tenant
  resolution, platform-admin acting via `X-Tenant-Id`, permission evaluation, ECS logging with masking.
- `libs/persistence-support`: per-module Flyway, fail-closed RLS with a transaction-scoped tenant (ADR-013), JPA
  auditing, Modulith JDBC outbox with tenant-bound listeners, inbox, failed-event store and resubmission, number
  sequences (ADR-012), backlog health and gauge, ShedLock.
- `libs/test-support`: PostgreSQL 18 Testcontainers with the production role model, JWT test tokens, contract and
  event-contract validation, RLS coverage checker, ArchUnit rule sets.
- Modules: **tenant** (tenants, locations, versioned settings with JSON schemas, receipt templates; TEN-01..04),
  **iam** (users, roles, devices, Keycloak gateway with resilience and compensation; IAM-01..03; ADR-016), **audit**
  (hash-chained append-only trail of API writes and all domain events).
- App: actuator on a management port, OTLP tracing, Prometheus/OTLP metrics, Modulith verification and ArchUnit
  tests, local seed data. Local stack (`deploy/local`), `.http` collections, README, GitHub Actions pipeline.

**Verified.** `./gradlew build` green (all gates, unit + Testcontainers ITs incl. Keycloak 26.8.2). Locally:
compose + `bootRun` start cleanly, health UP; with tokens from local Keycloak: location created, settings read and
updated, user created (invitation in Mailpit), device registered, its token accepted, revoked and rejected; audit
trail filled; traces and metrics visible in Grafana. Spectral lint clean; Trivy scan of the SBOM clean. A fresh clone
compiles.

**ADRs.** 011 pinned versions · 012 number sequences per module · 013 fail-closed RLS · 014 identity, tenant claim
and permissions · 015 event publication style · 016 tenant roles in the identity provider · 017 personal data
protection (legal review pending).

**Review.** The `code-reviewer` agent found no BLOCKER and 8 MAJOR issues, all fixed in `6161cd8`: roles taken only
from where Keycloak puts them (platform role via allowed clients only, device tokens exactly `DEVICE`); idempotency
bound to the caller and refusals not stored; platform-administrator reads audited; Keycloak accounts created disabled
until their profile is stored; TEN-02 evidence so confirmed documents lock everyone; contract-driven 401/403 checks for
every operation; backlog out of readiness; a grant ceiling against privilege escalation. Most MINOR items were fixed
too (TEN-01 and default-template races, 4xx mapping, purge of completed events, stricter RLS check, erasable audit
network data, owner-free runtime, local credentials outside the jar); the rest are listed under open questions.

**Limitations / carried forward.**
- CI has not run on GitHub yet (no remote configured); its jobs were exercised locally (build, Spectral, Trivy).
- Location scope of role grants is stored, not enforced (phase 02 handlers). Permission-sensitive reads are not
  audited automatically. Disabled users keep issued access tokens until expiry (≤ 15 min).
- Security filter-chain spans appear as separate trace roots next to the HTTP request span; to be tidied with the
  observability work of phase 06.
- Failed-event administration is a job and a store, without an admin endpoint yet.
- `si`/`ta` messages are English placeholders.
